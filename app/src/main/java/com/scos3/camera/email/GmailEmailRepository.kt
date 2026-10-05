package com.scos3.camera.email

import android.content.Context
import android.net.Uri
import com.scos3.camera.R
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.DataSource
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gmail SMTP implementation of [EmailRepository].
 *
 * Authentication model (verified against current Gmail):
 *  - Gmail no longer accepts a normal account password over SMTP.
 *  - With 2-Step Verification enabled, Gmail issues a 16-character "App
 *    Password" that IS accepted as the SMTP password (username = full sender
 *    address). That is exactly what this phase uses.
 *  - SMTP server smtp.gmail.com over SSL/TLS port 465 (STARTTLS 587 is also
 *    supported by Gmail; 465 is used here, matching the reference client).
 *
 * The credentials are passed in per call (never cached in this class) and are
 * never included in log output or exception messages: failures are mapped to
 * [sanitize].
 */
class GmailEmailRepository(private val context: Context) : EmailRepository {

    override suspend fun sendImage(
        imageUri: Uri,
        senderEmail: String,
        appPassword: String,
        receiverEmail: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val name = imageUri.lastPathSegment?.substringAfterLast('/') ?: "photo.jpg"
            sendMessage(
                senderEmail = senderEmail,
                appPassword = appPassword,
                receiverEmail = receiverEmail,
                subject = "Merit1st capture",
                body = "Captured photo from Merit1st.",
                attachment = { open ->
                    val inputStream = if ("file" == imageUri.scheme) {
                        imageUri.path?.let { java.io.FileInputStream(java.io.File(it)) }
                    } else {
                        context.contentResolver.openInputStream(imageUri)
                    }
                    if (inputStream != null) {
                        open(inputStream, name)
                    } else {
                        throw java.io.FileNotFoundException("Could not open input stream for $imageUri")
                    }
                },
            )
        }.mapError { sanitize(it) }
    }

    override suspend fun sendTest(
        senderEmail: String,
        appPassword: String,
        receiverEmail: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            sendMessage(
                senderEmail = senderEmail,
                appPassword = appPassword,
                receiverEmail = receiverEmail,
                subject = "Merit1st test email",
                body = "This is a test message from Merit1st. If you can read this, the Gmail SMTP configuration is working.",
                attachment = null,
            )
        }.mapError { sanitize(it) }
    }

    private fun sendMessage(
        senderEmail: String,
        appPassword: String,
        receiverEmail: String,
        subject: String,
        body: String,
        attachment: ((load: (InputStream, String) -> Unit) -> Unit)?,
    ) {
        val cleanPassword = appPassword.replace(" ", "").trim()
        val cleanSender = senderEmail.trim()
        val cleanReceiver = receiverEmail.trim()

        val props = Properties().apply {
            put("mail.transport.protocol", "smtp")
            put("mail.host", SMTP_HOST)
            put("mail.smtp.auth", "true")
            put("mail.smtp.port", "465")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.socketFactory.port", "465")
            put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            put("mail.smtp.socketFactory.fallback", "false")
            put("mail.smtp.ssl.trust", SMTP_HOST)
            put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
            put("mail.smtp.quitwait", "false")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.writetimeout", "30000")
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
                PasswordAuthentication(cleanSender, cleanPassword)
        })

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(cleanSender))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(cleanReceiver))
            setSubject(subject)
        }

        val multipart = MimeMultipart()

        val bodyPart = MimeBodyPart().apply { setText(body, "utf-8") }
        multipart.addBodyPart(bodyPart)

        attachment?.invoke { input, fileName ->
            input.use { bytes ->
                val imagePart = MimeBodyPart().apply {
                    dataHandler = DataHandler(ByteArrayDataSource(bytes.readBytes(), "image/jpeg"))
                    setFileName(fileName)
                }
                multipart.addBodyPart(imagePart)
            }
        }

        message.setContent(multipart)
        Transport.send(message)
    }

    /** Maps low-level exceptions to user-safe messages that never leak credentials. */
    private fun sanitize(t: Throwable): Throwable {
        val message = when (t) {
            is javax.mail.AuthenticationFailedException -> context.getString(R.string.email_error_auth)
            is java.net.UnknownHostException -> context.getString(R.string.email_error_network)
            is java.net.ConnectException -> context.getString(R.string.email_error_network)
            is java.net.SocketTimeoutException -> context.getString(R.string.email_error_network)
            else -> context.getString(R.string.email_error_generic)
        }
        return RuntimeException(message, t)
    }

    /** Small attachment source so images never need to be written to disk first. */
    private class ByteArrayDataSource(
        private val data: ByteArray,
        private val contentType: String,
    ) : DataSource {
        override fun getInputStream(): InputStream = data.inputStream()
        override fun getOutputStream(): OutputStream = throw UnsupportedOperationException()
        override fun getContentType(): String = contentType
        override fun getName(): String = "scos3"
    }

    private companion object {
        const val SMTP_HOST = "smtp.gmail.com"
    }
}

private fun <T> Result<T>.mapError(f: (Throwable) -> Throwable): Result<T> =
    fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(f(it)) })
