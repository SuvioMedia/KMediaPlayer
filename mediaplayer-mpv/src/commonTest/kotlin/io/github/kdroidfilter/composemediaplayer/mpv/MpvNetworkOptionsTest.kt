package io.github.kdroidfilter.composemediaplayer.mpv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvNetworkOptionsTest {
    @Test
    fun sanitizesAndOrdersHttpHeadersWithoutFlatteningValues() {
        assertEquals(
            listOf("Authorization: Bearer opaque,value", "User-Agent: Suvio/1.0"),
            mapOf(
                "User-Agent" to " Suvio/1.0 ",
                "Authorization" to "Bearer opaque,value",
                "Injected" to "ok\r\nBad: yes",
            ).toMpvHttpHeaderFields(),
        )
    }

    @Test
    fun rejectsCaseInsensitiveHeaderDuplicates() {
        assertFailsWith<IllegalArgumentException> {
            mapOf(
                "Authorization" to "one",
                "authorization" to "two",
            ).toMpvHttpHeaderFields()
        }
    }

    @Test
    fun recognizesOnlyDirectHttpSchemes() {
        assertTrue("http://media.test/movie".isMpvHttpSource())
        assertTrue("HTTPS://media.test/movie".isMpvHttpsSource())
        assertFalse("file:///movie.mkv".isMpvHttpSource())
        assertFalse("relative:movie.mkv".isMpvHttpSource())
    }

    @Test
    fun rejectsUnsafeOrMalformedDirectHttpAuthorities() {
        assertTrue("https://media.test/movie.mkv?token=opaque@value".isSafeDirectMpvHttpSource())
        assertTrue("http://[::1]:49152/movie.mkv".isSafeDirectMpvHttpSource())
        assertFalse("https://user@media.test/movie.mkv".isSafeDirectMpvHttpSource())
        assertFalse("https:///movie.mkv".isSafeDirectMpvHttpSource())
        assertFalse("https:media.test/movie.mkv".isSafeDirectMpvHttpSource())
        assertFalse("https://media.test:0/movie.mkv".isSafeDirectMpvHttpSource())
        assertFalse("https://media.test:99999/movie.mkv".isSafeDirectMpvHttpSource())
    }
}
