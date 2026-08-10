@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package io.github.kdroidfilter.composemediaplayer.mpv

import kotlinx.cinterop.cValue
import kotlinx.cinterop.objcPtr
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGSizeMake
import platform.QuartzCore.CAMetalLayer
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import kotlin.math.max

/** UIKit host for the application-owned Metal layer used by KMediaMpv's iosvk context. */
internal class IosMpvMetalSurface private constructor() {
    val view: UIView =
        UIView(frame = cValue<CGRect>()).apply {
            backgroundColor = UIColor.blackColor
            clipsToBounds = true
            opaque = true
            userInteractionEnabled = false
        }

    private val metalLayer =
        CAMetalLayer().apply {
            opaque = true
            delegate = view
            view.layer.addSublayer(this)
        }

    val nativeHandle: ULong
        get() = metalLayer.objcPtr().toLong().toULong()

    fun layout(
        pixelWidth: Int,
        pixelHeight: Int,
    ) {
        val scale = view.window?.screen?.scale ?: UIScreen.mainScreen.scale
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        metalLayer.frame = view.bounds
        metalLayer.contentsScale = scale
        metalLayer.drawableSize =
            CGSizeMake(
                width = max(MIN_DRAWABLE_DIMENSION, pixelWidth).toDouble(),
                height = max(MIN_DRAWABLE_DIMENSION, pixelHeight).toDouble(),
            )
        CATransaction.commit()
    }

    companion object {
        fun create(): IosMpvMetalSurface = IosMpvMetalSurface()

        private const val MIN_DRAWABLE_DIMENSION = 2
    }
}
