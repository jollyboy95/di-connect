package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.cert.CertificateFactory
import java.util.zip.ZipInputStream

/**
 * Installs the local CarPlay identity (identity.pk8 + certificate.p7b) chosen by the driver,
 * either as the two files or copied out of a compatible DiPlay APK. Source builds ship without
 * these resources, so this is how such a build gets a working local identity.
 *
 * Nothing is written to the live directory until [LocalMfiAuthenticationClient.load] accepts the
 * pair, so a bad import never replaces a working identity.
 */
internal object AuthenticationImport {
    const val IDENTITY = "identity.pk8"
    const val CERTIFICATE = "certificate.p7b"
    private const val APK_PREFIX = "assets/${LocalMfiAuthenticationClient.DIRECTORY}/"
    private const val MAX_FILE_BYTES = 16 * 1024
    private const val MAX_APK_ENTRIES = 100_000

    class ImportException(message: String) : Exception(message)

    class Resources(val identity: ByteArray, val certificate: ByteArray) {
        fun clear() { identity.fill(0) }
    }

    fun directory(context: Context) = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)

    /** Whether a complete identity is installed in the app's private storage. */
    fun installed(context: Context): Boolean = directory(context).let { dir ->
        listOf(IDENTITY, CERTIFICATE).all { File(dir, it).let { file -> file.isFile && file.length() > 0 } }
    }

    /**
     * Sorts two picked files into key and certificate by content, so file names do not matter
     * (some file managers rename downloads). The certificate is the one that parses as X.509.
     */
    fun fromFiles(first: ByteArray, second: ByteArray): Resources = when {
        isCertificate(first) && !isCertificate(second) -> Resources(identity = second, certificate = first)
        isCertificate(second) && !isCertificate(first) -> Resources(identity = first, certificate = second)
        else -> throw ImportException("Expected one identity key and one certificate")
    }

    /** Copies the identity out of an APK's assets/offline-mfi folder. */
    fun fromApk(apk: InputStream): Resources {
        var identity: ByteArray? = null
        var certificate: ByteArray? = null
        ZipInputStream(apk.buffered()).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > MAX_APK_ENTRIES) throw ImportException("APK has too many entries")
                if (entry.isDirectory) continue
                when (entry.name) {
                    APK_PREFIX + IDENTITY -> identity = readBounded(zip)
                    APK_PREFIX + CERTIFICATE -> certificate = readBounded(zip)
                }
                if (identity != null && certificate != null) break
            }
        }
        if (identity == null || certificate == null) {
            identity?.fill(0)
            throw ImportException("This APK has no CarPlay authentication resources")
        }
        return Resources(identity!!, certificate!!)
    }

    /** Validates the pair in a staging folder, then replaces the installed identity with it. */
    fun install(context: Context, resources: Resources) {
        val base = context.noBackupFilesDir
        val staging = File(base, "offline-mfi-import")
        val target = directory(context)
        val previous = File(base, "offline-mfi-previous")
        staging.deleteRecursively()
        previous.deleteRecursively()
        try {
            if (!staging.mkdirs()) throw ImportException("Could not prepare local authentication")
            lockDown(staging, directory = true)
            writePrivate(File(staging, IDENTITY), resources.identity)
            writePrivate(File(staging, CERTIFICATE), resources.certificate)
            try {
                LocalMfiAuthenticationClient.load(staging)
            } catch (error: Exception) {
                throw ImportException(error.message ?: "The identity and certificate do not match")
            }
            if (target.exists() && !target.renameTo(previous)) throw ImportException("Could not replace the old identity")
            if (!staging.renameTo(target)) {
                previous.renameTo(target)
                throw ImportException("Could not install local authentication")
            }
            previous.deleteRecursively()
        } finally {
            resources.clear()
            staging.deleteRecursively()
        }
    }

    private fun isCertificate(bytes: ByteArray): Boolean = runCatching {
        CertificateFactory.getInstance("X.509").generateCertificates(bytes.inputStream()).isNotEmpty()
    }.getOrDefault(false)

    fun readBounded(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_FILE_BYTES) throw ImportException("File is too large to be an identity")
            output.write(buffer, 0, count)
        }
        return output.toByteArray().also { if (it.isEmpty()) throw ImportException("File is empty") }
    }

    private fun writePrivate(file: File, bytes: ByteArray) {
        file.outputStream().use { it.write(bytes) }
        lockDown(file, directory = false)
    }

    private fun lockDown(file: File, directory: Boolean) {
        file.setReadable(false, false); file.setReadable(true, true)
        file.setWritable(false, false); file.setWritable(true, true)
        if (directory) { file.setExecutable(false, false); file.setExecutable(true, true) }
    }
}
