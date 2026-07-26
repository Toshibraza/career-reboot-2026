package com.nova.feature.vision

import com.nova.core.agent.ActionExecutor
import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction
import com.nova.core.agent.RequiredPermission
import com.nova.core.agent.vision.OcrSummary

/** Reads on-screen text that the accessibility node tree cannot see. */
class VisionActionExecutor(
    private val reader: ScreenTextReader,
) : ActionExecutor {

    override val name: String = "vision"

    override fun canHandle(action: NovaAction): Boolean = action is NovaAction.ReadScreenText

    override suspend fun execute(action: NovaAction): ActionResult =
        when (val result = reader.read()) {
            is OcrResult.Text -> ActionResult.Success(OcrSummary.summarise(result.lines))

            // Distinct from "I couldn't read it": there was a screen, it was captured, and it
            // genuinely had no text on it.
            OcrResult.NothingFound -> ActionResult.Success("I couldn't find any text.")

            is OcrResult.Unavailable -> if ("accessibility" in result.reason) {
                ActionResult.NeedsPermission(
                    RequiredPermission.ACCESSIBILITY_SERVICE,
                    result.reason,
                )
            } else {
                ActionResult.Failure(result.reason)
            }
        }
}
