package io.github.kdroidfilter.composemediaplayer

/**
 * Configuration for ASS/SSA subtitle rendering.
 *
 * Browser targets use JASSUB, whose npm package provides the default
 * worker/WASM/font assets. The URL properties are optional overrides for
 * applications that need to self-host those assets. Other targets accept this
 * configuration so the same extension registration can be shared, but ignore
 * its browser-specific values.
 */
class AssSubtitleRendererConfig(
    val enabled: Boolean = true,
    val workerUrl: String? = null,
    val wasmUrl: String? = null,
    val modernWasmUrl: String? = null,
    val fallbackFontUrl: String? = null,
    val fallbackFontFamily: String = "liberation sans",
    preloadFontUrls: List<String> = emptyList(),
    availableFontUrls: Map<String, String> = emptyMap(),
    val fontQueryMode: AssFontQueryMode = AssFontQueryMode.DISABLED,
    val debug: Boolean = false,
) {
    private val preloadFontUrlValues: List<String> = preloadFontUrls.toList()
    private val availableFontUrlValues: Map<String, String> = availableFontUrls.toMap()

    val preloadFontUrls: List<String>
        get() = preloadFontUrlValues.toList()

    val availableFontUrls: Map<String, String>
        get() = availableFontUrlValues.toMap()

    init {
        require(workerUrl == null || workerUrl.isNotBlank()) {
            "The ASS subtitle worker URL must not be blank."
        }
        require(wasmUrl == null || wasmUrl.isNotBlank()) {
            "The ASS subtitle WASM URL must not be blank."
        }
        require(modernWasmUrl == null || modernWasmUrl.isNotBlank()) {
            "The ASS subtitle modern WASM URL must not be blank."
        }
        require(fallbackFontUrl == null || fallbackFontUrl.isNotBlank()) {
            "The ASS subtitle fallback font URL must not be blank."
        }
        require(fallbackFontFamily.isNotBlank()) {
            "The ASS subtitle fallback font family must not be blank."
        }
        require(preloadFontUrlValues.all { it.isNotBlank() }) {
            "ASS subtitle preload font URLs must not be blank."
        }
        require(
            availableFontUrlValues.all { (family, url) ->
                family.isNotBlank() && url.isNotBlank()
            },
        ) {
            "ASS subtitle available font families and URLs must not be blank."
        }

        val normalizedFontFamilies = availableFontUrlValues.keys.map { it.trim().lowercase() }
        require(normalizedFontFamilies.distinct().size == normalizedFontFamilies.size) {
            "ASS subtitle available font families must be unique ignoring case and surrounding whitespace."
        }
    }

    @Suppress("LongParameterList")
    fun copy(
        enabled: Boolean = this.enabled,
        workerUrl: String? = this.workerUrl,
        wasmUrl: String? = this.wasmUrl,
        modernWasmUrl: String? = this.modernWasmUrl,
        fallbackFontUrl: String? = this.fallbackFontUrl,
        fallbackFontFamily: String = this.fallbackFontFamily,
        preloadFontUrls: List<String> = this.preloadFontUrlValues,
        availableFontUrls: Map<String, String> = this.availableFontUrlValues,
        fontQueryMode: AssFontQueryMode = this.fontQueryMode,
        debug: Boolean = this.debug,
    ): AssSubtitleRendererConfig =
        AssSubtitleRendererConfig(
            enabled = enabled,
            workerUrl = workerUrl,
            wasmUrl = wasmUrl,
            modernWasmUrl = modernWasmUrl,
            fallbackFontUrl = fallbackFontUrl,
            fallbackFontFamily = fallbackFontFamily,
            preloadFontUrls = preloadFontUrls,
            availableFontUrls = availableFontUrls,
            fontQueryMode = fontQueryMode,
            debug = debug,
        )

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is AssSubtitleRendererConfig &&
                    enabled == other.enabled &&
                    workerUrl == other.workerUrl &&
                    wasmUrl == other.wasmUrl &&
                    modernWasmUrl == other.modernWasmUrl &&
                    fallbackFontUrl == other.fallbackFontUrl &&
                    fallbackFontFamily == other.fallbackFontFamily &&
                    preloadFontUrlValues == other.preloadFontUrlValues &&
                    availableFontUrlValues == other.availableFontUrlValues &&
                    fontQueryMode == other.fontQueryMode &&
                    debug == other.debug
            )

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + (workerUrl?.hashCode() ?: 0)
        result = 31 * result + (wasmUrl?.hashCode() ?: 0)
        result = 31 * result + (modernWasmUrl?.hashCode() ?: 0)
        result = 31 * result + (fallbackFontUrl?.hashCode() ?: 0)
        result = 31 * result + fallbackFontFamily.hashCode()
        result = 31 * result + preloadFontUrlValues.hashCode()
        result = 31 * result + availableFontUrlValues.hashCode()
        result = 31 * result + fontQueryMode.hashCode()
        result = 31 * result + debug.hashCode()
        return result
    }

    override fun toString(): String =
        "AssSubtitleRendererConfig(" +
            "enabled=$enabled, " +
            "workerUrlConfigured=${workerUrl != null}, " +
            "wasmUrlConfigured=${wasmUrl != null}, " +
            "modernWasmUrlConfigured=${modernWasmUrl != null}, " +
            "fallbackFontUrlConfigured=${fallbackFontUrl != null}, " +
            "fallbackFontFamily=$fallbackFontFamily, " +
            "preloadFontCount=${preloadFontUrlValues.size}, " +
            "availableFontFamilies=${availableFontUrlValues.keys}, " +
            "fontQueryMode=$fontQueryMode, " +
            "debug=$debug)"
}

/** Controls whether JASSUB may query local system fonts or its remote font provider. */
enum class AssFontQueryMode {
    DISABLED,
    LOCAL,
    LOCAL_AND_REMOTE,
}
