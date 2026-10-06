package com.scos3.camera.email

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Creates an upright JPEG copy of a captured image for email delivery at full resolution.
 *
 * Fixes the 90-degree sideways rotation bug on email attachments:
 *  - Reads EXIF orientation from the captured JPEG bytes.
 *  - Physically rotates the bitmap matrix if needed so that pixels are 0° upright.
 *  - Encodes the upright image at high quality (95) preserving the original captured resolution.
 *  - Ensures the EXIF tag on the output file is ORIENTATION_NORMAL so all email clients
 *    (Gmail, Outlook, Webmail, etc.) display it perfectly upright without any 90° curve/tilt.
 *
 * Stored in app-private `email_captures/` and cleaned up after successful delivery.
 */
class ImageCompressor(context: Context) {

    private val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME)

    fun compress(originalJpeg: ByteArray, width: Int, height: Int): Uri {
        ensureDirectory()

        val displayName = FILE_PREFIX + SimpleDateFormat(FILE_DATE_FORMAT, Locale.US).format(Date()) + ".jpg"
        val file = File(directory, displayName)

        // Read EXIF orientation from the raw captured JPEG
        val exif = runCatching {
            ExifInterface(ByteArrayInputStream(originalJpeg))
        }.getOrNull()

        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        ) ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> { /* ORIENTATION_NORMAL or ORIENTATION_UNDEFINED */ }
        }

        if (matrix.isIdentity) {
            // Already upright: write directly without re-encoding to preserve 100% original quality
            file.writeBytes(originalJpeg)
            runCatching {
                val outExif = ExifInterface(file.absolutePath)
                outExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                outExif.saveAttributes()
            }
            return Uri.fromFile(file)
        }

        // If rotated (e.g. 90° CW from sensor), decode full resolution and physically rotate pixels to upright
        var bitmap: Bitmap? = null
        try {
            bitmap = BitmapFactory.decodeByteArray(originalJpeg, 0, originalJpeg.size)
        } catch (_: OutOfMemoryError) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            bitmap = BitmapFactory.decodeByteArray(originalJpeg, 0, originalJpeg.size, opts)
        }

        if (bitmap == null) {
            // Fallback: write original bytes directly if decode fails
            file.writeBytes(originalJpeg)
            return Uri.fromFile(file)
        }

        val rotatedBitmap = try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: OutOfMemoryError) {
            bitmap
        }

        val output = ByteArrayOutputStream()
        // High quality (95%) preserves crisp resolution
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        val bytes = output.toByteArray()
        file.writeBytes(bytes)

        // Set EXIF ORIENTATION_NORMAL on the output file
        runCatching {
            val outExif = ExifInterface(file.absolutePath)
            outExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            outExif.saveAttributes()
        }

        if (rotatedBitmap !== bitmap) {
            rotatedBitmap.recycle()
        }
        bitmap.recycle()

        return Uri.fromFile(file)
    }

    /**
     * Deletes the compressed email copy if it resides in the dedicated
     * [DIRECTORY_NAME] directory. Safe to call with any URI — non-email
     * files and non-existent paths are ignored.
     */
    fun cleanup(compressedUri: Uri) {
        val path = compressedUri.path ?: return
        val file = File(path)
        val canonicalDir = runCatching { directory.canonicalPath }.getOrNull() ?: return
        val canonicalFile = runCatching { file.canonicalPath }.getOrNull() ?: return
        if (canonicalFile.startsWith(canonicalDir + File.separator) && file.exists()) {
            file.delete()
        }
    }

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
        const val JPEG_QUALITY = 95
    }
}
