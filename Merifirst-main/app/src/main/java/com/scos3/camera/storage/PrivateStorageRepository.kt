package com.scos3.camera.storage

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Durable app-private capture store used when "Save Photos to Gallery" is OFF.
 *
 * Originals are written byte-for-byte (no recompression) under
 * `filesDir/private_captures/`, a stable app-internal location that:
 *  - is never exposed through MediaStore / the Android Gallery,
 *  - survives app restarts, foreground-service restarts and network retries,
 *  - is NOT a cache directory, so Android will not evict queued images.
 *
 * The returned [SavedImage] carries a `file://` URI. [android.content.ContentResolver]
 * and the existing email pipeline can open `file://` URIs directly, so a queued
 * private image stays readable for delivery after any restart.
 */
class PrivateStorageRepository(context: Context) {

    private val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME)

    fun saveImage(jpeg: ByteArray, width: Int, height: Int): SavedImage {
        val displayName = FILE_PREFIX + SimpleDateFormat(FILE_DATE_FORMAT, Locale.US).format(Date()) + ".jpg"
        val file = File(directory, displayName)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Failed to create private capture directory")
        }
        file.writeBytes(jpeg)
        return SavedImage(Uri.fromFile(file), displayName, width, height)
    }

    private companion object {
        const val DIRECTORY_NAME = "private_captures"
        const val FILE_PREFIX = "Merit1st_"
        const val FILE_DATE_FORMAT = "yyyyMMdd_HHmmssSSS"
    }
}