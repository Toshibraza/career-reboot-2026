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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nova.assistant.ui.NovaScreen
import com.nova.assistant.ui.NovaViewModel
import com.nova.assistant.ui.theme.NovaTheme

class MainActivity : ComponentActivity() {

    /**
     * Command injected over adb, in debug builds only. See [readCommandExtra].
     */
    private val injectedCommand = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        injectedCommand.value = readCommandExtra(intent)

        setContent {
            NovaTheme {
                val context = LocalContext.current
                val viewModel: NovaViewModel = viewModel(factory = NovaViewModel.Factory)
                val state by viewModel.state.collectAsState()

                var micGranted by remember { mutableStateOf(hasMicPermission()) }
                var alwaysListening by remember { mutableStateOf(NovaListeningService.isRunning) }

                val permissionLauncher = rememberPermissionLauncher { granted ->
                    micGranted = granted
                }

                LaunchedEffect(injectedCommand.value) {
                    injectedCommand.value?.let { command ->
                        injectedCommand.value = null
                        viewModel.submit(command)
                    }
                }

                NovaScreen(
                    state = state,
                    micGranted = micGranted,
                    alwaysListening = alwaysListening,
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
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        injectedCommand.value = readCommandExtra(intent)
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
