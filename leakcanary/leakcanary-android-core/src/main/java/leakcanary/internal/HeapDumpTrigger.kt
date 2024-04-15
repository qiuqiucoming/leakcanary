package leakcanary.internal

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources.NotFoundException
import android.os.Handler
import android.os.SystemClock
import com.squareup.leakcanary.core.R
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import leakcanary.AppWatcher
import leakcanary.EventListener.Event.DumpingHeap
import leakcanary.EventListener.Event.HeapDump
import leakcanary.EventListener.Event.HeapDumpFailed
import leakcanary.GcTrigger
import leakcanary.KeyedWeakReference
import leakcanary.LeakCanary.Config
import leakcanary.RetainedObjectTracker
import leakcanary.internal.HeapDumpControl.ICanHazHeap.Nope
import leakcanary.internal.HeapDumpControl.ICanHazHeap.NotifyingNope
import leakcanary.internal.InternalLeakCanary.onRetainInstanceListener
import leakcanary.internal.NotificationReceiver.Action.CANCEL_NOTIFICATION
import leakcanary.internal.NotificationReceiver.Action.DUMP_HEAP
import leakcanary.internal.NotificationType.LEAKCANARY_LOW
import leakcanary.internal.RetainInstanceEvent.CountChanged.BelowThreshold
import leakcanary.internal.RetainInstanceEvent.CountChanged.DumpHappenedRecently
import leakcanary.internal.RetainInstanceEvent.CountChanged.DumpingDisabled
import leakcanary.internal.RetainInstanceEvent.NoMoreObjects
import leakcanary.internal.friendly.measureDurationMillis
import shark.AndroidResourceIdNames
import shark.SharkLog

internal class HeapDumpTrigger(
  private val application: Application,
  private val backgroundHandler: Handler,
  private val retainedObjectTracker: RetainedObjectTracker,
  private val gcTrigger: GcTrigger,
  private val configProvider: () -> Config
) {

  private val notificationManager
    get() =
      application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  private val applicationVisible
    get() = applicationInvisibleAt == -1L

  @Volatile
  private var checkScheduledAt: Long = 0L

  private var lastDisplayedRetainedObjectCount = 0

  private var lastHeapDumpUptimeMillis = 0L

  private val scheduleDismissRetainedCountNotification = {
    dismissRetainedCountNotification()
  }

  private val scheduleDismissNoRetainedOnTapNotification = {
    dismissNoRetainedOnTapNotification()
  }

  /**
   * When the app becomes invisible, we don't dump the heap immediately. Instead we wait in case
   * the app came back to the foreground, but also to wait for new leaks that typically occur on
   * back press (activity destroy).
   */
  private val applicationInvisibleLessThanWatchPeriod: Boolean
    get() {
      val applicationInvisibleAt = applicationInvisibleAt
      return applicationInvisibleAt != -1L && SystemClock.uptimeMillis() - applicationInvisibleAt < AppWatcher.retainedDelayMillis
    }

  @Volatile
  private var applicationInvisibleAt = -1L

  // Needs to be lazy because on Android 16, UUID.randomUUID().toString() will trigger a disk read
  // violation by calling RandomBitsSupplier.getUnixDeviceRandom()
  // Can't be lazy because this is a var.
  private var currentEventUniqueId: String? = null

  fun onApplicationVisibilityChanged(applicationVisible: Boolean) {
    if (applicationVisible) {
      applicationInvisibleAt = -1L
    } else {
      applicationInvisibleAt = SystemClock.uptimeMillis()
      // Scheduling for after watchDuration so that any destroyed activity has time to become
      // watch and be part of this analysis.
      /**
       * 应用变为隐藏之后，不会立即触发leak检测流程，因为可能用户会立即回到应用，
       * 因此会有一个延时retainedDelayMillis，这段时间也可以等待一些由于应用
       * 隐藏导致的新的内存泄漏发生。
       */
      scheduleRetainedObjectCheck(
        delayMillis = AppWatcher.retainedDelayMillis
      )
    }
  }

  /**
   * 真正检测当前retain object并dump的入口，
   * 该方法是一个private，
   * 对外暴露延时函数scheduleRetainedObjectCheck
   * 该方法对于是否进行dumpHeap之前会进行三个内容的校验：
   * 1. iCanHasHeap是否满足条件
   * 2. retained object个数是否达到retained阈值
   * 3. 距离上一次dumpHeap的时间是否达到dump阈值
   */
  private fun checkRetainedObjects() {
    val iCanHasHeap = HeapDumpControl.iCanHasHeap()

    val config = configProvider()

    // 条件一的校验
    if (iCanHasHeap is Nope) {

      if (iCanHasHeap is NotifyingNope) {
        // 即使不能够dump，也会检测retained object
        // Before notifying that we can't dump heap, let's check if we still have retained object.
        var retainedReferenceCount = retainedObjectTracker.retainedObjectCount

        if (retainedReferenceCount > 0) {
          gcTrigger.runGc()
          retainedReferenceCount = retainedObjectTracker.retainedObjectCount
        }

        val nopeReason = iCanHasHeap.reason()
        val wouldDump = !checkRetainedCount(
          retainedReferenceCount, config.retainedVisibleThreshold, nopeReason
        )

        if (wouldDump) {
          // retained object的数量达到dump的条件，但是由于iCanHasHeap的原因不能dump，因此在这里要通知原因
          val uppercaseReason = nopeReason[0].toUpperCase() + nopeReason.substring(1)
          onRetainInstanceListener.onEvent(DumpingDisabled(uppercaseReason))
          showRetainedCountNotification(
            objectCount = retainedReferenceCount,
            contentText = uppercaseReason
          )
        }
      } else {
        SharkLog.d {
          application.getString(
            R.string.leak_canary_heap_dump_disabled_text, iCanHasHeap.reason()
          )
        }
      }
      return
    }

    var retainedReferenceCount = retainedObjectTracker.retainedObjectCount

    if (retainedReferenceCount > 0) {
      gcTrigger.runGc()
      retainedReferenceCount = retainedObjectTracker.retainedObjectCount
    }

    // 条件二的校验，满足iCanHasHeap的条件，在这里进行retained object的校验
    if (checkRetainedCount(retainedReferenceCount, config.retainedVisibleThreshold)) return

    val now = SystemClock.uptimeMillis()
    val elapsedSinceLastDumpMillis = now - lastHeapDumpUptimeMillis
    // 条件三的校验，距离上次dump小于一分钟，则需要延时等待，然后再手动触发一次checkRetainedObjects
    if (elapsedSinceLastDumpMillis < WAIT_BETWEEN_HEAP_DUMPS_MILLIS) {
      onRetainInstanceListener.onEvent(DumpHappenedRecently)
      showRetainedCountNotification(
        objectCount = retainedReferenceCount,
        contentText = application.getString(R.string.leak_canary_notification_retained_dump_wait)
      )
      scheduleRetainedObjectCheck(
        delayMillis = WAIT_BETWEEN_HEAP_DUMPS_MILLIS - elapsedSinceLastDumpMillis
      )
      return
    }

    // 同时满足：
    // 1. iCanHasHeap条件
    // 2. retained object个数达到要求
    // 3. 同时距离上一次dump也超过了dump时间阈值
    dismissRetainedCountNotification()
    val visibility = if (applicationVisible) "visible" else "not visible"
    dumpHeap(
      retainedReferenceCount = retainedReferenceCount,
      retry = true,
      reason = "$retainedReferenceCount retained objects, app is $visibility"
    )
  }

  private fun dumpHeap(
    retainedReferenceCount: Int,
    retry: Boolean,
    reason: String
  ) {
    // 和Shark进行dump信息交互的方式
    val directoryProvider =
      InternalLeakCanary.createLeakDirectoryProvider(InternalLeakCanary.application)
    val heapDumpFile = directoryProvider.newHeapDumpFile()

    val durationMillis: Long
    if (currentEventUniqueId == null) {
      currentEventUniqueId = UUID.randomUUID().toString()
    }
    try {
      // dump开始前，抛出DumpingHeap事件:
      // 日志(LogcatEventListener)
      // 通知(NotificationEventListener)
      InternalLeakCanary.sendEvent(DumpingHeap(currentEventUniqueId!!))
      if (heapDumpFile == null) {
        throw RuntimeException("Could not create heap dump file")
      }
      saveResourceIdNamesToMemory()
      val heapDumpUptimeMillis = SystemClock.uptimeMillis()
      KeyedWeakReference.heapDumpUptimeMillis = heapDumpUptimeMillis
      durationMillis = measureDurationMillis {
        // 默认直接用java的dump方法输出到一个hprof文件
        configProvider().heapDumper.dumpHeap(heapDumpFile)
      }
      if (heapDumpFile.length() == 0L) {
        throw RuntimeException("Dumped heap file is 0 byte length")
      }
      lastDisplayedRetainedObjectCount = 0
      lastHeapDumpUptimeMillis = SystemClock.uptimeMillis()
      retainedObjectTracker.clearObjectsTrackedBefore(heapDumpUptimeMillis.milliseconds)
      currentEventUniqueId = UUID.randomUUID().toString()
      // dump结束后，抛出HeapDump事件:
      // 日志(LogcatEventListener)
      // 通知(NotificationEventListener)
      // 抛给服务进行分析(RemoteWorkManagerHeapAnalyzer)
      InternalLeakCanary.sendEvent(HeapDump(currentEventUniqueId!!, heapDumpFile, durationMillis, reason))
    } catch (throwable: Throwable) {
      InternalLeakCanary.sendEvent(HeapDumpFailed(currentEventUniqueId!!, throwable, retry))
      if (retry) {
        scheduleRetainedObjectCheck(
          delayMillis = WAIT_AFTER_DUMP_FAILED_MILLIS
        )
      }
      showRetainedCountNotification(
        objectCount = retainedReferenceCount,
        contentText = application.getString(
          R.string.leak_canary_notification_retained_dump_failed
        )
      )
      return
    }
  }

  /**
   * Stores in memory the mapping of resource id ints to their corresponding name, so that the heap
   * analysis can label views with their resource id names.
   */
  private fun saveResourceIdNamesToMemory() {
    val resources = application.resources
    AndroidResourceIdNames.saveToMemory(
      getResourceTypeName = { id ->
        try {
          resources.getResourceTypeName(id)
        } catch (e: NotFoundException) {
          null
        }
      },
      getResourceEntryName = { id ->
        try {
          resources.getResourceEntryName(id)
        } catch (e: NotFoundException) {
          null
        }
      })
  }

  fun onDumpHeapReceived(forceDump: Boolean) {
    backgroundHandler.post {
      dismissNoRetainedOnTapNotification()
      gcTrigger.runGc()
      val retainedReferenceCount = retainedObjectTracker.retainedObjectCount
      if (!forceDump && retainedReferenceCount == 0) {
        SharkLog.d { "Ignoring user request to dump heap: no retained objects remaining after GC" }
        @Suppress("DEPRECATION")
        val builder = Notification.Builder(application)
          .setContentTitle(
            application.getString(R.string.leak_canary_notification_no_retained_object_title)
          )
          .setContentText(
            application.getString(
              R.string.leak_canary_notification_no_retained_object_content
            )
          )
          .setAutoCancel(true)
          .setContentIntent(NotificationReceiver.pendingIntent(application, CANCEL_NOTIFICATION))
        val notification =
          Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
        notificationManager.notify(
          R.id.leak_canary_notification_no_retained_object_on_tap, notification
        )
        backgroundHandler.postDelayed(
          scheduleDismissNoRetainedOnTapNotification,
          DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS
        )
        lastDisplayedRetainedObjectCount = 0
        return@post
      }

      SharkLog.d { "Dumping the heap because user requested it" }
      dumpHeap(retainedReferenceCount, retry = false, "user request")
    }
  }

  /**
   * 根据当前retained对象的个数，判断下一步动作，
   * 1. 如果首次检测retained object个数为0，则退出，等待下一次触发
   * 2. 如果retained object不为0，则在达到阈值之前，会触发循环检测
   * 该retained object的个数校验在生命周期触发之后，会不停调用，直到达到阈值，进行dumpHeap
   * @param  retainedKeysCount 当前retained object的数量
   * @param  retainedVisibleThreshold 当前thredshold阈值
   * @param  nopeReason 当前不能dump的原因
   * @return true --- retained数量被清除或者retained数量小于threshold
   *         false --- retained object的数量达到threshold
   */
  private fun checkRetainedCount(
    retainedKeysCount: Int,
    retainedVisibleThreshold: Int,
    nopeReason: String? = null
  ): Boolean {
    val countChanged = lastDisplayedRetainedObjectCount != retainedKeysCount
    lastDisplayedRetainedObjectCount = retainedKeysCount
    // 如果没有retained object，那么就没有必要继续循环检测，直接退出，等到下一次的触发
    if (retainedKeysCount == 0) {
      if (countChanged) {
        SharkLog.d { "All retained objects have been garbage collected" }
        onRetainInstanceListener.onEvent(NoMoreObjects)
        showNoMoreRetainedObjectNotification()
      }
      return true
    }

    val applicationVisible = applicationVisible
    val applicationInvisibleLessThanWatchPeriod = applicationInvisibleLessThanWatchPeriod

    if (countChanged) {
      val whatsNext = if (applicationVisible) {
        if (retainedKeysCount < retainedVisibleThreshold) {
          "not dumping heap yet (app is visible & < $retainedVisibleThreshold threshold)"
        } else {
          if (nopeReason != null) {
            "would dump heap now (app is visible & >=$retainedVisibleThreshold threshold) but $nopeReason"
          } else {
            "dumping heap now (app is visible & >=$retainedVisibleThreshold threshold)"
          }
        }
      } else if (applicationInvisibleLessThanWatchPeriod) {
        val wait =
          AppWatcher.retainedDelayMillis - (SystemClock.uptimeMillis() - applicationInvisibleAt)
        if (nopeReason != null) {
          "would dump heap in $wait ms (app just became invisible) but $nopeReason"
        } else {
          "dumping heap in $wait ms (app just became invisible)"
        }
      } else {
        if (nopeReason != null) {
          "would dump heap now (app is invisible) but $nopeReason"
        } else {
          "dumping heap now (app is invisible)"
        }
      }

      SharkLog.d {
        val s = if (retainedKeysCount > 1) "s" else ""
        "Found $retainedKeysCount object$s retained, $whatsNext"
      }
    }

    // retained object个数没有达到阈值，则继续检测
    if (retainedKeysCount < retainedVisibleThreshold) {
      if (applicationVisible || applicationInvisibleLessThanWatchPeriod) {
        if (countChanged) {
          onRetainInstanceListener.onEvent(BelowThreshold(retainedKeysCount))
        }
        showRetainedCountNotification(
          objectCount = retainedKeysCount,
          contentText = application.getString(
            R.string.leak_canary_notification_retained_visible, retainedVisibleThreshold
          )
        )
        // 从这里进入实现反复进行retained object检测
        scheduleRetainedObjectCheck(
          delayMillis = WAIT_FOR_OBJECT_THRESHOLD_MILLIS
        )
        return true
      }
    }
    return false
  }

  /**
   * 暴露给user调用的检测check + dump的唯一入口，
   * 这是一次retained object的检测尝试
   *
   *  该检测方法的入口：
   *  1. 相关组件destroy生命周期触发，由scheduleRetainedObjectCheck进入
   *  2. 应用变为不可见的回调触发，由onApplicationVisibilityChanged进入
   *  3. 达到dump条件，但是距离上次dump时间没达到阈值，再次触发，由checkRetainedObjects进入
   *  4. dump异常，如果retry为true，再次尝试
   *  5. retained object的个数大于0，小于阈值，则继续检测
   * @param delayMillis 当前距离检测retain object阈值的时间
   */
  fun scheduleRetainedObjectCheck(
    delayMillis: Long = 0L
  ) {
    SharkLog.d { "调用scheduleRetainedObjectCheck $delayMillis" }
    val checkCurrentlyScheduledAt = checkScheduledAt
    if (checkCurrentlyScheduledAt > 0) {
      return
    }
    checkScheduledAt = SystemClock.uptimeMillis() + delayMillis
    backgroundHandler.postDelayed({
      checkScheduledAt = 0
      checkRetainedObjects()
    }, delayMillis)
  }

  private fun showNoMoreRetainedObjectNotification() {
    backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
    if (!Notifications.canShowNotification) {
      return
    }
    val builder = Notification.Builder(application)
      .setContentTitle(
        application.getString(R.string.leak_canary_notification_no_retained_object_title)
      )
      .setContentText(
        application.getString(
          R.string.leak_canary_notification_no_retained_object_content
        )
      )
      .setAutoCancel(true)
      .setContentIntent(NotificationReceiver.pendingIntent(application, CANCEL_NOTIFICATION))
    val notification =
      Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
    notificationManager.notify(R.id.leak_canary_notification_retained_objects, notification)
    backgroundHandler.postDelayed(
      scheduleDismissRetainedCountNotification, DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS
    )
  }

  private fun showRetainedCountNotification(
    objectCount: Int,
    contentText: String
  ) {
    backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
    if (!Notifications.canShowNotification) {
      return
    }
    @Suppress("DEPRECATION")
    val builder = Notification.Builder(application)
      .setContentTitle(
        application.getString(R.string.leak_canary_notification_retained_title, objectCount)
      )
      .setContentText(contentText)
      .setAutoCancel(true)
      .setContentIntent(NotificationReceiver.pendingIntent(application, DUMP_HEAP))
    val notification =
      Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
    notificationManager.notify(R.id.leak_canary_notification_retained_objects, notification)
  }

  private fun dismissRetainedCountNotification() {
    backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
    notificationManager.cancel(R.id.leak_canary_notification_retained_objects)
  }

  private fun dismissNoRetainedOnTapNotification() {
    backgroundHandler.removeCallbacks(scheduleDismissNoRetainedOnTapNotification)
    notificationManager.cancel(R.id.leak_canary_notification_no_retained_object_on_tap)
  }

  companion object {
    internal const val WAIT_AFTER_DUMP_FAILED_MILLIS = 5_000L
    private const val WAIT_FOR_OBJECT_THRESHOLD_MILLIS = 2_000L
    private const val DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS = 30_000L
    private const val WAIT_BETWEEN_HEAP_DUMPS_MILLIS = 60_000L
  }
}
