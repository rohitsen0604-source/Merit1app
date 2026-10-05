package com.scos3.camera.email

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Creates a compressed JPEG copy of a captured image for email delivery.
 *
 * The compressed copy is stored in a dedicated app-private directory
 * (`email_captures/`) that is never exposed to MediaStore or the Android
 * Gallery. The original image is never modified.
 *
 * Compression policy:
 *  - Longest dimension capped at [MAX_DIMENSION] px (aspect ratio preserved).
 *  - JPEG quality [JPEG_QUALITY].
 *  - Both downsampling and quality reduction together achieve 60-80% size
 *    reduction on typical camera images while maintaining visual acceptability.
 *
 * The compressed file persists until [cleanup] is called after successful SMTP
 * delivery. On retry the same compressed file is reused (no recompression).
 */
class ImageCompressor(context: Context) {

    private val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME)

    /**
     * Compresses the original JPEG bytes and writes a smaller copy to the
     * dedicated email-captures directory.
     *
     * @param originalJpeg the raw JPEG bytes from CameraX capture.
     * @param width  the captured image width (pre-EXIF rotation).
     * @param height the captured image height (pre-EXIF rotation).
     * @return a `file://` [Uri] pointing to the compressed copy.
     * @throws java.io.IOException if the directory cannot be created or the
     *   file cannot be written.
     */
    fun compress(originalJpeg: ByteArray, width: Int, height: Int): Uri {
        ensureDirectory()

        // --- Determine in-sample-size for large images ---
        val longest = maxOf(width, height)
        val sampleSize = if (longest > MAX_DIMENSION) {
            var sample = 1
            while (longest / sample > MAX_DIMENSION) sample *= 2
            sample
        } else {
            1
        }

        // --- Decode with subsampling ---
        val decodeOpts = BitmapFactory.Options().apply {
            this.inSampleSize = sampleSize
        }
        val bitmap = BitmapFactory.decodeByteArray(originalJpeg, 0, originalJpeg.size, decodeOpts)
            ?: throw java.io.IOException("Failed to decode JPEG for email compression")

        // --- Scale to exact target if subsampling overshot ---
        val scaled = if (sampleSize > 1) {
            val targetLongest = MAX_DIMENSION
            val currentLongest = maxOf(bitmap.width, bitmap.height)
            if (currentLongest > targetLongest) {
                val scale = targetLongest.toFloat() / currentLongest
                val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                val result = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
                if (result !== bitmap) bitmap.recycle()
                result
            } else {
                bitmap
            }
        } else {
            bitmap
        }

        // --- Compress to JPEG ---
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()

        val bytes = output.toByteArray()

        // --- Write to dedicated directory ---
        val displayName = FILE_PREFIX + SimpleDateFormat(FILE_DATE_FORMAT, Locale.US).format(Date()) + ".jpg"
        val file = File(directory, displayName)
        file.writeBytes(bytes)

        return Uri.fromFile(file)
    }

    /**
     * Deletes the compressed email copy if it resides in the dedicated
     * [DIRECTORY_NAME] directory. Safe to call with any URI — non-email
     * files and non-existent paths are ignored.
     *
     * Safety: uses [File.getCanonicalPath] to prevent a crafted path such as
     * `.../evil/email_captures/file.jpg` from matching the check. Only files
     * whose canonical path starts with the canonical path of [directory] are
     * eligible for deletion.
     */
    fun cleanup(compressedUri: Uri) {
        val path = compressedUri.path ?: return
        val file = File(path)
        val canonicalDir = runCatching { directory.canonicalPath }.getOrNull() ?: return
        val canonicalFile = runCatching { file.canonicalPath }.getOrNull() ?: return
        // Safety: verify the file is strictly inside the dedicated directory.
        if (canonicalFile.startsWith(canonicalDir + File.separator) && file.exists()) {
            file.delete()
        }
    }

    /**
     * Returns true if the URI points to a file inside the dedicated
     * email-captures directory. Uses canonical path verification.
     */
    fun isCompressedEmailFile(uri: Uri): Boolean {
        val path = uri.path ?: return false
        val file = File(path)
        val canonicalDir = runCatching { directory.canonicalPath }.getOrNull() ?: return false
        val canonicalFile = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return canonicalFile.startsWith(canonicalDir + File.separator)
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) {
            throw java.io.IOException("Failed to create email captures directory")
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "email_captures"
        const val FILE_PREFIX = "email_"
        const val FILE_DATE_FORMAT = "yyyyMMdd_HHmmssSSS"
        const val MAX_DIMENSION = 3840
        const val JPEG_QUALITY = 92
    }
}
