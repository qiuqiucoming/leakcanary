package leakcanary

import android.annotation.SuppressLint
import android.app.Service
import android.os.Build
import android.os.Handler
import android.os.IBinder
import leakcanary.internal.friendly.checkMainThread
import shark.SharkLog
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/**
 * Expects services to become weakly reachable soon after they receive the [Service.onDestroy]
 * callback.
 */
@SuppressLint("PrivateApi")
class ServiceWatcher(private val deletableObjectReporter: DeletableObjectReporter) : InstallableWatcher {

  private val servicesToBeDestroyed = WeakHashMap<IBinder, WeakReference<Service>>()

  private val activityThreadClass by lazy { Class.forName("android.app.ActivityThread") }

  private val activityThreadInstance by lazy {
    activityThreadClass.getDeclaredMethod("currentActivityThread").invoke(null)!!
  }

  private val activityThreadServices by lazy {
    val mServicesField =
      activityThreadClass.getDeclaredField("mServices").apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    mServicesField[activityThreadInstance] as Map<IBinder, Service>
  }

  private var uninstallActivityThreadHandlerCallback: (() -> Unit)? = null
  private var uninstallActivityManager: (() -> Unit)? = null

  override fun install() {
    checkMainThread()
    check(uninstallActivityThreadHandlerCallback == null) {
      "ServiceWatcher already installed"
    }
    check(uninstallActivityManager == null) {
      "ServiceWatcher already installed"
    }
    try {
      // TODO 搞清楚service是如何监听其onDestroy事件的
      swapActivityThreadHandlerCallback { mCallback ->
        // 清理环境时使用，以防止影响正常功能
        uninstallActivityThreadHandlerCallback = {
          swapActivityThreadHandlerCallback {
            mCallback
          }
        }
        // 自定义的事件回调代理，只是代理STOP_SERVICE事件，其他事件还是扔给ActivityThread处理
        Handler.Callback { msg ->
          // https://github.com/square/leakcanary/issues/2114
          // On some Motorola devices (Moto E5 and G6), the msg.obj returns an ActivityClientRecord
          // instead of an IBinder. This crashes on a ClassCastException. Adding a type check
          // here to prevent the crash.
          if (msg.obj !is IBinder) {
            return@Callback false
          }

          if (msg.what == STOP_SERVICE) {
            val key = msg.obj as IBinder
            // 将发生destory的service添加到列表里，因为从发生destory事件之后，系统还有很多步骤需要处理，因为为了防止提早触发
            // 会干扰内存泄漏的检测结果，不再该事件处理开始时触发，而是放到该事件最后一个步骤即serviceDoneExecuting里触发检测
            activityThreadServices[key]?.let {
              onServicePreDestroy(key, it)
            }
          }
          mCallback?.handleMessage(msg) ?: false
        }
      }
      swapActivityManager { activityManagerInterface, activityManagerInstance ->
        // 同样先保存原有处理逻辑
        uninstallActivityManager = {
          swapActivityManager { _, _ ->
            activityManagerInstance
          }
        }
        Proxy.newProxyInstance(
          activityManagerInterface.classLoader, arrayOf(activityManagerInterface)
        ) { _, method, args ->
          // 在此真正地进行内存泄漏触发检测，方法的拦截，
          if (METHOD_SERVICE_DONE_EXECUTING == method.name) {
            val token = args!![0] as IBinder
            if (servicesToBeDestroyed.containsKey(token)) {
              onServiceDestroyed(token)
            }
          }
          try {
            if (args == null) {
              method.invoke(activityManagerInstance)
            } else {
              method.invoke(activityManagerInstance, *args)
            }
          } catch (invocationException: InvocationTargetException) {
            throw invocationException.targetException
          }
        }
      }
    } catch (ignored: Throwable) {
      SharkLog.d(ignored) { "Could not watch destroyed services" }
    }
  }

  override fun uninstall() {
    checkMainThread()
    uninstallActivityManager?.invoke()
    uninstallActivityThreadHandlerCallback?.invoke()
    uninstallActivityManager = null
    uninstallActivityThreadHandlerCallback = null
  }

  private fun onServicePreDestroy(
    token: IBinder,
    service: Service
  ) {
    servicesToBeDestroyed[token] = WeakReference(service)
  }

  private fun onServiceDestroyed(token: IBinder) {
    servicesToBeDestroyed.remove(token)?.also { serviceWeakReference ->
      serviceWeakReference.get()?.let { service ->
        deletableObjectReporter.expectDeletionFor(
          service, "${service::class.java.name} received Service#onDestroy() callback"
        )
      }
    }
  }

  // 找到系统ActivityThread中的事件处理回调函数，目的是替换成自定义的回调代理
  private fun swapActivityThreadHandlerCallback(swap: (Handler.Callback?) -> Handler.Callback?) {
    val mHField =
      activityThreadClass.getDeclaredField("mH").apply { isAccessible = true }
    val mH = mHField[activityThreadInstance] as Handler

    val mCallbackField =
      Handler::class.java.getDeclaredField("mCallback").apply { isAccessible = true }
    val mCallback = mCallbackField[mH] as Handler.Callback?
    mCallbackField[mH] = swap(mCallback)
  }

  @SuppressLint("PrivateApi")
  private fun swapActivityManager(swap: (Class<*>, Any) -> Any) {
    val singletonClass = Class.forName("android.util.Singleton")
    val mInstanceField =
      singletonClass.getDeclaredField("mInstance").apply { isAccessible = true }

    val singletonGetMethod = singletonClass.getDeclaredMethod("get")

    val (className, fieldName) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      "android.app.ActivityManager" to "IActivityManagerSingleton"
    } else {
      "android.app.ActivityManagerNative" to "gDefault"
    }

    val activityManagerClass = Class.forName(className)
    val activityManagerSingletonField =
      activityManagerClass.getDeclaredField(fieldName).apply { isAccessible = true }
    val activityManagerSingletonInstance = activityManagerSingletonField[activityManagerClass]

    // Calling get() instead of reading from the field directly to ensure the singleton is
    // created.
    val activityManagerInstance = singletonGetMethod.invoke(activityManagerSingletonInstance)

    val iActivityManagerInterface = Class.forName("android.app.IActivityManager")
    mInstanceField[activityManagerSingletonInstance] =
      swap(iActivityManagerInterface, activityManagerInstance!!)
  }

  companion object {
    private const val STOP_SERVICE = 116

    private const val METHOD_SERVICE_DONE_EXECUTING = "serviceDoneExecuting"
  }
}
