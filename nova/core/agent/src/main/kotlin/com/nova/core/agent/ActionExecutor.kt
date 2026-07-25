package com.nova.core.agent

/**
 * Carries out one class of [NovaAction].
 *
 * Executors are registered as a list with the runtime and probed in order, so a capability is
 * added by writing a new executor and registering it — never by editing a `when` block that
 * every module has to keep in sync. Phase 2's accessibility executor and Phase 5's Windows
 * executor plug in here.
 */
interface ActionExecutor {

    val name: String

    fun canHandle(action: NovaAction): Boolean

    suspend fun execute(action: NovaAction): ActionResult
}
