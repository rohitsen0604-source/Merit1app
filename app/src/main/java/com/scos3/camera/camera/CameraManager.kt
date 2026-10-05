package com.scos3.camera.camera

import android.content.Context
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await

/**
 * Owns the application-wide [ProcessCameraProvider] (a process singleton).
 *
 * CameraX initializes the provider asynchronously, so obtaining it is a suspend
 * call. Every higher layer (CameraController, CameraCapabilities) obtains the
 * provider through this class instead of calling ProcessCameraProvider directly.
 */
class CameraManager(context: Context) {

    private val appContext = context.applicationContext

    suspend fun getProvider(): ProcessCameraProvider =
        ProcessCameraProvider.getInstance(appContext).await()
}
