# Third-party notices for the optional KMediaMpv desktop adapter

The JVM adapter maps declarations and constants from mpv's public `client.h`
and `render.h` headers. mpv offers those headers under the ISC license so that
independent clients can use them.

The complete ISC notice is packaged at
`META-INF/LICENSES/mpv-client-api-ISC.txt`.

Upstream declarations:

- https://github.com/mpv-player/mpv/blob/v0.41.0/include/mpv/client.h
- https://github.com/mpv-player/mpv/blob/v0.41.0/include/mpv/render.h

The `composemediaplayer-mpv` adapter does not embed or download libmpv, FFmpeg,
libass, or any other KMediaMpv native payload. Its platform variants declare a
matching KMediaMpv runtime dependency. Those separately published artifacts
retain their own license, notices, corresponding source, replacement mechanism,
and release evidence.
