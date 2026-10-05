package com.scos3.camera.capture

import androidx.camera.core.ImageProxy

/** Extracts the complete JPEG byte array from a captured [ImageProxy]. */
fun ImageProxy.jpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}
