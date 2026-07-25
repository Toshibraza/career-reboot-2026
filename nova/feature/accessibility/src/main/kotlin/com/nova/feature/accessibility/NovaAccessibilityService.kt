package com.nova.feature.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nova.core.agent.ScrollDirection
import com.nova.core.agent.match.FuzzyMatcher

/**
 * Nova's hands.
 *
 * Android gives an app no way to touch another app's UI; an AccessibilityService is the
 * sanctioned exception, and it is what turns "open WhatsApp" into "message Amit". The system
 * owns this object's lifecycle, so the only way for the rest of the app to reach it is the
 * [connected] reference published in [onServiceConnected].
 *
 * No screen content is read outside a command: [onAccessibilityEvent] deliberately does nothing.
 * Everything here is pull-based, triggered by something the user actually asked for.
 */
class NovaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
    }

    override fun onDestroy() {
        // Guard against a newer instance having already claimed the slot.
        if (connected === this) connected = null
        super.onDestroy()
    }

    /**
     * Intentionally empty. Nova acts on demand, not on every window change — subscribing to
     * the event firehose would mean continuously observing everything the user does.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // --- Global actions ------------------------------------------------------------------

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /** Null when the platform is too old to support it, so the caller can say why. */
    fun lockScreen(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            null
        }

    fun takeScreenshot(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            null
        }

    // --- Screen interaction --------------------------------------------------------------

    /**
     * Taps the visible control whose label best matches [query].
     *
     * Returns the matched label on success so the caller can confirm out loud what it pressed.
     * Confirming matters here: a fuzzy match that silently hits the wrong control in a payment
     * screen is the worst thing this class could do.
     */
    fun tapLabel(query: String): TapResult {
        val root = rootInActiveWindow ?: return TapResult.NoWindow

        val labelled = ScreenNodes.visible(root).filter { ScreenNodes.label(it).isNotBlank() }
        if (labelled.isEmpty()) return TapResult.NotFound

        // Actionable nodes are searched first, and only if none match do inert ones get a
        // look. Without this a page title outranks a real control: on MIUI's Settings screen
        // the search box is named "Search", so "tap search settings" scored the heading
        // "Settings" higher and reported it wasn't tappable. Headings should never beat
        // buttons when the whole point is to press something.
        val actionable = labelled.filter { ScreenNodes.clickable(it) != null }

        val match = FuzzyMatcher.best(query, actionable) { ScreenNodes.label(it) }
            ?: FuzzyMatcher.best(query, labelled) { ScreenNodes.label(it) }
            ?: return TapResult.NotFound

        val target = ScreenNodes.clickable(match) ?: return TapResult.NotClickable(ScreenNodes.label(match))

        return if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            TapResult.Tapped(ScreenNodes.label(match))
        } else {
            TapResult.NotClickable(ScreenNodes.label(match))
        }
    }

    fun scroll(direction: ScrollDirection): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = ScreenNodes.firstOrNull(root) { it.isScrollable } ?: return false

        // The platform has no left/right scroll action; forward and backward follow the
        // scrollable's own orientation, which is the right behaviour for a horizontal pager.
        val action = when (direction) {
            ScrollDirection.DOWN, ScrollDirection.RIGHT ->
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD

            ScrollDirection.UP, ScrollDirection.LEFT ->
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return scrollable.performAction(action)
    }

    /** Types [text] into the focused field, or the first editable one if nothing has focus. */
    fun type(text: String): Boolean {
        val root = rootInActiveWindow ?: return false

        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: ScreenNodes.firstOrNull(root) { it.isEditable }
            ?: return false

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    sealed interface TapResult {
        data class Tapped(val label: String) : TapResult
        data class NotClickable(val label: String) : TapResult
        data object NotFound : TapResult

        /** No window content available — usually a secure screen that blocks inspection. */
        data object NoWindow : TapResult
    }

    companion object {
        /**
         * The live service, or null when the user has not enabled it.
         *
         * A static reference to a Service is normally a leak; here the system controls the
         * lifetime and clears it in [onDestroy], and it is the only channel the platform
         * offers for reaching a bound AccessibilityService.
         */
        @Volatile
        var connected: NovaAccessibilityService? = null
            private set

        /**
         * Whether the user has switched Nova on in Settings.
         *
         * Read from Settings.Secure rather than checking [connected], because the service can
         * be enabled but not yet bound — the UI should not nag in that window.
         */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, NovaAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            return splitter.any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
