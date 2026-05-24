package io.github.kdroidfilter.composemediaplayer

internal const val EXTERNAL_FFMPEG_AUDIO_TRACK_ID_PREFIX = "external-ffmpeg:audio:"
internal const val EXTERNAL_FFMPEG_SUBTITLE_TRACK_ID_PREFIX = "external-ffmpeg:subtitle:"
internal const val EXTERNAL_VLC_AUDIO_TRACK_ID_PREFIX = "external-vlc:audio:"
internal const val EXTERNAL_VLC_SUBTITLE_TRACK_ID_PREFIX = "external-vlc:subtitle:"
internal const val LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX = "libvlc-canvas:audio:"
internal const val LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX = "libvlc-canvas:subtitle:"

@Deprecated("Use LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX.")
internal const val MAC_LIBVLC_AUDIO_TRACK_ID_PREFIX = LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX

@Deprecated("Use LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX.")
internal const val MAC_LIBVLC_SUBTITLE_TRACK_ID_PREFIX = LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX
