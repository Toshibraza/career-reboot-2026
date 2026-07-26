package com.nova.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nova.feature.accessibility.NovaAccessibilityService
import com.nova.feature.localllm.LocalModelStore
import com.nova.feature.localllm.ModelStatus
import com.nova.assistant.ui.NovaScreen
import com.nova.assistant.ui.NovaViewModel
import com.nova.assistant.ui.theme.NovaTheme

class MainActivity : ComponentActivity() {

    /**
     * Command injected over adb, in debug builds only. See [readCommandExtra].
     */
    private val injectedCommand = mutableStateOf<String?>(null)

    /**
     * Set when Nova was opened by the assist gesture rather than its launcher icon.
     *
     * This is the hands-free entry point that costs nothing: a power-button hold or corner
     * swipe, with no microphone held open in the background.
     */
    private val assistRequested = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        injectedCommand.value = readCommandExtra(intent)
        assistRequested.value = intent?.action == Intent.ACTION_ASSIST

        setContent {
            NovaTheme {
                val context = LocalContext.current
                val viewModel: NovaViewModel = viewModel(factory = NovaViewModel.Factory)
                val state by viewModel.state.collectAsState()

                var micGranted by remember { mutableStateOf(hasMicPermission()) }

                // Two sources, because neither alone is right. The settings entry covers the
                // moment after a rebind when the service is enabled but not yet bound; the
                // live binding covers the reverse, and drives recomposition so the prompt
                // disappears the instant Nova can actually act.
                var accessibilityInSettings by remember {
                    mutableStateOf(NovaAccessibilityService.isEnabled(this@MainActivity))
                }
                val boundService by NovaAccessibilityService.connection.collectAsState()
                val accessibilityEnabled = accessibilityInSettings || boundService != null
                var alwaysListening by remember { mutableStateOf(NovaListeningService.isRunning) }
                var resumeCount by remember { mutableIntStateOf(0) }

                val container = (application as NovaApplication).container
                val apiKeys = container.apiKeys
                // Bumped after a save so the masked display refreshes; the key itself is read
                // per request, so a new one takes effect on the next command without a restart.
                var apiKeyRevision by remember { mutableIntStateOf(0) }
                val hasApiKey = remember(apiKeyRevision) { apiKeys.hasKey() }

                // Re-read on resume as well as on save: the model file arrives over adb while
                // the app is in the background, and the row would otherwise still claim there
                // is no model.
                val plannerSummary = remember(apiKeyRevision, resumeCount) {
                    describePlanner(container.localModels, apiKeys)
                }

                val permissionLauncher = rememberPermissionLauncher { granted ->
                    micGranted = granted
                }

                // Accessibility and WRITE_SETTINGS are granted on a settings screen, so the
                // only reliable moment to re-read them is when the user comes back to us.
                DisposableEffect(Unit) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            micGranted = hasMicPermission()
                            accessibilityInSettings =
                                NovaAccessibilityService.isEnabled(this@MainActivity)
                            alwaysListening = NovaListeningService.isRunning
                            resumeCount++
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(injectedCommand.value) {
                    injectedCommand.value?.let { command ->
                        injectedCommand.value = null
                        viewModel.submit(command)
                    }
                }

                // Invoked by the gesture, the user is already mid-thought. Open the mic
                // straight away rather than making them find a button.
                LaunchedEffect(assistRequested.value, micGranted) {
                    if (assistRequested.value && micGranted) {
                        assistRequested.value = false
                        viewModel.startListening()
                    }
                }

                NovaScreen(
                    state = state,
                    micGranted = micGranted,
                    accessibilityEnabled = accessibilityEnabled,
                    alwaysListening = alwaysListening,
                    plannerSummary = plannerSummary,
                    hasApiKey = hasApiKey,
                    onMicTap = viewModel::toggleListening,
                    onSubmit = viewModel::submit,
                    onRequestMic = { permissionLauncher() },
                    onOpenSettingsFor = context::openSettingsFor,
                    onDismissPermissionPrompt = viewModel::dismissPermissionPrompt,
                    onAlwaysListeningChange = { enabled ->
                        alwaysListening = enabled
                        if (enabled) {
                            NovaListeningService.start(context)
                        } else {
                            NovaListeningService.stop(context)
                        }
                    },
                    onOpenAssistantSettings = context::openAssistantSettings,
                    onSaveApiKey = {
                        apiKeys.save(it)
                        apiKeyRevision++
                    },
                    onClearApiKey = {
                        apiKeys.clear()
                        apiKeyRevision++
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        injectedCommand.value = readCommandExtra(intent)
        assistRequested.value = intent.action == Intent.ACTION_ASSIST
    }

    /**
     * Lets a command be sent without speaking or typing:
     *
     * ```
     * adb shell am start -n com.nova.assistant/.MainActivity -e command "open youtube"
     * ```
     *
     * Debug builds only — this activity is exported, so in a release build the extra would be
     * an open door for any installed app to drive the assistant.
     */
    private fun readCommandExtra(intent: Intent?): String? =
        if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_COMMAND) else null

    /**
     * One line saying where planning will actually run.
     *
     * Worth showing plainly: an on-device model and an API key behave very differently — one is
     * slow and private, the other fast and billed — and a user should never have to guess which
     * one a command just used.
     */
    private fun describePlanner(models: LocalModelStore, keys: ApiKeyStore): String =
        when (val status = models.status()) {
            is ModelStatus.Ready ->
                "On-device model, ${status.sizeBytes / 1_048_576} MB — private, no network"

            is ModelStatus.TooLittleMemory ->
                "Model installed but won't fit in free memory right now"

            ModelStatus.NotInstalled ->
                if (keys.hasKey()) "OpenAI key ${keys.masked()}" else "Needs a model or an API key"
        }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Asks for the mic, and on Android 13+ the notification permission too — without the
     * latter the always-listening service runs with an invisible notification, which reads as
     * a bug.
     */
    @Composable
    private fun rememberPermissionLauncher(onResult: (Boolean) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            onResult(result[Manifest.permission.RECORD_AUDIO] == true)
        }

        return {
            val permissions = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            launcher.launch(permissions.toTypedArray())
        }
    }

    private companion object {
        const val EXTRA_COMMAND = "command"
    }
}
