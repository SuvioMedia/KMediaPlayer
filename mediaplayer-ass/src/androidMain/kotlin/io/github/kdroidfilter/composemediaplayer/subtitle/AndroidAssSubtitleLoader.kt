package io.github.kdroidfilter.composemediaplayer.subtitle

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

internal suspend fun loadAndroidAssSubtitleBytes(
    context: Context,
    source: String,
): ByteArray =
    withContext(Dispatchers.IO) {
        val bytes =
            when {
                source.startsWith("http://", ignoreCase = true) ||
                    source.startsWith("https://", ignoreCase = true) -> loadRemoteAss(source)

                source.startsWith(ANDROID_ASSET_PREFIX) -> {
                    context.assets.open(source.removePrefix(ANDROID_ASSET_PREFIX)).use { it.readAssBytes() }
                }

                source.startsWith("content://", ignoreCase = true) -> {
                    context.contentResolver.openInputStream(Uri.parse(source))?.use { it.readAssBytes() }
                        ?: error("Cannot open external ASS/SSA content URI.")
                }

                else -> {
                    val uri = if (source.startsWith("file://")) Uri.parse(source) else Uri.fromFile(File(source))
                    context.contentResolver.openInputStream(uri)?.use { it.readAssBytes() }
                        ?: File(uri.path ?: source).inputStream().use { it.readAssBytes() }
                }
            }
        bytes.normalizedAssEncoding()
    }

private suspend fun loadRemoteAss(source: String): ByteArray {
    val connection = URL(source).openConnection() as HttpURLConnection
    connection.connectTimeout = NETWORK_TIMEOUT_MS
    connection.readTimeout = NETWORK_TIMEOUT_MS
    connection.instanceFollowRedirects = true
    val cancellationRegistration =
        currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause is CancellationException) connection.disconnect()
        }
    return try {
        connection.connect()
        require(connection.contentLengthLong <= MAX_EXTERNAL_ASS_BYTES || connection.contentLengthLong < 0L) {
            "The external ASS/SSA script exceeds 16 MiB."
        }
        currentCoroutineContext().ensureActive()
        connection.inputStream.use { it.readAssBytes() }
    } finally {
        cancellationRegistration.dispose()
        connection.disconnect()
    }
}

private suspend fun InputStream.readAssBytes(): ByteArray {
    val output = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var total = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = read(buffer)
        if (read < 0) break
        if (read > 0) {
            total += read
            require(total <= MAX_EXTERNAL_ASS_BYTES) { "The external ASS/SSA script exceeds 16 MiB." }
            output.write(buffer, 0, read)
        }
    }
    return output.toByteArray()
}

private fun ByteArray.normalizedAssEncoding(): ByteArray =
    when {
        startsWith(UTF8_BOM) -> copyOfRange(UTF8_BOM.size, size)
        startsWith(UTF16_LE_BOM) ->
            String(this, UTF16_LE_BOM.size, size - UTF16_LE_BOM.size, Charsets.UTF_16LE).encodeToByteArray()
        startsWith(UTF16_BE_BOM) ->
            String(this, UTF16_BE_BOM.size, size - UTF16_BE_BOM.size, Charsets.UTF_16BE).encodeToByteArray()
        isValidUtf8() -> this
        else -> String(this, Charset.forName(WINDOWS_1252)).encodeToByteArray()
    }

private fun ByteArray.isValidUtf8(): Boolean =
    runCatching {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(this))
    }.isSuccess

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

private const val ANDROID_ASSET_PREFIX = "file:///android_asset/"
private const val NETWORK_TIMEOUT_MS = 10_000
private const val INITIAL_BUFFER_BYTES = 32 * 1024
private const val READ_BUFFER_BYTES = 16 * 1024
private const val WINDOWS_1252 = "windows-1252"
private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
