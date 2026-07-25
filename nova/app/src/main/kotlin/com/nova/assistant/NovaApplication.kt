package com.nova.assistant

import android.app.Application

class NovaApplication : Application() {

    /** Shared by the UI and the listening service so both talk to the same agent. */
    val container: NovaContainer by lazy { NovaContainer(this) }
}
