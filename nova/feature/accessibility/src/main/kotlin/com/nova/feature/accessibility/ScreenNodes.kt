package com.nova.feature.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Traversal helpers over the accessibility node tree.
 *
 * Nodes are not recycled here. `AccessibilityNodeInfo.recycle()` is deprecated from API 33 and
 * the platform pools them itself; calling it on a node still referenced elsewhere causes far
 * worse bugs than the allocation it saves.
 */
internal object ScreenNodes {

    /** Depth cap. Real hierarchies are ~20 deep; anything past this is a cycle or a hostile tree. */
    private const val MAX_DEPTH = 40

    /** Every node the user can actually see, flattened. */
    fun visible(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (node.isVisibleToUser) found += node
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)
        return found
    }

    /** First visible node satisfying [predicate], depth-first. */
    fun firstOrNull(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? = visible(root).firstOrNull(predicate)

    /**
     * The text a user would call this control by.
     *
     * Content description comes first: an icon-only button has no text but is described as
     * "Send", which is exactly what someone would say out loud.
     */
    fun label(node: AccessibilityNodeInfo): String =
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.hintText?.toString()?.takeIf { it.isNotBlank() }
            ?: ""

    /**
     * The nearest node that will actually accept a click — often an ancestor, because the
     * labelled `TextView` inside a button is rarely the clickable one.
     */
    fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_DEPTH) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            hops++
        }
        return null
    }
}
