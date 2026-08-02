package com.nova.feature.device

import com.nova.core.agent.ActionExecutor
import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction
import com.nova.core.agent.web.UrlOpener
import com.nova.core.agent.web.asQueryParameter

/**
 * Plays what was asked for.
 *
 * A YouTube search URL rather than a deep link, because a deep link needs a video id and Raza
 * only has a phrase. Android hands the link to the YouTube app when it is installed, so this
 * lands in the app for most people and in a browser for everyone else — one path, no branching
 * on what happens to be installed.
 *
 * Separate from [DeviceActionExecutor] because it needs nothing that one owns: no torch, no
 * audio manager, no package registry. Every action still has exactly one owner.
 */
class MediaActionExecutor(
    private val urlOpener: UrlOpener,
) : ActionExecutor {

    override val name: String = "media"

    override fun canHandle(action: NovaAction): Boolean = action is NovaAction.PlayMedia

    override suspend fun execute(action: NovaAction): ActionResult {
        val query = (action as? NovaAction.PlayMedia)?.query?.trim()
            ?: return ActionResult.Unhandled(action)

        if (query.isBlank()) return ActionResult.Failure("What would you like me to play?")

        val url = "https://www.youtube.com/results?search_query=${query.asQueryParameter()}"
        val opened = runCatching { urlOpener.open(url) }.getOrDefault(false)

        return if (opened) {
            ActionResult.Success("Playing $query.")
        } else {
            ActionResult.Failure("I couldn't find anything on this phone to play that with.")
        }
    }
}
