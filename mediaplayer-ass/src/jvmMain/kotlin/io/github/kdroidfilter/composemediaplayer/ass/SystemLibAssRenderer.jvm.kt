package io.github.kdroidfilter.composemediaplayer.ass

import io.github.kdroidfilter.composemediaplayer.DesktopSubtitleFont
import io.github.kdroidfilter.composemediaplayer.DesktopSubtitleRenderer
import io.github.shusek.kmediaffmpeg.runtime.KMediaAssRuntime
import io.github.shusek.kmediaffmpeg.runtime.RuntimeSource
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * JVM renderer backed by the shared KMediaAssRuntime.
 *
 * JDK 25's stable Foreign Function API keeps this bridge independent from a
 * platform-specific JNI shim. The libass renderer still owns shaping and
 * rasterization; Kotlin only composites the returned ASS_Image list into the
 * player's writable BGRA frame.
 */
internal class SystemLibAssSubtitleRenderer private constructor(
    private val runtime: SystemLibAssRuntime.Loaded,
) : DesktopSubtitleRenderer {
    private val lock = Any()
    private var library: MemorySegment = MemorySegment.NULL
    private var renderer: MemorySegment = MemorySegment.NULL
    private var track: MemorySegment = MemorySegment.NULL
    private var frameWidth = 0
    private var frameHeight = 0

    init {
        synchronized(lock) {
            library = runtime.functions.libraryInit()
            check(library.isNativePointer()) { "libass could not create its library context." }
            renderer = runtime.functions.rendererInit(library)
            if (!renderer.isNativePointer()) {
                runtime.functions.libraryDone(library)
                library = MemorySegment.NULL
                error("libass could not create its renderer context.")
            }
            track = runtime.functions.newTrack(library)
            if (!track.isNativePointer()) {
                runtime.functions.rendererDone(renderer)
                runtime.functions.libraryDone(library)
                renderer = MemorySegment.NULL
                library = MemorySegment.NULL
                error("libass could not create an ASS track.")
            }
            runtime.functions.setShaper(renderer, ASS_SHAPING_COMPLEX)
            runtime.functions.setCacheLimits(renderer, MAX_GLYPH_CACHE, MAX_BITMAP_CACHE_MIB)
            Arena.ofConfined().use { arena ->
                runtime.functions.setFonts(
                    renderer = renderer,
                    defaultFont = MemorySegment.NULL,
                    defaultFamily = arena.allocateFrom(DEFAULT_FONT_FAMILY),
                    provider = ASS_FONTPROVIDER_AUTODETECT,
                    configuration = MemorySegment.NULL,
                    update = true,
                )
            }
        }
    }

    override val backendDescription: String =
        "${runtime.originLabel} libass ${runtime.versionLabel} / ${systemFontProviderLabel()}"

    override fun addFont(font: DesktopSubtitleFont): Boolean =
        synchronized(lock) {
            if (!isOpen()) return@synchronized false
            runCatching {
                Arena.ofConfined().use { arena ->
                    val name = arena.allocateFrom(font.name)
                    val data = arena.allocate(font.data.size.toLong())
                    data.asByteBuffer().put(font.data)
                    runtime.functions.addFont(library, name, data, font.data.size)
                    runtime.functions.setFonts(
                        renderer = renderer,
                        defaultFont = MemorySegment.NULL,
                        defaultFamily = arena.allocateFrom(DEFAULT_FONT_FAMILY),
                        provider = ASS_FONTPROVIDER_AUTODETECT,
                        configuration = MemorySegment.NULL,
                        update = false,
                    )
                }
            }.isSuccess
        }

    override fun setTrack(data: ByteArray): Boolean =
        synchronized(lock) {
            if (!isOpen() || data.isEmpty() || data.size > MAX_INPUT_BYTES) {
                return@synchronized false
            }
            val replacement = runtime.functions.newTrack(library)
            if (!replacement.isNativePointer()) return@synchronized false

            val loaded =
                runCatching {
                    Arena.ofConfined().use { arena ->
                        val nativeData = arena.allocate(data.size.toLong())
                        nativeData.asByteBuffer().put(data)
                        runtime.functions.processData(replacement, nativeData, data.size)
                    }
                }.isSuccess
            if (!loaded) {
                runtime.functions.freeTrack(replacement)
                return@synchronized false
            }

            runtime.functions.freeTrack(track)
            track = replacement
            frameWidth = 0
            frameHeight = 0
            true
        }

    override fun blendBgraFrame(
        pixels: ByteBuffer,
        rowBytes: Int,
        width: Int,
        height: Int,
        timeMs: Long,
    ): Boolean {
        require(pixels.isDirect) { "The desktop subtitle frame must use a direct ByteBuffer." }
        require(width > 0 && height > 0) { "The subtitle frame dimensions must be positive." }
        require(width <= Int.MAX_VALUE / BGRA_BYTES_PER_PIXEL) { "The subtitle frame width is too large." }
        require(rowBytes >= width * BGRA_BYTES_PER_PIXEL) { "The subtitle frame row stride is too small." }
        require(rowBytes.toLong() * height <= pixels.capacity().toLong()) {
            "The direct subtitle frame buffer is too small."
        }

        return synchronized(lock) {
            if (!isOpen()) return@synchronized false
            runCatching {
                if (frameWidth != width || frameHeight != height) {
                    runtime.functions.setFrameSize(renderer, width, height)
                    runtime.functions.setStorageSize(renderer, width, height)
                    frameWidth = width
                    frameHeight = height
                }

                Arena.ofConfined().use { arena ->
                    val changed = arena.allocate(ValueLayout.JAVA_INT)
                    var image =
                        runtime.functions.renderFrame(
                            renderer = renderer,
                            track = track,
                            timeMs = timeMs.coerceAtLeast(0L),
                            changed = changed,
                        )
                    var imageCount = 0
                    while (image.isNativePointer()) {
                        if (++imageCount > MAX_ASS_IMAGES) {
                            error("libass returned an unexpectedly long ASS_Image chain.")
                        }
                        image = blendImage(image, pixels, rowBytes, width, height)
                    }
                }
            }.isSuccess
        }
    }

    private fun blendImage(
        imageAddress: MemorySegment,
        pixels: ByteBuffer,
        rowBytes: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): MemorySegment {
        val image = imageAddress.reinterpret(ASS_IMAGE_LAYOUT_SIZE)
        val width = image.get(ValueLayout.JAVA_INT, ASS_IMAGE_WIDTH_OFFSET)
        val height = image.get(ValueLayout.JAVA_INT, ASS_IMAGE_HEIGHT_OFFSET)
        val stride = image.get(ValueLayout.JAVA_INT, ASS_IMAGE_STRIDE_OFFSET)
        val bitmapAddress = image.get(ValueLayout.ADDRESS, ASS_IMAGE_BITMAP_OFFSET)
        val color = image.get(ValueLayout.JAVA_INT, ASS_IMAGE_COLOR_OFFSET)
        val destinationX = image.get(ValueLayout.JAVA_INT, ASS_IMAGE_DESTINATION_X_OFFSET)
        val destinationY = image.get(ValueLayout.JAVA_INT, ASS_IMAGE_DESTINATION_Y_OFFSET)
        val next = image.get(ValueLayout.ADDRESS, ASS_IMAGE_NEXT_OFFSET)

        if (width <= 0 || height <= 0 || stride < width || !bitmapAddress.isNativePointer()) {
            return next
        }
        val bitmapSize = stride.toLong() * height.toLong()
        if (bitmapSize <= 0L || bitmapSize > MAX_BITMAP_BYTES) {
            error("libass returned an invalid subtitle bitmap.")
        }
        val bitmap = bitmapAddress.reinterpret(bitmapSize)

        val clippedLeft = max(0, -destinationX)
        val clippedTop = max(0, -destinationY)
        val clippedRight = min(width, frameWidth - destinationX)
        val clippedBottom = min(height, frameHeight - destinationY)
        if (clippedLeft >= clippedRight || clippedTop >= clippedBottom) return next

        val red = color ushr 24 and 0xff
        val green = color ushr 16 and 0xff
        val blue = color ushr 8 and 0xff
        val opaqueAlpha = MAX_CHANNEL_VALUE - (color and MAX_CHANNEL_VALUE)

        for (row in clippedTop until clippedBottom) {
            val sourceRow = row.toLong() * stride.toLong()
            var destinationOffset =
                (destinationY + row) * rowBytes +
                    (destinationX + clippedLeft) * BGRA_BYTES_PER_PIXEL
            for (column in clippedLeft until clippedRight) {
                val coverage =
                    bitmap
                        .get(ValueLayout.JAVA_BYTE, sourceRow + column.toLong())
                        .toInt() and 0xff
                val sourceAlpha =
                    (coverage * opaqueAlpha + ALPHA_ROUNDING_BIAS) / MAX_CHANNEL_VALUE
                if (sourceAlpha != 0) {
                    val inverseAlpha = MAX_CHANNEL_VALUE - sourceAlpha
                    val destinationBlue = pixels.get(destinationOffset).toInt() and 0xff
                    val destinationGreen = pixels.get(destinationOffset + 1).toInt() and 0xff
                    val destinationRed = pixels.get(destinationOffset + 2).toInt() and 0xff
                    val destinationAlpha = pixels.get(destinationOffset + 3).toInt() and 0xff

                    pixels.put(
                        destinationOffset,
                        blendStraightChannel(blue, destinationBlue, sourceAlpha, inverseAlpha).toByte(),
                    )
                    pixels.put(
                        destinationOffset + 1,
                        blendStraightChannel(green, destinationGreen, sourceAlpha, inverseAlpha).toByte(),
                    )
                    pixels.put(
                        destinationOffset + 2,
                        blendStraightChannel(red, destinationRed, sourceAlpha, inverseAlpha).toByte(),
                    )
                    pixels.put(
                        destinationOffset + ALPHA_CHANNEL_OFFSET,
                        (
                            sourceAlpha +
                                (destinationAlpha * inverseAlpha + ALPHA_ROUNDING_BIAS) / MAX_CHANNEL_VALUE
                        ).toByte(),
                    )
                }
                destinationOffset += BGRA_BYTES_PER_PIXEL
            }
        }
        return next
    }

    override fun close() {
        synchronized(lock) {
            if (track.isNativePointer()) runtime.functions.freeTrack(track)
            if (renderer.isNativePointer()) runtime.functions.rendererDone(renderer)
            if (library.isNativePointer()) runtime.functions.libraryDone(library)
            track = MemorySegment.NULL
            renderer = MemorySegment.NULL
            library = MemorySegment.NULL
            frameWidth = 0
            frameHeight = 0
        }
    }

    private fun isOpen(): Boolean =
        library.isNativePointer() &&
            renderer.isNativePointer() &&
            track.isNativePointer()

    internal companion object {
        fun create(): SystemLibAssSubtitleRenderer = SystemLibAssSubtitleRenderer(SystemLibAssRuntime.requireLoaded())

        private const val ASS_SHAPING_COMPLEX = 1
        private const val ASS_FONTPROVIDER_AUTODETECT = 1
        private const val DEFAULT_FONT_FAMILY = "Arial"
        private const val MAX_GLYPH_CACHE = 1_000_000
        private const val MAX_BITMAP_CACHE_MIB = 64
        private const val MAX_INPUT_BYTES = 64 * 1024 * 1024
        private const val MAX_ASS_IMAGES = 65_536
        private const val BGRA_BYTES_PER_PIXEL = 4
        private const val ALPHA_CHANNEL_OFFSET = 3
        private const val MAX_BITMAP_BYTES = 64L * 1024L * 1024L
        private const val MAX_CHANNEL_VALUE = 255
        private const val ALPHA_ROUNDING_BIAS = 127

        private const val ASS_IMAGE_WIDTH_OFFSET = 0L
        private const val ASS_IMAGE_HEIGHT_OFFSET = 4L
        private const val ASS_IMAGE_STRIDE_OFFSET = 8L
        private val ASS_IMAGE_BITMAP_OFFSET = align(12L, ValueLayout.ADDRESS.byteAlignment())
        private val ASS_IMAGE_COLOR_OFFSET = ASS_IMAGE_BITMAP_OFFSET + ValueLayout.ADDRESS.byteSize()
        private val ASS_IMAGE_DESTINATION_X_OFFSET = ASS_IMAGE_COLOR_OFFSET + Int.SIZE_BYTES
        private val ASS_IMAGE_DESTINATION_Y_OFFSET = ASS_IMAGE_DESTINATION_X_OFFSET + Int.SIZE_BYTES
        private val ASS_IMAGE_NEXT_OFFSET =
            align(
                ASS_IMAGE_DESTINATION_Y_OFFSET + Int.SIZE_BYTES,
                ValueLayout.ADDRESS.byteAlignment(),
            )
        private val ASS_IMAGE_LAYOUT_SIZE =
            align(
                ASS_IMAGE_NEXT_OFFSET + ValueLayout.ADDRESS.byteSize() + Int.SIZE_BYTES,
                ValueLayout.ADDRESS.byteAlignment(),
            )

        private fun align(
            value: Long,
            alignment: Long,
        ): Long = (value + alignment - 1L) / alignment * alignment

        private fun blendStraightChannel(
            source: Int,
            destination: Int,
            sourceAlpha: Int,
            inverseAlpha: Int,
        ): Int =
            (
                source * sourceAlpha +
                    destination * inverseAlpha +
                    ALPHA_ROUNDING_BIAS
            ) / MAX_CHANNEL_VALUE
    }
}

internal object SystemLibAssRuntime {
    private val loadResult: Result<Loaded> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { load() }
    }

    val isAvailable: Boolean
        get() = loadResult.isSuccess

    val failureDetail: String
        get() =
            loadResult.exceptionOrNull()?.let { failure ->
                "The shared KMediaAssRuntime could not be loaded " +
                    "(${failure::class.simpleName}: ${failure.message.orEmpty().take(MAX_FAILURE_DETAIL_LENGTH)})."
            } ?: "The exact shared KMediaAssRuntime is available."

    fun requireLoaded(): Loaded = loadResult.getOrThrow()

    @Suppress("TooGenericExceptionCaught")
    private fun load(): Loaded {
        require(ValueLayout.ADDRESS.byteSize() == Long.SIZE_BYTES.toLong()) {
            "The desktop libass bridge supports 64-bit JVM runtimes only."
        }
        val report = KMediaAssRuntime.initialize(RuntimeSource.bundled())
        check(report.runtimeId() == REQUIRED_ASS_RUNTIME_ID) {
            "composemediaplayer-ass targets another KMediaAssRuntime ID."
        }
        val functions = LibAssFunctions(SymbolLookup.loaderLookup())
        val version = functions.libraryVersion()
        check(version == REQUIRED_LIBASS_VERSION) {
            "The loaded shared libass version differs from KMediaAssRuntime's manifest."
        }
        return Loaded(
            arena = Arena.global(),
            functions = functions,
            versionLabel =
                "0x${version.toUInt().toString(HEX_RADIX).padStart(VERSION_HEX_WIDTH, '0')}",
            source = "<shared-runtime>",
            originLabel = "shared",
        )
    }

    internal data class Loaded(
        @Suppress("unused")
        val arena: Arena,
        val functions: LibAssFunctions,
        val versionLabel: String,
        val source: String,
        val originLabel: String,
    )

    private const val REQUIRED_LIBASS_VERSION = 0x01705000
    private const val REQUIRED_ASS_RUNTIME_ID = "kmediaass-0.17.5-36443523f0148567"
    private const val MAX_FAILURE_DETAIL_LENGTH = 240
    private const val HEX_RADIX = 16
    private const val VERSION_HEX_WIDTH = 8
}

internal class LibAssFunctions(
    lookup: SymbolLookup,
) {
    private val linker = Linker.nativeLinker()

    private val libraryVersion = bind(lookup, "ass_library_version", FunctionDescriptor.of(ValueLayout.JAVA_INT))
    private val libraryInit = bind(lookup, "ass_library_init", FunctionDescriptor.of(ValueLayout.ADDRESS))
    private val libraryDone =
        bind(
            lookup,
            "ass_library_done",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        )
    private val rendererInit =
        bind(
            lookup,
            "ass_renderer_init",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
    private val rendererDone =
        bind(
            lookup,
            "ass_renderer_done",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        )
    private val newTrack =
        bind(
            lookup,
            "ass_new_track",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
    private val freeTrack =
        bind(
            lookup,
            "ass_free_track",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        )
    private val setShaper =
        bind(
            lookup,
            "ass_set_shaper",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
        )
    private val setCacheLimits =
        bind(
            lookup,
            "ass_set_cache_limits",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
            ),
        )
    private val setFonts =
        bind(
            lookup,
            "ass_set_fonts",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )
    private val addFont =
        bind(
            lookup,
            "ass_add_font",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )
    private val processData =
        bind(
            lookup,
            "ass_process_data",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )
    private val setFrameSize =
        bind(
            lookup,
            "ass_set_frame_size",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
            ),
        )
    private val setStorageSize =
        bind(
            lookup,
            "ass_set_storage_size",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
            ),
        )
    private val renderFrame =
        bind(
            lookup,
            "ass_render_frame",
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
            ),
        )

    fun libraryVersion(): Int = libraryVersion.invokeWithArguments() as Int

    fun libraryInit(): MemorySegment = libraryInit.invokeWithArguments() as MemorySegment

    fun libraryDone(library: MemorySegment) {
        libraryDone.invokeWithArguments(library)
    }

    fun rendererInit(library: MemorySegment): MemorySegment = rendererInit.invokeWithArguments(library) as MemorySegment

    fun rendererDone(renderer: MemorySegment) {
        rendererDone.invokeWithArguments(renderer)
    }

    fun newTrack(library: MemorySegment): MemorySegment = newTrack.invokeWithArguments(library) as MemorySegment

    fun freeTrack(track: MemorySegment) {
        freeTrack.invokeWithArguments(track)
    }

    fun setShaper(
        renderer: MemorySegment,
        shaper: Int,
    ) {
        setShaper.invokeWithArguments(renderer, shaper)
    }

    fun setCacheLimits(
        renderer: MemorySegment,
        glyphs: Int,
        bitmapMiB: Int,
    ) {
        setCacheLimits.invokeWithArguments(renderer, glyphs, bitmapMiB)
    }

    fun setFonts(
        renderer: MemorySegment,
        defaultFont: MemorySegment,
        defaultFamily: MemorySegment,
        provider: Int,
        configuration: MemorySegment,
        update: Boolean,
    ) {
        setFonts.invokeWithArguments(
            renderer,
            defaultFont,
            defaultFamily,
            provider,
            configuration,
            if (update) 1 else 0,
        )
    }

    fun addFont(
        library: MemorySegment,
        name: MemorySegment,
        data: MemorySegment,
        size: Int,
    ) {
        addFont.invokeWithArguments(library, name, data, size)
    }

    fun processData(
        track: MemorySegment,
        data: MemorySegment,
        size: Int,
    ) {
        processData.invokeWithArguments(track, data, size)
    }

    fun setFrameSize(
        renderer: MemorySegment,
        width: Int,
        height: Int,
    ) {
        setFrameSize.invokeWithArguments(renderer, width, height)
    }

    fun setStorageSize(
        renderer: MemorySegment,
        width: Int,
        height: Int,
    ) {
        setStorageSize.invokeWithArguments(renderer, width, height)
    }

    fun renderFrame(
        renderer: MemorySegment,
        track: MemorySegment,
        timeMs: Long,
        changed: MemorySegment,
    ): MemorySegment = renderFrame.invokeWithArguments(renderer, track, timeMs, changed) as MemorySegment

    private fun bind(
        lookup: SymbolLookup,
        name: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle {
        val symbol =
            lookup.find(name).orElseThrow {
                UnsatisfiedLinkError("The loaded library does not export $name.")
            }
        return linker.downcallHandle(symbol, descriptor)
    }
}

private fun MemorySegment.isNativePointer(): Boolean = address() != 0L

private fun systemFontProviderLabel(): String =
    when {
        System.getProperty("os.name", "").contains("windows", ignoreCase = true) -> "DirectWrite"
        System.getProperty("os.name", "").contains("linux", ignoreCase = true) -> "fontconfig"
        else -> "system fonts"
    }
