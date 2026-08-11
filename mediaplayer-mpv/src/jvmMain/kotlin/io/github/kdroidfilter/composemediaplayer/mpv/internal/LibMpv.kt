package io.github.kdroidfilter.composemediaplayer.mpv.internal

import io.github.kdroidfilter.composemediaplayer.mpv.MpvClientApiVersion
import io.github.kdroidfilter.composemediaplayer.mpv.MpvLibrarySource
import io.github.kdroidfilter.composemediaplayer.mpv.MpvRuntime
import io.github.kdroidfilter.composemediaplayer.mpv.MpvUnavailableReason
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.max

internal class MpvLoadFailure(
    val reason: MpvUnavailableReason,
    val guidance: String,
    cause: Throwable? = null,
) : IllegalStateException(guidance, cause)

internal sealed interface NativeMpvEvent {
    data object None : NativeMpvEvent

    data object Shutdown : NativeMpvEvent

    data object FileLoaded : NativeMpvEvent

    data class EndFile(
        val reason: Int,
        val errorCode: Int,
    ) : NativeMpvEvent

    data object SeekStarted : NativeMpvEvent

    data object PlaybackRestarted : NativeMpvEvent
}

/**
 * Minimal FFM mapping derived from mpv 0.41's ISC-licensed client.h and render.h.
 * No mpv header or native binary is packaged in the artifact.
 */
internal class LibMpvLibrary private constructor(
    private val arena: Arena,
    private val lookup: SymbolLookup,
    val loadedFrom: MpvLibrarySource,
) : AutoCloseable {
    private val linker = Linker.nativeLinker()
    private val cLongLayout = linker.canonicalLayouts().getValue("long") as ValueLayout
    private val sizeTLayout = linker.canonicalLayouts().getValue("size_t") as ValueLayout

    private val clientApiVersionHandle = downcall("mpv_client_api_version", FunctionDescriptor.of(cLongLayout))
    private val createHandle = downcall("mpv_create", FunctionDescriptor.of(ValueLayout.ADDRESS))
    private val initializeHandle =
        downcall(
            "mpv_initialize",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        )
    private val terminateDestroyHandle =
        downcall(
            "mpv_terminate_destroy",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        )
    private val setOptionStringHandle =
        downcall(
            "mpv_set_option_string",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )
    private val setPropertyStringHandle =
        downcall(
            "mpv_set_property_string",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )
    private val setPropertyHandle =
        downcall(
            "mpv_set_property",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )
    private val getPropertyStringHandle =
        downcall(
            "mpv_get_property_string",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
    private val commandHandle =
        downcall(
            "mpv_command",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
    private val waitEventHandle =
        downcall(
            "mpv_wait_event",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE),
        )
    private val wakeupHandle =
        downcall(
            "mpv_wakeup",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        )
    private val freeHandle =
        downcall(
            "mpv_free",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        )
    private val errorStringHandle =
        downcall(
            "mpv_error_string",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
        )
    private val renderContextCreateHandle =
        downcall(
            "mpv_render_context_create",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )
    private val renderContextRenderHandle =
        downcall(
            "mpv_render_context_render",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
    private val renderContextFreeHandle =
        downcall(
            "mpv_render_context_free",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        )
    private val embeddedMacVkApiVersionHandle: MethodHandle? =
        lookup
            .find(EMBEDDED_MACVK_API_VERSION_SYMBOL)
            .map { symbol ->
                linker.downcallHandle(
                    symbol,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT),
                )
            }.orElse(null)
    private val embeddedMacVkPresentedFramesHandle: MethodHandle? =
        lookup
            .find(EMBEDDED_MACVK_PRESENTED_FRAMES_SYMBOL)
            .map { symbol ->
                linker.downcallHandle(
                    symbol,
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                    ),
                )
            }.orElse(null)

    val clientApiVersion: MpvClientApiVersion
        get() {
            val raw = (clientApiVersionHandle.invokeWithArguments() as Number).toLong()
            return MpvClientApiVersion(
                major = ((raw ushr 16) and 0xffff).toInt(),
                minor = (raw and 0xffff).toInt(),
            )
        }

    /**
     * Version of KMediaMpv's private `wid`-backed macvk contract.
     *
     * Stock libmpv does not implement macOS `wid` for macvk even though older client API
     * documentation suggested it did. Requiring an explicit symbol prevents an unpatched
     * runtime from opening and managing a separate mpv window.
     */
    val embeddedMacVkApiVersion: Int
        get() =
            embeddedMacVkApiVersionHandle
                ?.let { handle ->
                    runCatching { (handle.invokeWithArguments() as Number).toInt() }
                        .getOrDefault(0)
                } ?: 0

    fun embeddedMacVkPresentedFrames(nativeView: Long): Long? {
        if (nativeView <= 0L) return null
        return embeddedMacVkPresentedFramesHandle
            ?.let { handle ->
                runCatching {
                    (handle.invokeWithArguments(nativeView) as Number)
                        .toLong()
                        .takeIf { it >= 0L }
                }.getOrNull()
            }
    }

    fun createEngine(
        options: Map<String, String>,
        createSoftwareRenderer: Boolean = true,
    ): LibMpvEngine {
        val version = clientApiVersion
        if (version.major != MpvRuntime.COMPILED_CLIENT_API_MAJOR) {
            throw MpvLoadFailure(
                MpvUnavailableReason.INCOMPATIBLE_CLIENT_API,
                "The installed libmpv client API major is ${version.major}; " +
                    "required: ${MpvRuntime.COMPILED_CLIENT_API_MAJOR}.",
            )
        }

        ensureNativeNumericLocale()
        val handle = createHandle.invokeWithArguments() as MemorySegment
        if (handle.address() == 0L) {
            throw MpvLoadFailure(MpvUnavailableReason.LOAD_FAILED, "libmpv could not create a player instance.")
        }

        try {
            options.forEach { (name, value) ->
                checkResult(
                    handle = handle,
                    method = setOptionStringHandle,
                    name = name,
                    value = value,
                    allowMissing = name == "load-scripts",
                )
            }
            checkResult(initializeHandle.invokeWithArguments(handle) as Int)
            val renderContext =
                if (createSoftwareRenderer) {
                    createSoftwareRenderContext(handle)
                } else {
                    null
                }
            return LibMpvEngine(this, handle, renderContext)
        } catch (failure: Throwable) {
            terminateDestroyHandle.invokeWithArguments(handle)
            throw failure
        }
    }

    internal fun createSoftwareRenderContext(handle: MemorySegment): MemorySegment =
        Arena.ofConfined().use { callArena ->
            val output = callArena.allocate(ValueLayout.ADDRESS)
            val apiType = callArena.allocateFrom("sw")
            val params = callArena.allocate(RENDER_PARAM_LAYOUT, 2)
            setRenderParam(params, 0, MPV_RENDER_PARAM_API_TYPE, apiType)
            setRenderParam(params, 1, MPV_RENDER_PARAM_INVALID, MemorySegment.NULL)
            checkResult(renderContextCreateHandle.invokeWithArguments(output, handle, params) as Int)
            output.get(ValueLayout.ADDRESS, 0).also { renderContext ->
                if (renderContext.address() == 0L) {
                    throw MpvLoadFailure(
                        MpvUnavailableReason.LOAD_FAILED,
                        "The installed libmpv does not provide its software render API.",
                    )
                }
            }
        }

    internal fun command(
        handle: MemorySegment,
        arguments: List<String>,
    ) {
        require(arguments.isNotEmpty()) { "An mpv command must not be empty." }
        Arena.ofConfined().use { callArena ->
            val pointers = callArena.allocate(ValueLayout.ADDRESS, arguments.size.toLong() + 1L)
            arguments.forEachIndexed { index, argument ->
                pointers.setAtIndex(ValueLayout.ADDRESS, index.toLong(), callArena.allocateFrom(argument))
            }
            pointers.setAtIndex(ValueLayout.ADDRESS, arguments.size.toLong(), MemorySegment.NULL)
            checkResult(commandHandle.invokeWithArguments(handle, pointers) as Int)
        }
    }

    internal fun setProperty(
        handle: MemorySegment,
        name: String,
        value: String,
    ) {
        checkResult(handle, setPropertyStringHandle, name, value)
    }

    internal fun setStringListProperty(
        handle: MemorySegment,
        name: String,
        values: List<String>,
    ) {
        Arena.ofConfined().use { callArena ->
            val nativeValues =
                if (values.isEmpty()) {
                    MemorySegment.NULL
                } else {
                    callArena.allocate(MPV_NODE_LAYOUT, values.size.toLong()).also { nodes ->
                        values.forEachIndexed { index, value ->
                            val offset = MPV_NODE_LAYOUT.byteSize() * index
                            nodes.set(
                                ValueLayout.ADDRESS,
                                offset + MPV_NODE_DATA_OFFSET,
                                callArena.allocateFrom(value),
                            )
                            nodes.set(ValueLayout.JAVA_INT, offset + MPV_NODE_FORMAT_OFFSET, MPV_FORMAT_STRING)
                        }
                    }
                }
            val nativeList = callArena.allocate(MPV_NODE_LIST_LAYOUT)
            nativeList.set(ValueLayout.JAVA_INT, MPV_NODE_LIST_COUNT_OFFSET, values.size)
            nativeList.set(ValueLayout.ADDRESS, MPV_NODE_LIST_VALUES_OFFSET, nativeValues)
            nativeList.set(ValueLayout.ADDRESS, MPV_NODE_LIST_KEYS_OFFSET, MemorySegment.NULL)

            val root = callArena.allocate(MPV_NODE_LAYOUT)
            root.set(ValueLayout.ADDRESS, MPV_NODE_DATA_OFFSET, nativeList)
            root.set(ValueLayout.JAVA_INT, MPV_NODE_FORMAT_OFFSET, MPV_FORMAT_NODE_ARRAY)
            val result =
                setPropertyHandle.invokeWithArguments(
                    handle,
                    callArena.allocateFrom(name),
                    MPV_FORMAT_NODE,
                    root,
                ) as Int
            checkResult(result)
        }
    }

    internal fun getProperty(
        handle: MemorySegment,
        name: String,
    ): String? =
        Arena.ofConfined().use { callArena ->
            val nativeName = callArena.allocateFrom(name)
            val result = getPropertyStringHandle.invokeWithArguments(handle, nativeName) as MemorySegment
            if (result.address() == 0L) return@use null
            try {
                result.reinterpret(MAX_PROPERTY_BYTES).getString(0)
            } finally {
                freeHandle.invokeWithArguments(result)
            }
        }

    internal fun waitEvent(
        handle: MemorySegment,
        timeoutSeconds: Double,
    ): NativeMpvEvent {
        val pointer = waitEventHandle.invokeWithArguments(handle, timeoutSeconds) as MemorySegment
        if (pointer.address() == 0L) return NativeMpvEvent.None
        val event = pointer.reinterpret(EVENT_LAYOUT.byteSize())
        return when (event.get(ValueLayout.JAVA_INT, EVENT_ID_OFFSET)) {
            MPV_EVENT_NONE -> NativeMpvEvent.None
            MPV_EVENT_SHUTDOWN -> NativeMpvEvent.Shutdown
            MPV_EVENT_FILE_LOADED -> NativeMpvEvent.FileLoaded
            MPV_EVENT_END_FILE -> {
                val data = event.get(ValueLayout.ADDRESS, EVENT_DATA_OFFSET)
                if (data.address() == 0L) {
                    NativeMpvEvent.EndFile(reason = 0, errorCode = 0)
                } else {
                    val endFile = data.reinterpret(END_FILE_PREFIX_LAYOUT.byteSize())
                    NativeMpvEvent.EndFile(
                        reason = endFile.get(ValueLayout.JAVA_INT, 0),
                        errorCode = endFile.get(ValueLayout.JAVA_INT, 4),
                    )
                }
            }
            MPV_EVENT_SEEK -> NativeMpvEvent.SeekStarted
            MPV_EVENT_PLAYBACK_RESTART -> NativeMpvEvent.PlaybackRestarted
            else -> NativeMpvEvent.None
        }
    }

    internal fun wakeup(handle: MemorySegment) {
        wakeupHandle.invokeWithArguments(handle)
    }

    internal fun render(
        renderContext: MemorySegment,
        width: Int,
        height: Int,
        rowBytes: Long,
        pixelsAddress: Long,
    ) {
        require(width > 0 && height > 0) { "The render target must have a positive size." }
        require(rowBytes >= width.toLong() * BYTES_PER_PIXEL) { "The render target stride is too small." }
        require(pixelsAddress != 0L) { "The render target pointer must not be null." }

        Arena.ofConfined().use { callArena ->
            val size = callArena.allocateFrom(ValueLayout.JAVA_INT, width, height)
            val format = callArena.allocateFrom("bgr0")
            val stride = callArena.allocate(sizeTLayout)
            writeNativeNumber(stride, sizeTLayout, rowBytes)

            val params = callArena.allocate(RENDER_PARAM_LAYOUT, 5)
            setRenderParam(params, 0, MPV_RENDER_PARAM_SW_SIZE, size)
            setRenderParam(params, 1, MPV_RENDER_PARAM_SW_FORMAT, format)
            setRenderParam(params, 2, MPV_RENDER_PARAM_SW_STRIDE, stride)
            setRenderParam(
                params,
                3,
                MPV_RENDER_PARAM_SW_POINTER,
                MemorySegment.ofAddress(pixelsAddress),
            )
            setRenderParam(params, 4, MPV_RENDER_PARAM_INVALID, MemorySegment.NULL)
            checkResult(renderContextRenderHandle.invokeWithArguments(renderContext, params) as Int)
        }
    }

    internal fun freeRenderContext(renderContext: MemorySegment) {
        if (renderContext.address() != 0L) renderContextFreeHandle.invokeWithArguments(renderContext)
    }

    internal fun terminate(handle: MemorySegment) {
        if (handle.address() != 0L) terminateDestroyHandle.invokeWithArguments(handle)
    }

    internal fun errorMessage(errorCode: Int): String {
        val pointer = errorStringHandle.invokeWithArguments(errorCode) as MemorySegment
        return if (pointer.address() == 0L) {
            "libmpv error $errorCode"
        } else {
            pointer.reinterpret(MAX_ERROR_BYTES).getString(0)
        }
    }

    private fun checkResult(
        handle: MemorySegment,
        method: MethodHandle,
        name: String,
        value: String,
        allowMissing: Boolean = false,
    ) {
        Arena.ofConfined().use { callArena ->
            val result =
                method.invokeWithArguments(
                    handle,
                    callArena.allocateFrom(name),
                    callArena.allocateFrom(value),
                ) as Int
            if (allowMissing && result == MPV_ERROR_OPTION_NOT_FOUND) return
            if (result < 0) {
                throw IllegalStateException("libmpv rejected '$name': ${errorMessage(result)}")
            }
        }
    }

    private fun checkResult(result: Int) {
        if (result < 0) throw IllegalStateException(errorMessage(result))
    }

    private fun downcall(
        name: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle {
        val symbol =
            lookup.find(name).orElseThrow {
                MpvLoadFailure(
                    MpvUnavailableReason.REQUIRED_SYMBOL_MISSING,
                    "The loaded library is not a compatible libmpv: required API symbols are missing.",
                )
            }
        return linker.downcallHandle(symbol, descriptor)
    }

    override fun close() {
        if (!retainForProcessLifetime()) arena.close()
    }

    private fun retainForProcessLifetime(): Boolean =
        shouldRetainMpvLibraryForProcessLifetime(
            osName = System.getProperty("os.name", ""),
            embeddedMacVkApiVersion = embeddedMacVkApiVersion,
        )

    companion object {
        private const val EMBEDDED_MACVK_API_VERSION_SYMBOL =
            "kmediampv_embedded_macvk_api_version"
        private const val EMBEDDED_MACVK_PRESENTED_FRAMES_SYMBOL =
            "kmediampv_embedded_macvk_presented_frames"
        private const val MAX_PROPERTY_BYTES = 64L * 1024L
        private const val MAX_ERROR_BYTES = 4L * 1024L
        private const val BYTES_PER_PIXEL = 4L

        private const val MPV_RENDER_PARAM_INVALID = 0
        private const val MPV_RENDER_PARAM_API_TYPE = 1
        private const val MPV_RENDER_PARAM_SW_SIZE = 17
        private const val MPV_RENDER_PARAM_SW_FORMAT = 18
        private const val MPV_RENDER_PARAM_SW_STRIDE = 19
        private const val MPV_RENDER_PARAM_SW_POINTER = 20

        private const val MPV_EVENT_NONE = 0
        private const val MPV_EVENT_SHUTDOWN = 1
        private const val MPV_EVENT_END_FILE = 7
        private const val MPV_EVENT_FILE_LOADED = 8
        private const val MPV_EVENT_SEEK = 20
        private const val MPV_EVENT_PLAYBACK_RESTART = 21
        private const val MPV_ERROR_OPTION_NOT_FOUND = -5

        private const val MPV_FORMAT_STRING = 1
        private const val MPV_FORMAT_NODE = 6
        private const val MPV_FORMAT_NODE_ARRAY = 7

        private val RENDER_PARAM_LAYOUT =
            MemoryLayout.structLayout(
                ValueLayout.JAVA_INT.withName("type"),
                MemoryLayout.paddingLayout(4),
                ValueLayout.ADDRESS.withName("data"),
            )
        private val EVENT_LAYOUT =
            MemoryLayout.structLayout(
                ValueLayout.JAVA_INT.withName("event_id"),
                ValueLayout.JAVA_INT.withName("error"),
                ValueLayout.JAVA_LONG.withName("reply_userdata"),
                ValueLayout.ADDRESS.withName("data"),
            )
        private val END_FILE_PREFIX_LAYOUT =
            MemoryLayout.structLayout(
                ValueLayout.JAVA_INT.withName("reason"),
                ValueLayout.JAVA_INT.withName("error"),
            )
        private val MPV_NODE_LAYOUT =
            MemoryLayout.structLayout(
                ValueLayout.ADDRESS.withName("data"),
                ValueLayout.JAVA_INT.withName("format"),
                MemoryLayout.paddingLayout(4),
            )
        private val MPV_NODE_LIST_LAYOUT =
            MemoryLayout.structLayout(
                ValueLayout.JAVA_INT.withName("num"),
                MemoryLayout.paddingLayout(4),
                ValueLayout.ADDRESS.withName("values"),
                ValueLayout.ADDRESS.withName("keys"),
            )
        private const val EVENT_ID_OFFSET = 0L
        private const val EVENT_DATA_OFFSET = 16L
        private const val MPV_NODE_DATA_OFFSET = 0L
        private const val MPV_NODE_FORMAT_OFFSET = 8L
        private const val MPV_NODE_LIST_COUNT_OFFSET = 0L
        private const val MPV_NODE_LIST_VALUES_OFFSET = 8L
        private const val MPV_NODE_LIST_KEYS_OFFSET = 16L
        private val nativeLocaleLock = Any()

        fun open(source: MpvLibrarySource): LibMpvLibrary {
            val candidates = source.candidates()
            var nativeAccessDenied = false
            var sawLoadFailure = false
            for (candidate in candidates) {
                val arena = Arena.ofShared()
                try {
                    val lookup =
                        when (candidate) {
                            is MpvLibrarySource.ExplicitPath -> SymbolLookup.libraryLookup(candidate.path, arena)
                            is MpvLibrarySource.SystemLibrary -> SymbolLookup.libraryLookup(candidate.name, arena)
                            MpvLibrarySource.Automatic,
                            MpvLibrarySource.Bundled,
                            -> error("The runtime source must be resolved before loading.")
                        }
                    return LibMpvLibrary(arena, lookup, candidate).also { it.clientApiVersion }
                } catch (failure: IllegalCallerException) {
                    nativeAccessDenied = true
                    arena.close()
                    break
                } catch (failure: MpvLoadFailure) {
                    arena.close()
                    throw failure
                } catch (failure: IllegalArgumentException) {
                    sawLoadFailure = true
                    arena.close()
                } catch (failure: UnsatisfiedLinkError) {
                    sawLoadFailure = true
                    arena.close()
                }
            }

            if (nativeAccessDenied) {
                throw MpvLoadFailure(
                    MpvUnavailableReason.NATIVE_ACCESS_DISABLED,
                    "Native access is disabled. Start the JVM with --enable-native-access=ALL-UNNAMED.",
                )
            }
            throw MpvLoadFailure(
                reason =
                    if (sawLoadFailure) {
                        MpvUnavailableReason.LIBRARY_NOT_FOUND
                    } else {
                        MpvUnavailableReason.LOAD_FAILED
                    },
                guidance =
                    "No compatible user-provided libmpv was found. Install or build libmpv separately, " +
                        "then pass an absolute path with MpvRuntimeSource.ExplicitPath.",
            )
        }

        private fun ensureNativeNumericLocale() {
            synchronized(nativeLocaleLock) {
                val linker = Linker.nativeLinker()
                val setLocale =
                    linker
                        .defaultLookup()
                        .find("setlocale")
                        .orElseThrow {
                            MpvLoadFailure(
                                MpvUnavailableReason.LOAD_FAILED,
                                "The platform C runtime does not expose setlocale required by libmpv.",
                            )
                        }
                val handle =
                    linker.downcallHandle(
                        setLocale,
                        FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                        ),
                    )
                Arena.ofConfined().use { callArena ->
                    val result =
                        handle.invokeWithArguments(
                            nativeNumericLocaleCategory(System.getProperty("os.name", "")),
                            callArena.allocateFrom("C"),
                        ) as MemorySegment
                    if (result.address() == 0L) {
                        throw MpvLoadFailure(
                            MpvUnavailableReason.LOAD_FAILED,
                            "The platform C runtime rejected the numeric locale required by libmpv.",
                        )
                    }
                }
            }
        }

        private fun MpvLibrarySource.candidates(): List<MpvLibrarySource> =
            when (this) {
                MpvLibrarySource.Automatic -> automaticCandidates()
                MpvLibrarySource.Bundled -> error("Bundled must be resolved before loading.")
                else -> listOf(this)
            }

        private fun automaticCandidates(): List<MpvLibrarySource> {
            val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
            val candidates = mutableListOf<MpvLibrarySource>()
            when {
                os.contains("mac") || os.contains("darwin") -> {
                    addExisting(candidates, Path.of("/opt/homebrew/lib/libmpv.dylib"))
                    addExisting(candidates, Path.of("/opt/homebrew/opt/mpv/lib/libmpv.dylib"))
                    addExisting(candidates, Path.of("/usr/local/lib/libmpv.dylib"))
                    addExisting(candidates, Path.of("/opt/local/lib/libmpv.dylib"))
                    candidates += MpvLibrarySource.SystemLibrary("mpv")
                }
                os.contains("win") -> {
                    candidates += MpvLibrarySource.SystemLibrary("mpv-2")
                    candidates += MpvLibrarySource.SystemLibrary("libmpv-2")
                }
                else -> {
                    linuxLibraryDirectories().forEach { directory ->
                        addExisting(candidates, directory.resolve("libmpv.so.2"))
                        addExisting(candidates, directory.resolve("libmpv.so.1"))
                    }
                    candidates += MpvLibrarySource.SystemLibrary("mpv")
                }
            }
            return candidates
        }

        private fun linuxLibraryDirectories(): List<Path> {
            val architecture = System.getProperty("os.arch", "").lowercase(Locale.ROOT)
            val multiArchDirectory =
                when (architecture) {
                    "amd64", "x86_64" -> "/usr/lib/x86_64-linux-gnu"
                    "aarch64", "arm64" -> "/usr/lib/aarch64-linux-gnu"
                    else -> null
                }
            return buildList {
                multiArchDirectory?.let { add(Path.of(it)) }
                add(Path.of("/usr/local/lib"))
                add(Path.of("/usr/lib64"))
                add(Path.of("/usr/lib"))
            }
        }

        private fun addExisting(
            candidates: MutableList<MpvLibrarySource>,
            path: Path,
        ) {
            if (Files.isRegularFile(path)) candidates += MpvLibrarySource.ExplicitPath(path)
        }

        private fun setRenderParam(
            params: MemorySegment,
            index: Long,
            type: Int,
            data: MemorySegment,
        ) {
            val offset = RENDER_PARAM_LAYOUT.byteSize() * index
            params.set(ValueLayout.JAVA_INT, offset, type)
            params.set(ValueLayout.ADDRESS, offset + 8L, data)
        }

        private fun writeNativeNumber(
            segment: MemorySegment,
            layout: ValueLayout,
            value: Long,
        ) {
            when (layout.carrier()) {
                Int::class.javaPrimitiveType -> segment.set(ValueLayout.JAVA_INT, 0, value.toInt())
                Long::class.javaPrimitiveType -> segment.set(ValueLayout.JAVA_LONG, 0, value)
                else -> error("Unsupported native integer carrier: ${layout.carrier().name}")
            }
        }
    }
}

internal fun shouldRetainMpvLibraryForProcessLifetime(
    osName: String,
    embeddedMacVkApiVersion: Int,
): Boolean {
    val normalizedOsName = osName.lowercase(Locale.ROOT)
    return embeddedMacVkApiVersion > 0 &&
        (normalizedOsName.contains("mac") || normalizedOsName.contains("darwin"))
}

internal fun nativeNumericLocaleCategory(osName: String): Int {
    val normalized = osName.lowercase(Locale.ROOT)
    return when {
        normalized.contains("linux") -> 1
        normalized.contains("mac") ||
            normalized.contains("darwin") ||
            normalized.contains("win") -> 4
        else ->
            throw MpvLoadFailure(
                MpvUnavailableReason.UNSUPPORTED_PLATFORM,
                "The platform C numeric-locale category is unsupported.",
            )
    }
}

internal class LibMpvEngine(
    private val library: LibMpvLibrary,
    private val handle: MemorySegment,
    renderContext: MemorySegment?,
) : AutoCloseable {
    @Volatile
    private var closed = false

    private var softwareRenderContext: MemorySegment? = renderContext
    private var externalRenderContextActive = false

    fun command(vararg arguments: String) {
        checkOpen()
        library.command(handle, arguments.toList())
    }

    fun setProperty(
        name: String,
        value: String,
    ) {
        checkOpen()
        library.setProperty(handle, name, value)
    }

    fun setStringListProperty(
        name: String,
        values: List<String>,
    ) {
        checkOpen()
        library.setStringListProperty(handle, name, values)
    }

    fun getProperty(name: String): String? {
        checkOpen()
        return library.getProperty(handle, name)
    }

    fun embeddedMacVkPresentedFrames(nativeView: Long): Long? {
        checkOpen()
        return library.embeddedMacVkPresentedFrames(nativeView)
    }

    fun waitEvent(timeoutSeconds: Double): NativeMpvEvent {
        checkOpen()
        return library.waitEvent(handle, max(0.0, timeoutSeconds))
    }

    fun wakeup() {
        if (!closed) library.wakeup(handle)
    }

    fun render(
        width: Int,
        height: Int,
        rowBytes: Long,
        pixelsAddress: Long,
    ) {
        checkOpen()
        check(!externalRenderContextActive) { "A native libmpv render context is active." }
        val renderContext =
            softwareRenderContext ?: library.createSoftwareRenderContext(handle).also {
                softwareRenderContext = it
            }
        library.render(renderContext, width, height, rowBytes, pixelsAddress)
    }

    /**
     * Releases the software renderer before an embedded native renderer creates its own libmpv
     * render context. libmpv permits only one render context per player handle at a time.
     */
    fun beginExternalRendering(): ExternalMpvRenderTarget {
        checkOpen()
        check(!externalRenderContextActive) { "A native libmpv render context is already active." }
        softwareRenderContext?.let(library::freeRenderContext)
        softwareRenderContext = null
        externalRenderContextActive = true
        return ExternalMpvRenderTarget(
            mpvHandle = handle.address(),
            libraryLoadName = library.loadedFrom.nativeLoadName(),
        )
    }

    /** Creates the software render context after a window-owned VO has been stopped. */
    fun restoreSoftwareRendering() {
        checkOpen()
        check(!externalRenderContextActive) { "A native libmpv render context is active." }
        if (softwareRenderContext == null) {
            softwareRenderContext = library.createSoftwareRenderContext(handle)
        }
    }

    /** Must be called only after the native render context has been destroyed. */
    fun endExternalRendering(restoreSoftwareRenderer: Boolean) {
        if (!externalRenderContextActive) return
        externalRenderContextActive = false
        if (restoreSoftwareRenderer && !closed) {
            softwareRenderContext = library.createSoftwareRenderContext(handle)
        }
    }

    fun errorMessage(errorCode: Int): String = library.errorMessage(errorCode)

    private fun checkOpen() = check(!closed) { "The libmpv engine has been closed." }

    override fun close() {
        if (closed) return
        closed = true
        check(!externalRenderContextActive) {
            "The native libmpv render context must be detached before closing the player."
        }
        softwareRenderContext?.let(library::freeRenderContext)
        softwareRenderContext = null
        library.terminate(handle)
        library.close()
    }
}

internal data class ExternalMpvRenderTarget(
    val mpvHandle: Long,
    val libraryLoadName: String,
)

private fun MpvLibrarySource.nativeLoadName(): String =
    when (this) {
        is MpvLibrarySource.ExplicitPath -> path.toString()
        is MpvLibrarySource.SystemLibrary ->
            if (System.getProperty("os.name", "").lowercase(Locale.ROOT).let {
                    it.contains("mac") || it.contains("darwin")
                }
            ) {
                name.takeIf { it.endsWith(".dylib") } ?: "lib$name.dylib"
            } else {
                name
            }
        MpvLibrarySource.Automatic,
        MpvLibrarySource.Bundled,
        -> error("The libmpv source must be resolved before native rendering.")
    }
