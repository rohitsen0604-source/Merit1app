package com.scos3.camera.capture

import android.net.Uri

/** Outcome of a single image capture. Delivered on the main thread. */
sealed class CaptureResult {
    data class Success(
        val uri: Uri,
        val width: Int,
        val height: Int,
        val displayName: String,
    ) : CaptureResult()

    data class Failure(val error: Throwable) : CaptureResult()
}
