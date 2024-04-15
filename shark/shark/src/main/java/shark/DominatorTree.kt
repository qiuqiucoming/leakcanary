@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "CANNOT_OVERRIDE_INVISIBLE_MEMBER")
package shark

import shark.ObjectDominators.DominatorNode
import shark.internal.hppc.LongLongScatterMap
import shark.internal.hppc.LongLongScatterMap.ForEachCallback
import shark.internal.hppc.LongScatterSet

class DominatorTree(expectedElements: Int = 4) {

  /**
   * Map of objects to their dominator.
   *
   * If an object is dominated by more than one GC root then its dominator is set to
   * [ValueHolder.NULL_REFERENCE].
   */
  private val dominated = LongLongScatterMap(expectedElements)

  /**
   * Records that [objectId] is a root.
   */
  fun updateDominatedAsRoot(objectId: Long): Boolean {
    return updateDominated(objectId, ValueHolder.NULL_REFERENCE)
  }

  companion object OutputDebug {
    var printDominate = 0;
  }

  /**
   * Records that [objectId] can be reached through [parentObjectId], updating the dominator for
   * [objectId] to be either [parentObjectId] if [objectId] has no known dominator and otherwise to
   * the Lowest Common Dominator between [parentObjectId] and the previously determined dominator
   * for [objectId].
   *
   * [parentObjectId] should already have been added via [updateDominatedAsRoot]. Failing to do
   * that will throw [IllegalStateException] on future calls.
   *
   * This implementation is optimized with the assumption that the graph is visited as a breadth
   * first search, so when objectId already has a known dominator then its dominator path is
   * shorter than the dominator path of [parentObjectId].
   *
   * @return true if [objectId] already had a known dominator, false otherwise.
   */
  fun updateDominated(
    objectId: Long,
    parentObjectId: Long
  ): Boolean {
    // SharkLog.d { "updateDominated dominate size: ${dominated.getSizeOfMap()}" }
    val dominatedSlot = dominated.getSlot(objectId)

    val hasDominator = dominatedSlot != -1

    if (!hasDominator || parentObjectId == ValueHolder.NULL_REFERENCE) {
      dominated[objectId] = parentObjectId
    } else {
      val currentDominator = dominated.getSlotValue(dominatedSlot)
      if (currentDominator != ValueHolder.NULL_REFERENCE) {
        // We're looking for the Lowest Common Dominator between currentDominator and
        // parentObjectId. We know that currentDominator likely has a shorter dominator path than
        // parentObjectId since we're exploring the graph with a breadth first search. So we build
        // a temporary hash set for the dominator path of currentDominator (since it's smaller)
        // and then go through the dominator path of parentObjectId checking if any id exists
        // in that hash set.
        // Once we find either a common dominator or none, we update the map accordingly
        val currentDominators = LongScatterSet()
        var dominator = currentDominator
        // 搜集该节点当前所有的dominator
        while (dominator != ValueHolder.NULL_REFERENCE) {
          currentDominators.add(dominator)
          // 这里需要理解的是key-value的对应关系其实就是上下节点的对应关系，一个节点对应的value，其实是她上游节点对应的key
          val nextDominatorSlot = dominated.getSlot(dominator)
          if (nextDominatorSlot == -1) {
            throw IllegalStateException(
              "Did not find dominator for $dominator when going through the dominator chain for $currentDominator: $currentDominators"
            )
          } else {
            dominator = dominated.getSlotValue(nextDominatorSlot)
          }
        }
        // 查找公共dominate节点
        // 综合当前的dominator列表和parentObjectId的dominator分支，为objectId选出一个公共的最低层的dominator节点
        // 理解这个是理解dominator tree的关键：
        // 找出公共dominator，然后将objectId指向dominator，这样做的目的是什么？，即防止节点被多次引用，而导致计算分支大小的时候重复计算
        // 所以之前一直存在的一个问题就得到了解决，就是dominator tree是怎么解决一个object被多个ParentObject引用的问题的呢，
        // 那就是找出他们最低层的公共节点作为我的dominator节点
        // 这样利用dominator tree就能准确地算出retained size
        dominator = parentObjectId
        while (dominator != ValueHolder.NULL_REFERENCE) {
          // 返回parentObjectId与objectId同一个的dominator
          if (dominator in currentDominators) {
            break
          }
          val nextDominatorSlot = dominated.getSlot(dominator)
          if (nextDominatorSlot == -1) {
            throw IllegalStateException(
              "Did not find dominator for $dominator when going through the dominator chain for $parentObjectId"
            )
          } else {
            dominator = dominated.getSlotValue(nextDominatorSlot)
          }
        }
        // 这里将objectId的管理者指向公共的最底层node，保证了不管何时，任意一个node在dominator trree上都会回有一个dominator
        dominated[objectId] = dominator
        if (OutputDebug.printDominate != 5) {
          SharkLog.d { "updateDominated dominatedSlot is $dominatedSlot, " +
            "currentDominator is $currentDominator, " +
            "dominated[objectId] is ${dominated[objectId]}" }
          OutputDebug.printDominate++
        }

      }
    }
    return hasDominator
  }

  private class MutableDominatorNode {
    var shallowSize = 0
    var retainedSize = 0
    var retainedCount = 0
    val dominated = mutableListOf<Long>()
  }

  fun buildFullDominatorTree(computeSize: (Long) -> Int): Map<Long, DominatorNode> {
    val dominators = mutableMapOf<Long, MutableDominatorNode>()
    dominated.forEach(ForEachCallback {key, value ->
      // create entry for dominated
      dominators.getOrPut(key) {
        MutableDominatorNode()
      }
      // If dominator is null ref then we still have an entry for that, to collect all dominator
      // roots.
      dominators.getOrPut(value) {
        MutableDominatorNode()
      }.dominated += key
    })

    val allReachableObjectIds = dominators.keys.toSet() - ValueHolder.NULL_REFERENCE

    val retainedSizes = computeRetainedSizes(allReachableObjectIds) { objectId ->
      val shallowSize = computeSize(objectId)
      dominators.getValue(objectId).shallowSize = shallowSize
      shallowSize
    }

    dominators.forEach { (objectId, node) ->
      if (objectId != ValueHolder.NULL_REFERENCE) {
        val (retainedSize, retainedCount) = retainedSizes.getValue(objectId)
        node.retainedSize = retainedSize
        node.retainedCount = retainedCount
      }
    }

    val rootDominator = dominators.getValue(ValueHolder.NULL_REFERENCE)
    rootDominator.retainedSize = rootDominator.dominated.map { dominators[it]!!.retainedSize }.sum()
    rootDominator.retainedCount =
      rootDominator.dominated.map { dominators[it]!!.retainedCount }.sum()

    // Sort children with largest retained first
    dominators.values.forEach { node ->
      node.dominated.sortBy { -dominators.getValue(it).retainedSize }
    }

    return dominators.mapValues { (_, node) ->
      DominatorNode(
        node.shallowSize, node.retainedSize, node.retainedCount, node.dominated
      )
    }
  }

  /**
   * Computes the size retained by [retainedObjectIds] using the dominator tree built using
   * [updateDominatedAsRoot]. The shallow size of each object is provided by [computeSize].
   * @return a map of object id to retained size.
   */
  fun computeRetainedSizes(
    retainedObjectIds: Set<Long>,
    computeSize: (Long) -> Int
  ): Map<Long, Pair<Int, Int>> {
    val nodeRetainedSizes = mutableMapOf<Long, Pair<Int, Int>>()
    retainedObjectIds.forEach { objectId ->
      nodeRetainedSizes[objectId] = 0 to 0
    }

    SharkLog.d { "computeRetainedSizes nodeRetainedSizes size is ${nodeRetainedSizes.size}" }
    nodeRetainedSizes.keys.forEach { k -> SharkLog.d { "computeRetainedSizes ObjectI: $k" }}

    dominated.forEach(object : ForEachCallback {
      override fun onEntry(
        key: Long,
        value: Long
      ) {
        // lazy computing of instance size
        var instanceSize = -1

        // If the entry is a node, add its size to nodeRetainedSizes
        // 在dominator tree上找到retained节点就把大小加上来
        nodeRetainedSizes[key]?.let { (currentRetainedSize, currentRetainedCount) ->
          SharkLog.d { "computeRetainedSizes forEach key is $key, value is ($currentRetainedSize, $currentRetainedCount)" }
          instanceSize = computeSize(key)
          nodeRetainedSizes[key] = currentRetainedSize + instanceSize to currentRetainedCount + 1
          SharkLog.d { "computeRetainedSizes forEach after inc, value is (${nodeRetainedSizes[key]}, ${currentRetainedCount + 1})" }
        }
        // 从任意一个非NULL_REFERENCE节点开始查找，只要出现一条分支，遍历到它的一个dominator在nodeRetainedSizes内，
        // 就说明遍历到这个dominator之前通过dominatedByNextNode收集的节点全部都在某一个retained节点下面，
        // 意思就是，这些节点的大小全都算在该retained节点的retainedSize内
        if (value != ValueHolder.NULL_REFERENCE) {
          // 把value放在dominator，代表上游节点
          var dominator = value
          // 先把本节点的key即objectId放进dominatedByNextNode，然后一直往上游找
          val dominatedByNextNode = mutableListOf(key)
          // 只要没有找到NULL_REFERENCE就一直找
          while (dominator != ValueHolder.NULL_REFERENCE) {
            // If dominator is a node
            // 如果nodeRetainedSizes中包含该节点的value，
            // 那么说明nodeRetainedSizes中的某个节点是该节点以及之前所有收集到dominatedByNextNode中的节点的上游节点
            if (nodeRetainedSizes.containsKey(dominator)) {
              // Update dominator for all objects in the dominator path so far to directly point
              // to it. We're compressing the dominator path to make this iteration faster and
              // faster as we go through each entry.
              // 将这些所有的下游节点的dominated都指向自己，相当于进行了一次收缩
              dominatedByNextNode.forEach { objectId ->
                dominated[objectId] = dominator
              }
              if (instanceSize == -1) {
                // 收集key对应的节点的大小，该key对应的节点是所有下游节点的最顶层节点
                instanceSize = computeSize(key)
              }
              // Update retained size for that node
              val (currentRetainedSize, currentRetainedCount) = nodeRetainedSizes.getValue(
                dominator
              )
              // 更新一次retained size
              nodeRetainedSizes[dominator] =
                (currentRetainedSize + instanceSize) to currentRetainedCount + 1
              dominatedByNextNode.clear()
            } else {
              dominatedByNextNode += dominator
            }
            dominator = dominated[dominator]
          }
          // Update all dominator for all objects found in the dominator path after the last node
          dominatedByNextNode.forEach { objectId ->
            dominated[objectId] = ValueHolder.NULL_REFERENCE
          }
        }
      }
    })
    dominated.release()

    return nodeRetainedSizes
  }
}
