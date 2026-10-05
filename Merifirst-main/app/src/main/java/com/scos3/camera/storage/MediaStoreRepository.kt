package com.scos3.camera.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Metadata for an image persisted through MediaStore. */
data class SavedImage(
    val uri: Uri,
    val displayName: String,
    val width: Int,
    val height: Int,
)

/**
 * Saves captured JPEGs using the modern MediaStore / scoped-storage API.
 *
 * Images land in `Pictures/Merit1st` on the primary external volume, which makes
 * them immediately visible in the system Gallery with no storage permission.
 * Writing your own media into MediaStore does not require any permission on
 * Android 10+ (minSdk of this app).
 *
 * `IS_PENDING` hides the file from other apps until the bytes are fully
 * written, then is cleared to make the image appear atomically.
 *
 * EXIF: the JPEG produced by CameraX already carries the correct orientation
 * tag. We read it back so the width/height and the ORIENTATION column stored
 * in MediaStore match what the viewer will actually display.
 */
class MediaStoreRepository(private val context: Context) {

    private val resolver = context.contentResolver

    fun saveImage(jpeg: ByteArray, width: Int, height: Int): SavedImage {
        val dateTaken = System.currentTimeMillis()
        val displayName = FILE_PREFIX + SimpleDateFormat(FILE_DATE_FORMAT, Locale.US).format(Date(dateTaken)) + ".jpg"

        val orientation = readExifOrientation(jpeg)
        val orientedWidth = if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        ) height else width
        val orientedHeight = if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        ) width else height

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, dateTaken)
            put(MediaStore.Images.Media.WIDTH, orientedWidth)
            put(MediaStore.Images.Media.HEIGHT, orientedHeight)
            put(MediaStore.Images.Media.ORIENTATION, orientationDegrees(orientation))
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + DIRECTORY_NAME)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Failed to create MediaStore entry")

        resolver.openOutputStream(uri)?.use { stream ->
            stream.write(jpeg)
        } ?: throw IOException("Failed to open output stream for $uri")

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        runCatching {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(uri.toString()),
                arrayOf("image/jpeg"),
                null
            )
        }

        return SavedImage(uri, displayName, orientedWidth, orientedHeight)
    }

    private fun readExifOrientation(jpeg: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun orientationDegrees(exif: Int): Int = when (exif) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    private companion object {
        const val DIRECTORY_NAME = "Merit1st"
        const val FILE_PREFIX = "Merit1st_"
        const val FILE_DATE_FORMAT = "yyyyMMdd_HHmmssSSS"
    }
}
