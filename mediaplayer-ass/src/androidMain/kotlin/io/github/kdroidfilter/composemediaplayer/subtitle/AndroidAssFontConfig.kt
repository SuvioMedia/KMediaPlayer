package io.github.kdroidfilter.composemediaplayer.subtitle

import android.content.Context
import java.io.File

internal object AndroidAssFontConfig {
    private val lock = Any()

    fun ensure(context: Context): String =
        synchronized(lock) {
            val root = File(context.cacheDir, "kmedia-fontconfig").apply { mkdirs() }
            check(root.isDirectory) { "Cannot create the private libass font cache directory." }
            val cache = File(root, "cache").apply { mkdirs() }
            check(cache.isDirectory) { "Cannot create the private fontconfig cache directory." }

            val configuration = File(root, "fonts.conf")
            val content = fontConfigXml(cache.absolutePath.xmlText())
            if (!configuration.isFile || configuration.readText() != content) {
                val temporary = File(root, "fonts.conf.tmp")
                temporary.writeText(content)
                check(
                    temporary.renameTo(configuration) ||
                        runCatching {
                            configuration.writeText(content)
                            temporary.delete()
                            true
                        }.getOrDefault(false),
                ) {
                    "Cannot write the private fontconfig configuration."
                }
            }
            configuration.absolutePath
        }

    private fun fontConfigXml(cachePath: String): String =
        """
        <?xml version="1.0"?>
        <!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
        <fontconfig>
          <dir>/system/fonts</dir>
          <dir>/product/fonts</dir>
          <dir>/system_ext/fonts</dir>
          <dir>/vendor/fonts</dir>
          <cachedir>$cachePath</cachedir>
          <config><rescan><int>0</int></rescan></config>
        </fontconfig>
        """.trimIndent()
}

private fun String.xmlText(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
