package com.nova.core.agent

import kotlinx.coroutines.Job

/**
 * Every command currently acting on the phone, whichever entry point started it.
 *
 * The stop button's reach used to end at the UI's own job: a multi-step task started by the
 * wake word or a routine had no cancellation path at all, while the same task started by a tap
 * had one. An agent acting on someone's phone must be stoppable from the one control the user
 * can see, regardless of which door the command came in through.
 *
 * Entry points register the job that runs each command; [cancelAll] is what the orb's stop
 * action calls. Tracking is a set rather than a single slot because two entry points can be
 * live at once — one holding the runtime's lock, one queued behind it — and stopping must
 * reach both.
 */
class ActiveCommands {

    private val jobs = mutableSetOf<Job>()

    /** Registers [job] until it completes, however it completes. */
    fun track(job: Job) {
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs -= job } }
    }

    /** Stops every running and queued command. */
    fun cancelAll() {
        val toCancel = synchronized(jobs) { jobs.toList() }
        toCancel.forEach { it.cancel() }
    }
}
