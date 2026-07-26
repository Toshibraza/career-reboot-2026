package com.nova.feature.vision

import android.graphics.Bitmap
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.nova.feature.accessibility.NovaAccessibilityService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** What OCR found, or why it found nothing. */
sealed interface OcrResult {

    data class Text(val lines: List<String>) : OcrResult

    data object NothingFound : OcrResult

    data class Unavailable(val reason: String) : OcrResult
}

/**
 * Reads text off the screen as pixels rather than as a node tree.
 *
 * The accessibility reader is exact and should be preferred wherever it works, but it only sees
 * what an app chooses to expose. A photo of a bill, a scanned document, a game or a canvas-drawn
 * view are all invisible to it. This fills that hole: capture the screen, run recognition, and
 * read back what a person would actually see.
 *
 * Nothing is written to disk. The bitmap is recycled as soon as recognition finishes — a
 * screenshot of whatever the user had open is not something to leave lying around.
 */
class ScreenTextReader {

    private val recogniser by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun read(): OcrResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return OcrResult.Unavailable("Reading the screen this way needs Android 11 or newer.")
        }

        val service = NovaAccessibilityService.connected
            ?: return OcrResult.Unavailable("Turn on Nova's accessibility service and I can read it.")

        // A SecurityException here means the service is running without the screenshot
        // capability — which happens when the config declaring it was added after the user
        // enabled the service. Turning it off and on again re-reads the capabilities.
        val bitmap = runCatching { service.captureScreen() }
            .getOrElse { failure ->
                return if (failure is SecurityException) {
                    OcrResult.Unavailable(
                        "I need screenshot permission. Turn Nova's accessibility service off and on again.",
                    )
                } else {
                    OcrResult.Unavailable("I couldn't capture the screen.")
                }
            }
            ?: return OcrResult.Unavailable("I couldn't capture the screen — it may be a secure one.")

        return try {
            recognise(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognise(bitmap: Bitmap): OcrResult =
        suspendCancellableCoroutine { continuation ->
            recogniser.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val lines = result.textBlocks
                        .flatMap { block -> block.lines }
                        .map { it.text.trim() }
                        .filter { it.isNotEmpty() }

                    if (continuation.isActive) {
                        continuation.resume(
                            if (lines.isEmpty()) OcrResult.NothingFound else OcrResult.Text(lines),
                        )
                    }
                }
                .addOnFailureListener {
                    if (continuation.isActive) {
                        continuation.resume(OcrResult.Unavailable("I couldn't read that."))
                    }
                }
        }
}
