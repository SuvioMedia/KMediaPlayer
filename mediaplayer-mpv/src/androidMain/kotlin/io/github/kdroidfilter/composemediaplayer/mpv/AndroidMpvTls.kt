package io.github.kdroidfilter.composemediaplayer.mpv

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64

internal object AndroidMpvTls {
    private val lock = Any()

    @Volatile
    private var cachedSystemCaFile: File? = null

    fun certificateAuthorityFile(
        context: Context,
        configuredFile: File?,
    ): File = configuredFile?.validatedRegularFile() ?: systemCertificateAuthorities(context)

    private fun systemCertificateAuthorities(context: Context): File {
        cachedSystemCaFile?.takeIf(File::isFile)?.let { return it }
        return synchronized(lock) {
            cachedSystemCaFile?.takeIf(File::isFile)?.let { return@synchronized it }
            val root = context.noBackupFilesDir.canonicalFile
            val directory = File(root, "composemediaplayer-mpv/tls").canonicalFile
            require(directory.path.startsWith(root.path + File.separator)) {
                "The MPV TLS directory escaped app-private storage."
            }
            if (!directory.isDirectory) {
                require(directory.mkdirs()) { "Could not create the app-private MPV TLS directory." }
            }
            require(!Files.isSymbolicLink(directory.toPath())) {
                "The app-private MPV TLS directory must not be a symbolic link."
            }

            val target = File(directory, "android-system-ca.pem")
            val temporary = File.createTempFile("android-system-ca-", ".pem", directory)
            try {
                writeAndroidCertificateAuthorities(temporary)
                temporary.setReadable(false, false)
                temporary.setWritable(false, false)
                check(temporary.setReadable(true, true)) { "Could not protect the MPV CA file." }
                check(temporary.setWritable(true, true)) { "Could not protect the MPV CA file." }
                try {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                target.validatedRegularFile().also { cachedSystemCaFile = it }
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    private fun writeAndroidCertificateAuthorities(target: File) {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val aliases = store.aliases().toList().sorted()
        val encoder = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
        val encodedCertificates = linkedSetOf<String>()
        aliases.forEach { alias ->
            val certificate = store.getCertificate(alias) as? X509Certificate ?: return@forEach
            encodedCertificates += encoder.encodeToString(certificate.encoded)
        }
        require(encodedCertificates.isNotEmpty()) { "Android exposed no trusted certificate authorities." }
        target.outputStream().bufferedWriter(StandardCharsets.US_ASCII).use { writer ->
            encodedCertificates.forEach { certificate ->
                writer.appendLine("-----BEGIN CERTIFICATE-----")
                writer.appendLine(certificate)
                writer.appendLine("-----END CERTIFICATE-----")
            }
        }
    }

    private fun File.validatedRegularFile(): File {
        val canonical = canonicalFile
        require(isAbsolute && canonical.isFile && !Files.isSymbolicLink(toPath())) {
            "The MPV CA file must be an absolute regular file."
        }
        return canonical
    }
}
