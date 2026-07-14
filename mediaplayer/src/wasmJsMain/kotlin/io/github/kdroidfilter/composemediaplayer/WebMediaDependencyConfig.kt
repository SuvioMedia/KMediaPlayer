package io.github.kdroidfilter.composemediaplayer

/**
 * Browser dependency configuration for optional media features that cannot be imported as ES modules.
 *
 * The defaults use an exact Matroska parser version protected by subresource integrity. Applications
 * with an offline or strict-CSP deployment should self-host the same file and set both properties before
 * creating a player. Setting [matroskaSubtitlesScriptUrl] to an empty string disables embedded MKV subtitle
 * extraction without affecting normal video playback.
 */
object WebMediaDependencyConfig {
    var matroskaSubtitlesScriptUrl: String =
        "https://cdn.jsdelivr.net/npm/matroska-subtitles@3.3.2/dist/matroska-subtitles.min.js"

    var matroskaSubtitlesScriptIntegrity: String =
        "sha384-gGN9a/1oMjF5kIq0N0PFrgbT2AT1N5ZumuzEMDqib6LM5G60oHAajwuuEYfldRu7"
}
