package io.github.kdroidfilter.composemediaplayer

internal fun Map<String, String>.browserRequestHeadersJsonObjectString(): String =
    sanitizedRequestHeaders()
        .filterKeys { name -> !name.isBrowserManagedRequestHeaderName() }
        .requestHeadersJsonObjectString()

internal fun Map<String, String>.usesBrowserCredentials(): Boolean =
    sanitizedRequestHeaders()
        .keys
        .any { name -> name.equals("Cookie", ignoreCase = true) || name.equals("Cookie2", ignoreCase = true) }

private fun String.isBrowserManagedRequestHeaderName(): Boolean {
    val normalized = lowercase()
    return normalized in BrowserManagedRequestHeaderNames ||
        normalized.startsWith("proxy-") ||
        normalized.startsWith("sec-")
}

private val BrowserManagedRequestHeaderNames =
    setOf(
        "accept-charset",
        "accept-encoding",
        "access-control-request-headers",
        "access-control-request-method",
        "connection",
        "content-length",
        "cookie",
        "cookie2",
        "date",
        "dnt",
        "expect",
        "host",
        "keep-alive",
        "origin",
        "referer",
        "referrer",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "via",
    )
