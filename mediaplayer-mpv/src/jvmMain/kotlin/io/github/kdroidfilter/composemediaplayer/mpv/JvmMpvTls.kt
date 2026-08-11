package io.github.kdroidfilter.composemediaplayer.mpv

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal fun resolveDesktopMpvTlsCertificateAuthorityFile(
    config: MpvRuntimeConfig,
    osName: String = System.getProperty("os.name", ""),
): Path? {
    config.tlsCertificateAuthorityFile?.let { configuredFile ->
        require(
            Files.isRegularFile(configuredFile, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(configuredFile),
        ) {
            "tlsCertificateAuthorityFile must identify an existing, non-symbolic-link regular file."
        }
        return configuredFile.toAbsolutePath().normalize()
    }
    return if (osName.lowercase(Locale.ROOT).contains("linux")) {
        JvmTrustStorePemBundle.path
    } else {
        null
    }
}

private object JvmTrustStorePemBundle {
    val path: Path by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::create)

    private fun create(): Path {
        val certificates = defaultTrustCertificates()
        require(certificates.isNotEmpty()) { "The JVM default trust store contains no CA certificates." }

        val directory = Files.createTempDirectory("composemediaplayer-mpv-ca-")
        setPosixPermissions(directory, "rwx------")
        directory.toFile().deleteOnExit()
        val file = directory.resolve("jvm-trust.pem")
        Files
            .newBufferedWriter(
                file,
                StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { writer ->
                certificates.forEach { certificate ->
                    writer.appendLine("-----BEGIN CERTIFICATE-----")
                    writer.appendLine(PEM_ENCODER.encodeToString(certificate.encoded))
                    writer.appendLine("-----END CERTIFICATE-----")
                }
            }
        setPosixPermissions(file, "rw-------")
        file.toFile().deleteOnExit()
        return file
    }

    private fun defaultTrustCertificates(): List<X509Certificate> {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val trustManager =
            factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: error("The JVM default trust manager is not X.509-capable.")
        return trustManager.acceptedIssuers
            .asSequence()
            .associateBy(::certificateDigest)
            .toSortedMap()
            .values
            .toList()
    }

    private fun certificateDigest(certificate: X509Certificate): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun setPosixPermissions(
        path: Path,
        permissions: String,
    ) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                PosixFilePermissions.fromString(permissions),
            )
        }
    }

    private val PEM_ENCODER = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
}
