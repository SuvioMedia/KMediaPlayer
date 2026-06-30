package io.github.kdroidfilter.composemediaplayer.subtitle

import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.stringWithContentsOfURL

private val iosSubtitleLogger = TaggedLogger("IosSubtitleLoader")

/**
 * iOS implementation of the loadSubtitleContent function.
 * Loads subtitle content from a local file or a remote URL.
 *
 * @param src The source URI of the subtitle file
 * @return The content of the subtitle file as a string
 */
@OptIn(ExperimentalForeignApi::class)
actual suspend fun loadSubtitleContent(src: String): String =
    withContext(Dispatchers.Default) {
        try {
            when {
                // Handle HTTP/HTTPS URLs
                src.startsWith("http://") || src.startsWith("https://") -> {
                    val nsUrl = NSURL(string = src)
                    try {
                        NSString.stringWithContentsOfURL(nsUrl, encoding = NSUTF8StringEncoding, error = null) ?: ""
                    } catch (e: Exception) {
                        iosSubtitleLogger.e { "Error loading URL: ${e.message}" }
                        ""
                    }
                }

                // Handle file:// URIs
                src.startsWith("file://") -> {
                    val nsUrl = NSURL(string = src)
                    try {
                        NSString.stringWithContentsOfURL(nsUrl, encoding = NSUTF8StringEncoding, error = null) ?: ""
                    } catch (e: Exception) {
                        iosSubtitleLogger.e { "Error loading file URL: ${e.message}" }
                        ""
                    }
                }

                // Handle local file paths
                else -> {
                    try {
                        NSString.stringWithContentsOfFile(src, encoding = NSUTF8StringEncoding, error = null) ?: ""
                    } catch (e: Exception) {
                        // Try as file URL
                        try {
                            val fileUrl = NSURL.fileURLWithPath(src)
                            NSString.stringWithContentsOfURL(fileUrl, encoding = NSUTF8StringEncoding, error = null)
                                ?: ""
                        } catch (e2: Exception) {
                            iosSubtitleLogger.e { "Error loading file path: ${e2.message}" }
                            ""
                        }
                    }
                }
            }
        } catch (e: Exception) {
            iosSubtitleLogger.e { "Error loading subtitle content: ${e.message}" }
            ""
        }
    }
