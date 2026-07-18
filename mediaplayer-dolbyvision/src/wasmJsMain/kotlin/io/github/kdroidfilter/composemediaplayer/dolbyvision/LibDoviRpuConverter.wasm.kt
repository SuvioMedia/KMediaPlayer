@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import kotlinx.coroutines.CompletableDeferred
import kotlin.js.JsArray
import kotlin.js.JsNumber
import kotlin.js.toInt
import kotlin.js.toJsNumber

/** Browser location of the pinned Rust libdovi WebAssembly module packaged by this artifact. */
object LibDoviWasmConfiguration {
    var moduleUrl: String = "composemediaplayer_libdovi.wasm"
        set(value) {
            require(value.isNotBlank()) { "moduleUrl must not be blank." }
            field = value
        }
}

/** Lazy browser binding for the pinned Rust libdovi module. */
actual class LibDoviRpuConverter actual constructor() : DolbyVisionRpuConverter {
    private var runtime: JsAny? = null
    private var preparation: CompletableDeferred<JsAny?>? = null
    private var preparationFailure: String? = null
    private var closed = false

    actual override val isAvailable: Boolean
        get() = !closed && runtime != null

    actual override suspend fun prepare(): Boolean {
        if (closed) return false
        if (runtime != null) return true
        val pending =
            preparation ?: CompletableDeferred<JsAny?>().also { created ->
                preparation = created
                loadLibDoviWasm(
                    moduleUrl = LibDoviWasmConfiguration.moduleUrl,
                    onReady = { loaded ->
                        if (!closed) runtime = loaded
                        created.complete(loaded.takeUnless { closed })
                    },
                    onError = { message ->
                        preparationFailure = message
                        created.complete(null)
                    },
                )
            }
        return pending.await() != null && !closed
    }

    actual override suspend fun convertProfile7To81(rpuNalUnit: ByteArray): DolbyVisionRpuConversionResult {
        if (rpuNalUnit.isEmpty()) {
            return DolbyVisionRpuConversionResult.Invalid("The Dolby Vision RPU NAL unit is empty.")
        }
        if (!prepare()) {
            return DolbyVisionRpuConversionResult.Unavailable(
                preparationFailure ?: "The libdovi Wasm module is unavailable in this browser runtime.",
            )
        }
        val activeRuntime = runtime ?: return DolbyVisionRpuConversionResult.Unavailable("libdovi Wasm was closed.")
        val input = JsArray<JsNumber>()
        rpuNalUnit.forEachIndexed { index, byte ->
            input[index] = (byte.toInt() and UNSIGNED_BYTE_MASK).toJsNumber()
        }
        return runCatching { convertWithLibDoviWasm(activeRuntime, input) }
            .fold(
                onSuccess = { converted ->
                    if (converted == null) {
                        DolbyVisionRpuConversionResult.Invalid(
                            "libdovi rejected the RPU or it was not Dolby Vision Profile 7.",
                        )
                    } else if (converted.length !in 1..MAXIMUM_RPU_BYTES) {
                        DolbyVisionRpuConversionResult.Invalid("libdovi returned an invalid RPU length.")
                    } else {
                        DolbyVisionRpuConversionResult.Success(
                            ByteArray(converted.length) { index -> converted[index]?.toInt()?.toByte() ?: 0 },
                        )
                    }
                },
                onFailure = { error ->
                    DolbyVisionRpuConversionResult.Invalid(
                        "libdovi Wasm conversion failed: ${error.message ?: error::class.simpleName}",
                    )
                },
            )
    }

    actual fun close() {
        closed = true
        runtime = null
        preparation?.takeUnless { it.isCompleted }?.complete(null)
    }

    private companion object {
        const val MAXIMUM_RPU_BYTES = 4 * 1024 * 1024
        const val UNSIGNED_BYTE_MASK = 0xff
    }
}

@Suppress("UNUSED_PARAMETER")
private fun loadLibDoviWasm(
    moduleUrl: String,
    onReady: (JsAny) -> Unit,
    onError: (String) -> Unit,
): Unit =
    js(
        """
        (function() {
            const resolvedUrl = moduleUrl === "composemediaplayer_libdovi.wasm"
                ? new URL("./composemediaplayer_libdovi.wasm", import.meta.url)
                : new URL(moduleUrl, document.baseURI);
            fetch(resolvedUrl)
                .then(function(response) {
                    if (!response.ok) throw new Error("HTTP " + response.status + " while loading " + resolvedUrl);
                    return response.arrayBuffer();
                })
                .then(function(bytes) { return WebAssembly.instantiate(bytes, {}); })
                .then(function(result) {
                    const instance = result.instance || result;
                    const exports = instance.exports;
                    if (!(exports.memory instanceof WebAssembly.Memory) ||
                        typeof exports.cmp_dovi_allocate_buffer !== "function" ||
                        typeof exports.cmp_dovi_convert_profile7_to81 !== "function" ||
                        typeof exports.cmp_dovi_free_buffer !== "function") {
                        throw new Error("The libdovi Wasm module has an incompatible export table.");
                    }
                    onReady(instance);
                })
                .catch(function(error) {
                    onError(error && error.message ? String(error.message) : String(error));
                });
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun convertWithLibDoviWasm(
    runtime: JsAny,
    input: JsArray<JsNumber>,
): JsArray<JsNumber>? =
    js(
        """
        (function() {
            const exports = runtime.exports;
            const inputLength = input.length >>> 0;
            const lengthSlotSize = 4;
            const inputPointer = exports.cmp_dovi_allocate_buffer(inputLength) >>> 0;
            const lengthPointer = exports.cmp_dovi_allocate_buffer(lengthSlotSize) >>> 0;
            if (inputPointer === 0 || lengthPointer === 0) {
                if (inputPointer !== 0) exports.cmp_dovi_free_buffer(inputPointer, inputLength);
                if (lengthPointer !== 0) exports.cmp_dovi_free_buffer(lengthPointer, lengthSlotSize);
                throw new Error("libdovi Wasm input allocation failed.");
            }
            let outputPointer = 0;
            let outputLength = 0;
            try {
                new Uint8Array(exports.memory.buffer, inputPointer, inputLength).set(input);
                new DataView(exports.memory.buffer).setUint32(lengthPointer, 0, true);
                outputPointer = exports.cmp_dovi_convert_profile7_to81(
                    inputPointer,
                    inputLength,
                    lengthPointer
                ) >>> 0;
                outputLength = new DataView(exports.memory.buffer).getUint32(lengthPointer, true) >>> 0;
                if (outputPointer === 0 || outputLength === 0 || outputLength > 4194304) return null;
                return Array.from(new Uint8Array(exports.memory.buffer, outputPointer, outputLength));
            } finally {
                exports.cmp_dovi_free_buffer(inputPointer, inputLength);
                exports.cmp_dovi_free_buffer(lengthPointer, lengthSlotSize);
                if (outputPointer !== 0) exports.cmp_dovi_free_buffer(outputPointer, outputLength);
            }
        })()
        """,
    )
