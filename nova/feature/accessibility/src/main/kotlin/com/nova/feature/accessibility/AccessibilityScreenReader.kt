package com.nova.feature.accessibility

import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import com.nova.core.agent.screen.Bounds
import com.nova.core.agent.screen.ElementRole
import com.nova.core.agent.screen.ScreenElement
import com.nova.core.agent.screen.ScreenReader
import com.nova.core.agent.screen.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Rect

/**
 * Reads the screen from the accessibility node tree.
 *
 * Exact where OCR would guess: these are the app's own labels, roles and coordinates, with no
 * transcription step to get them wrong. The trade-off is that it sees only what an app exposes
 * to accessibility — a canvas-rendered game or a Flutter view with no semantics is invisible
 * here, which is where a MediaProjection-plus-OCR reader would earn its place behind the same
 * [ScreenReader] interface.
 */
class AccessibilityScreenReader(context: Context) : ScreenReader {

    private val packageManager = context.applicationContext.packageManager

    override suspend fun snapshot(): ScreenSnapshot? = withContext(Dispatchers.Main) {
        val service = NovaAccessibilityService.connected ?: return@withContext null
        val root = service.rootInActiveWindow ?: return@withContext null

        val packageName = root.packageName?.toString()

        val elements = ScreenNodes.visible(root)
            .mapNotNull { node ->
                val label = ScreenNodes.label(node)
                // Containers with no label of their own carry no information a planner can
                // use, and there are hundreds of them in a real hierarchy.
                if (label.isBlank() && !node.isClickable && !node.isEditable) return@mapNotNull null

                ScreenElement(
                    label = label,
                    role = node.role(),
                    clickable = ScreenNodes.clickable(node) != null,
                    editable = node.isEditable,
                    checked = if (node.isCheckable) node.isChecked else null,
                    bounds = node.bounds(),
                )
            }
            .distinctBy { it.label to it.role }

        ScreenSnapshot(
            packageName = packageName,
            appLabel = packageName?.let(::appLabel),
            elements = elements,
        )
    }

    private fun AccessibilityNodeInfo.role(): ElementRole = when {
        isEditable -> ElementRole.TEXT_FIELD
        isCheckable -> ElementRole.CHECKABLE
        isClickable -> ElementRole.BUTTON
        className?.contains("Image") == true -> ElementRole.IMAGE
        childCount > 0 -> ElementRole.CONTAINER
        else -> ElementRole.TEXT
    }

    private fun AccessibilityNodeInfo.bounds(): Bounds {
        val rect = Rect()
        getBoundsInScreen(rect)
        return Bounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    /** Turns a package name into something worth saying out loud. */
    private fun appLabel(packageName: String): String? = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrElse {
        if (it is PackageManager.NameNotFoundException) null else null
    }
}
