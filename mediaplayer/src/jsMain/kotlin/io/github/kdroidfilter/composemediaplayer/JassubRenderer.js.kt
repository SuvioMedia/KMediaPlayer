@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsModule
import kotlin.js.JsName
import kotlin.js.JsNonModule

@JsModule("jassub")
@JsNonModule
@JsName("default")
private external class JassubJsRenderer(options: JsAny) : JsAny

internal actual fun createJassubRenderer(options: JsAny): JsAny =
    JassubJsRenderer(options)
