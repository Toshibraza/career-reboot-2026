package com.nova.core.agent.screen

/**
 * Reads what is currently on screen.
 *
 * Implemented in `:feature:accessibility` over the node tree. A future implementation could
 * OCR a MediaProjection capture for apps that expose nothing to accessibility — same interface,
 * and nothing that consumes a [ScreenSnapshot] would change.
 */
interface ScreenReader {

    /** Null when nothing can be read: service off, or a secure screen that blocks inspection. */
    suspend fun snapshot(): ScreenSnapshot?
}
