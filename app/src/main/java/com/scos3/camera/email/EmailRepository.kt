package com.scos3.camera.email

import android.net.Uri

/**
 * Modular email delivery abstraction. Camera capture NEVER talks to this
 * directly: it hands images to the [EmailManager] queue, which calls this
 * interface when it decides it is time to upload.
 *
 * Implementations must never place credentials in log output or in exception
 * messages (see [EmailResult.sanitizedMessage]).
 */
interface EmailRepository {

    suspend fun sendImage(
        imageUri: Uri,
        senderEmail: String,
        appPassword: String,
        receiverEmail: String,
    ): Result<Unit>

    /** Small test message used by the Settings "Test email" button. */
    suspend fun sendTest(
        senderEmail: String,
        appPassword: String,
        receiverEmail: String,
    ): Result<Unit>
}
