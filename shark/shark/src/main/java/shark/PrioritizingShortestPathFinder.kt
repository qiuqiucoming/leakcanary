@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import java.util.ArrayDeque
import java.util.Deque
import shark.PrioritizingShortestPathFinder.Event.StartedFindingDominators
import shark.PrioritizingShortestPathFinder.Event.StartedFindingPathsToRetainedObjects
import shark.PrioritizingShortestPathFinder.VisitTracker.Dominated
import shark.PrioritizingShortestPathFinder.VisitTracker.Visited
import shark.internal.ReferencePathNode
import shark.internal.ReferencePathNode.ChildNode
import shark.internal.ReferencePathNode.RootNode
import shark.internal.ReferencePathNode.RootNode.LibraryLeakRootNode
import shark.internal.ReferencePathNode.RootNode.NormalRootNode
import shark.internal.hppc.LongScatterSet
import shark.internal.invalidObjectIdErrorMessage

/**
 * Not thread safe.
 *
 * Finds the shortest path from leaking references to a gc root, first ignoring references
 * identified as "to visit last" and then visiting them as needed if no path is
 * found.
 */
// ShortestPathFinder真正的实现类
class PrioritizingShortestPathFinder private constructor(
  private val graph: HeapGraph,
  private val listener: Event.Listener,
  private val objectReferenceReader: ReferenceReader<HeapObject>,
  private val gcRootProvider: GcRootProvider,
  private val computeRetainedHeapSize: Boolean
) : ShortestPathFinder {

  class Factory(
    private val listener: Event.Listener,
    private val referenceReaderFactory: ReferenceReader.Factory<HeapObject>,
    private val gcRootProvider: GcRootProvider,
    private val computeRetainedHeapSize: Boolean
  ) : ShortestPathFinder.Factory {
    override fun createFor(heapGraph: HeapGraph): ShortestPathFinder {
      return PrioritizingShortestPathFinder(
        graph = heapGraph,
        listener = listener,
        objectReferenceReader = referenceReaderFactory.createFor(heapGraph),
        gcRootProvider = gcRootProvider,
        computeRetainedHeapSize = computeRetainedHeapSize
      )
    }
  }

  // TODO Enum or sealed? class makes it possible to report progress. Enum
  // provides ordering of events.
  sealed interface Event {
    object StartedFindingPathsToRetainedObjects : Event
    object StartedFindingDominators : Event

    fun interface Listener {
      fun onEvent(event: Event)
    }
  }

  private sealed class VisitTracker {

    abstract fun visited(
      objectId: Long,
      parentObjectId: Long
    ): Boolean

    class Dominated(expectedElements: Int) : VisitTracker() {
      /**
       * Tracks visited objects and their dominator.
       * If an object is not in [dominatorTree] then it hasn't been enqueued yet.
       * If an object is in [dominatorTree] but not in [State.toVisitSet] nor [State.toVisitLastSet]
       * then it has already been dequeued.
       *
       * If an object is dominated by more than one GC root then its dominator is set to
       * [ValueHolder.NULL_REFERENCE].
       */
      val dominatorTree = DominatorTree(expectedElements)
      override fun visited(
        objectId: Long,
        parentObjectId: Long
      ): Boolean {
        return dominatorTree.updateDominated(objectId, parentObjectId)
      }
    }

    class Visited(expectedElements: Int) : VisitTracker() {
      /**
       * Set of visited objects.
       */
      private val visitedSet = LongScatterSet(expectedElements)
      override fun visited(
        objectId: Long,
        parentObjectId: Long
      ): Boolean {
        return !visitedSet.add(objectId)
      }
    }
  }

  private class State(
    val leakingObjectIds: LongScatterSet,
    val computeRetainedHeapSize: Boolean,
    estimatedVisitedObjects: Int
  ) {

    /** Set of objects to visit */
    val toVisitQueue: Deque<ReferencePathNode> = ArrayDeque()

    /**
     * Objects to visit when [toVisitQueue] is empty.
     */
    val toVisitLastQueue: Deque<ReferencePathNode> = ArrayDeque()

    /**
     * Enables fast checking of whether a node is already in the queue.
     */
    val toVisitSet = LongScatterSet()
    val toVisitLastSet = LongScatterSet()

    val queuesNotEmpty: Boolean
      get() = toVisitQueue.isNotEmpty() || toVisitLastQueue.isNotEmpty()

    val visitTracker = if (computeRetainedHeapSize) {
      Dominated(estimatedVisitedObjects)
    } else {
      Visited(estimatedVisitedObjects)
    }

    /**
     * A marker for when we're done exploring the graph of higher priority references and start
     * visiting the lower priority references, at which point we won't add any reference to
     * the high priority queue anymore.
     */
    var visitingLast = false
  }

  override fun findShortestPathsFromGcRoots(
    leakingObjectIds: Set<Long>
  ): PathFindingResults {
    SharkLog.d { "findShortestPathsFromGcRoots leakingObjectIds size ${leakingObjectIds.size}" }
    listener.onEvent(StartedFindingPathsToRetainedObjects)
    // Estimate of how many objects we'll visit. This is a conservative estimate, we should always
    // visit more than that but this limits the number of early array growths.
    val estimatedVisitedObjects = (graph.instanceCount / 2).coerceAtLeast(4)

    val state = State(
      leakingObjectIds = leakingObjectIds.toLongScatterSet(),
      computeRetainedHeapSize = computeRetainedHeapSize,
      estimatedVisitedObjects = estimatedVisitedObjects
    )
    return state.findPathsFromGcRoots()
  }

  private fun Set<Long>.toLongScatterSet(): LongScatterSet {
    val longScatterSet = LongScatterSet()
    longScatterSet.ensureCapacity(size)
    forEach { longScatterSet.add(it) }
    return longScatterSet
  }

  private fun State.findPathsFromGcRoots(): PathFindingResults {
    // 先把默认的GC节点推进去
    enqueueGcRoots()

    val shortestPathsToLeakingObjects = mutableListOf<ReferencePathNode>()
    visitingQueue@ while (queuesNotEmpty) {
      // 开始从GC节点往下遍历
      val node = poll()
      if (leakingObjectIds.contains(node.objectId)) {
        // 把发生泄漏的对象添加进来
        shortestPathsToLeakingObjects.add(node)
        // Found all refs, stop searching (unless computing retained size)
        if (shortestPathsToLeakingObjects.size == leakingObjectIds.size()) {
          if (computeRetainedHeapSize) {
            listener.onEvent(StartedFindingDominators)
          } else {
            break@visitingQueue
          }
        }
      }

      // 找到节点的heap对象，上面有关于这个对象的所有节点信息
      val heapObject = try {
        graph.findObjectById(node.objectId)
      } catch (objectIdNotFound: IllegalArgumentException) {
        // This should never happen (a heap should only have references to objects that exist)
        // but when it does happen, let's at least display how we got there.
        throw RuntimeException(graph.invalidObjectIdErrorMessage(node), objectIdNotFound)
      }
      // TODO 调试信息
      if (leakingObjectIds.contains(node.objectId)) {
        SharkLog.d { "findPathsFromGcRoots leak heapObject: ${heapObject.asClass?.name}" }
      }
      objectReferenceReader.read(heapObject).forEach { reference ->
        // 遍历该节点的所有引用，
        // 这里只是利用这对引用关系生成一个child node信息，貌似这个节点是个新的节点，
        // 但是后面插入到dominator tree上时，如果发现有objectId相同的节点，则会有另一番处理，
        // 这个处理就是帮助dominator tree 调整node的位置
        val newNode = ChildNode(
          objectId = reference.valueObjectId,
          parent = node,
          lazyDetailsResolver = reference.lazyDetailsResolver
        )
        // 将该对象插入队列
        enqueue(newNode, isLowPriority = reference.isLowPriority)
      }
    }
    SharkLog.d { "findPathsFromGcRoots shortestPathsToLeakingObjects size: ${shortestPathsToLeakingObjects.size}" }
    return PathFindingResults(
      shortestPathsToLeakingObjects,
      if (visitTracker is Dominated) visitTracker.dominatorTree else null
    )
  }

  private fun State.poll(): ReferencePathNode {
    return if (!visitingLast && !toVisitQueue.isEmpty()) {
      val removedNode = toVisitQueue.poll()
      toVisitSet.remove(removedNode.objectId)
      removedNode
    } else {
      visitingLast = true
      val removedNode = toVisitLastQueue.poll()
      toVisitLastSet.remove(removedNode.objectId)
      removedNode
    }
  }

  private fun State.enqueueGcRoots() {
    gcRootProvider.provideGcRoots(graph).forEach { gcRootReference ->
      enqueue(
        gcRootReference.matchedLibraryLeak?.let { matchedLibraryLeak ->
          LibraryLeakRootNode(
            gcRootReference.gcRoot,
            matchedLibraryLeak
          )
        } ?: NormalRootNode(
          gcRootReference.gcRoot
        ),
        isLowPriority = gcRootReference.isLowPriority
      )
    }
  }

  @Suppress("ReturnCount")
  private fun State.enqueue(
    node: ReferencePathNode,
    isLowPriority: Boolean
  ) {
    if (node.objectId == ValueHolder.NULL_REFERENCE) {
      return
    }

    val parentObjectId = when (node) {
      is RootNode -> ValueHolder.NULL_REFERENCE
      is ChildNode -> node.parent.objectId
    }

    // Note: when computing dominators, this has a side effects of updating
    // the dominator for node.objectId.
    // 尝试更新objectId的dominator，当然只是尝试，因为可能已经在旧的dominator列表里了
    val alreadyEnqueued = visitTracker.visited(node.objectId, parentObjectId)

    val visitLast = visitingLast || isLowPriority

    when {
      alreadyEnqueued -> {
        val bumpPriority =
          !visitLast &&
            node.objectId !in toVisitSet &&
            // This could be false if node had already been visited.
            node.objectId in toVisitLastSet

        if (bumpPriority) {
          // Move from "visit last" to "visit first" queue.
          toVisitQueue.add(node)
          toVisitSet.add(node.objectId)
          val nodeToRemove = toVisitLastQueue.first { it.objectId == node.objectId }
          toVisitLastQueue.remove(nodeToRemove)
          toVisitLastSet.remove(node.objectId)
        }
      }

      visitLast -> {
        toVisitLastQueue.add(node)
        toVisitLastSet.add(node.objectId)
      }

      else -> {
        toVisitQueue.add(node)
        toVisitSet.add(node.objectId)
      }
    }
  }
}

