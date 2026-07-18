@file:Suppress("MagicNumber")

package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder

/** Detects containers that the platform players do not open directly. */
internal object JvmExternalFallbackContainerSupport {
    private val unsupportedExtensions = setOf("mkv", "mk3d", "mka", "mks", "webm")
    private val unsupportedContentTypes =
        setOf(
            "video/x-matroska",
            "audio/x-matroska",
            "application/x-matroska",
            "video/webm",
            "audio/webm",
        )

    suspend fun needsContainerFallback(
        uri: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): Boolean =
        withContext(Dispatchers.IO) {
            val headers = requestHeaders.sanitizedRequestHeaders()
            val localFile = localFile(uri)
            if (localFile != null) {
                return@withContext hasUnsupportedExtension(localFile.name) || hasMatroskaSignature(localFile)
            }
            if (hasUnsupportedExtension(runCatching { URI(uri).path }.getOrNull())) {
                return@withContext true
            }
            if (!uri.isHttpUri()) return@withContext false

            val remoteHeaders = readRemoteHeaders(uri, headers)
            val contentType =
                remoteHeaders
                    ?.contentType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
            if (contentType in unsupportedContentTypes ||
                hasUnsupportedExtension(remoteHeaders?.contentDispositionFilename)
            ) {
                return@withContext true
            }
            readRemotePrefix(uri, headers)?.let(::hasMatroskaSignature) ?: false
        }

    internal fun hasMatroskaSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] != 0x1A.toByte()) return false
        if (bytes[1] != 0x45.toByte()) return false
        if (bytes[2] != 0xDF.toByte()) return false
        if (bytes[3] != 0xA3.toByte()) return false
        val header = String(bytes, Charsets.ISO_8859_1).lowercase()
        return "matroska" in header || "webm" in header
    }

    private fun localFile(uri: String): File? {
        if (WINDOWS_DRIVE_PATH.matches(uri)) return File(uri)
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return File(uri)
        return when (parsed.scheme?.lowercase()) {
            null, "" -> File(uri)
            "file" -> runCatching { File(parsed) }.getOrNull()
            else -> null
        }
    }

    private fun hasUnsupportedExtension(value: String?): Boolean {
        val path = value?.substringBefore('?')?.substringBefore('#') ?: return false
        return path.substringAfterLast('.', missingDelimiterValue = "").lowercase() in unsupportedExtensions
    }

    private fun hasMatroskaSignature(file: File): Boolean {
        if (!file.isFile) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(64)
                val count = input.read(header)
                count > 0 && hasMatroskaSignature(header.copyOf(count))
            }
        }.getOrDefault(false)
    }

    private fun readRemoteHeaders(
        uri: String,
        requestHeaders: Map<String, String>,
    ): RemoteHeaders? =
        runCatching {
            uri.openHttpConnection(requestHeaders, method = "HEAD").run {
                try {
                    RemoteHeaders(
                        contentType = contentType,
                        contentDispositionFilename = contentDispositionFilename(getHeaderField("Content-Disposition")),
                    )
                } finally {
                    disconnect()
                }
            }
        }.getOrNull()

    private fun readRemotePrefix(
        uri: String,
        requestHeaders: Map<String, String>,
    ): ByteArray? =
        runCatching {
            uri.openHttpConnection(requestHeaders, method = "GET").run {
                setRequestProperty("Range", "bytes=0-63")
                try {
                    inputStream.use { input ->
                        val header = ByteArray(64)
                        val count = input.read(header)
                        if (count > 0) header.copyOf(count) else null
                    }
                } finally {
                    disconnect()
                }
            }
        }.getOrNull()

    private fun String.openHttpConnection(
        requestHeaders: Map<String, String>,
        method: String,
    ): HttpURLConnection =
        (URI.create(this).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = method
            connectTimeout = REMOTE_PROBE_TIMEOUT_MS
            readTimeout = REMOTE_PROBE_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            requestHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
        }

    private fun contentDispositionFilename(value: String?): String? {
        val item =
            value
                ?.split(';')
                ?.map(String::trim)
                ?.firstOrNull {
                    it.startsWith("filename*=", ignoreCase = true) ||
                        it.startsWith("filename=", ignoreCase = true)
                } ?: return null
        return URLDecoder.decode(
            item
                .substringAfter('=')
                .substringAfter("''")
                .trim()
                .trim('"'),
            Charsets.UTF_8,
        )
    }

    private data class RemoteHeaders(
        val contentType: String?,
        val contentDispositionFilename: String?,
    )

    private const val REMOTE_PROBE_TIMEOUT_MS = 3_500
    private const val USER_AGENT = "ComposeMediaPlayer/2.0"
    private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
}

internal data class HlsFallbackSource(
    val playlistUrl: String,
    val durationSeconds: Double?,
    val playbackOffsetSeconds: Double = 0.0,
    val audioTracks: List<AudioTrack> = emptyList(),
    val selectedAudioStreamIndex: Int? = null,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedSubtitleStreamIndex: Int? = null,
    val inputColorInfo: VideoColorInfo = VideoColorInfo(),
    val outputColorInfo: VideoColorInfo = VideoColorInfo(),
    val toneMappedHdrToSdr: Boolean = false,
    val hdrCmafPassthrough: Boolean = false,
    val videoCopiedWithoutReencoding: Boolean = false,
    val usesMediaBridge: Boolean = false,
)

private fun String.isHttpUri(): Boolean =
    runCatching { URI(this).scheme?.lowercase() }
        .getOrNull() in setOf("http", "https")
