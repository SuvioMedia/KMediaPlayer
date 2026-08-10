package io.github.kdroidfilter.composemediaplayer.mpv

import io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi
import io.github.kdroidfilter.composemediaplayer.sanitizedRequestHeaders

@OptIn(ExperimentalComposeMediaPlayerBackendApi::class)
internal fun Map<String, String>.sanitizedMpvHttpHeaders(): Map<String, String> {
    val entries =
        sanitizedRequestHeaders()
            .entries
            .sortedWith(compareBy({ it.key.lowercase() }, { it.key }))
    require(entries.map { it.key.lowercase() }.distinct().size == entries.size) {
        "HTTP header names must be unique ignoring case."
    }
    return entries.associate { it.key to it.value }
}

internal fun Map<String, String>.toMpvHttpHeaderFields(): List<String> =
    sanitizedMpvHttpHeaders().map { (name, value) -> "$name: $value" }

internal fun String.isMpvHttpSource(): Boolean =
    mpvSourceScheme()?.let { scheme -> scheme == "http" || scheme == "https" } == true

internal fun String.isMpvHttpsSource(): Boolean = mpvSourceScheme() == "https"

internal fun String.isSafeDirectMpvHttpSource(): Boolean {
    if (!isMpvHttpSource()) return false
    val separator = indexOf(':')
    val remainder = substring(separator + 1)
    if (!remainder.startsWith("//")) return false
    val authorityAndPath = remainder.substring(2)
    val authorityEnd =
        authorityAndPath.indexOfFirst { character -> character == '/' || character == '?' || character == '#' }
    val authority =
        if (authorityEnd < 0) {
            authorityAndPath
        } else {
            authorityAndPath.substring(0, authorityEnd)
        }
    if (authority.isBlank() || '@' in authority || '\\' in authority || authority.any(Char::isWhitespace)) {
        return false
    }

    val port =
        if (authority.startsWith('[')) {
            val closingBracket = authority.indexOf(']')
            if (closingBracket <= 1) return false
            val suffix = authority.substring(closingBracket + 1)
            if (suffix.isEmpty()) null else suffix.takeIf { it.startsWith(':') }?.substring(1) ?: return false
        } else {
            if (authority.count { it == ':' } > 1) return false
            authority.substringAfterLast(':', missingDelimiterValue = "").takeIf { ':' in authority }
        }
    val host =
        when {
            authority.startsWith('[') -> authority.substring(1, authority.indexOf(']'))
            ':' in authority -> authority.substringBeforeLast(':')
            else -> authority
        }
    if (host.isBlank() || host.any { it.isWhitespace() || it.isISOControl() }) return false
    return port == null ||
        (
            port.isNotEmpty() &&
                port.all(Char::isDigit) &&
                port.toIntOrNull()?.let { value -> value in 1..65_535 } == true
        )
}

internal fun String.mpvSourceScheme(): String? {
    val separator = indexOf(':')
    if (separator <= 0 || !this[0].isAsciiLetter()) return null
    for (index in 1 until separator) {
        val character = this[index]
        if (!character.isAsciiLetter() && character !in '0'..'9' && character !in "+-.") {
            return null
        }
    }
    return substring(0, separator).lowercase()
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
