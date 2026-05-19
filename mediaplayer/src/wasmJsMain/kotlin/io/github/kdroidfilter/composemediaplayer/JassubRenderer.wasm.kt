@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsModule
import kotlin.js.JsName

@JsModule("jassub")
@JsName("default")
private external class JassubWasmRenderer(
    options: JsAny,
) : JsAny

internal fun createJassubRenderer(options: JsAny): JsAny = JassubWasmRenderer(options)
