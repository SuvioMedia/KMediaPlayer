@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

private typealias WebProjectionMotionCallback = (Double, Double, Double) -> Unit

@Composable
internal fun WebProjectionDeviceMotionEffect(
    playerState: VideoPlayerState,
    enabled: Boolean,
) {
    val projection = playerState.projection
    val useDeviceMotion = enabled && playerState.projectionViewControlMode.usesDeviceMotionFor(projection)

    DisposableEffect(playerState, projection, useDeviceMotion) {
        if (!useDeviceMotion) {
            return@DisposableEffect onDispose { }
        }

        val listenerId =
            startWebProjectionDeviceMotionListener { yawDegrees, pitchDegrees, rollDegrees ->
                playerState.projectionView =
                    playerState.projectionView.copy(
                        yawDegrees = yawDegrees.toFloat(),
                        pitchDegrees = pitchDegrees.toFloat(),
                        rollDegrees = rollDegrees.toFloat(),
                    )
            }

        if (listenerId == WEB_PROJECTION_MOTION_UNAVAILABLE_ID) {
            webVideoLogger.w {
                "Device motion projection control requested, but DeviceOrientationEvent is unavailable."
            }
        }

        onDispose {
            stopWebProjectionDeviceMotionListener(listenerId)
        }
    }
}

@Suppress("UNUSED_PARAMETER")
private fun startWebProjectionDeviceMotionListener(onOrientation: WebProjectionMotionCallback): Int =
    js(
        """
        {
            if (typeof window === "undefined" || typeof window.DeviceOrientationEvent === "undefined") {
                return -1;
            }

            const win = window;
            if (!win.__composeMediaPlayerProjectionMotionListeners) {
                win.__composeMediaPlayerProjectionMotionListeners = {};
                win.__composeMediaPlayerProjectionMotionListenerId = 1;
            }

            const id = win.__composeMediaPlayerProjectionMotionListenerId++;
            let baseAlpha = null;
            let listening = false;
            const normalizeDegrees = function(value) {
                let normalized = value % 360;
                if (normalized > 180) normalized -= 360;
                if (normalized < -180) normalized += 360;
                return normalized;
            };
            const handler = function(event) {
                if (typeof event.alpha !== "number" ||
                    typeof event.beta !== "number" ||
                    typeof event.gamma !== "number") {
                    return;
                }
                if (baseAlpha === null) {
                    baseAlpha = event.alpha;
                }
                const yaw = -normalizeDegrees(event.alpha - baseAlpha);
                const pitch = event.beta;
                const roll = -event.gamma;
                onOrientation(yaw, pitch, roll);
            };
            const addOrientationListener = function() {
                if (listening) return;
                listening = true;
                win.addEventListener("deviceorientation", handler, true);
            };
            let permissionHandler = null;
            const removePermissionListeners = function() {
                if (!permissionHandler) return;
                win.removeEventListener("click", permissionHandler, true);
                win.removeEventListener("touchend", permissionHandler, true);
                win.removeEventListener("pointerup", permissionHandler, true);
                permissionHandler = null;
            };

            if (typeof win.DeviceOrientationEvent.requestPermission === "function") {
                permissionHandler = function() {
                    removePermissionListeners();
                    win.DeviceOrientationEvent.requestPermission()
                        .then(function(state) {
                            if (state === "granted") {
                                addOrientationListener();
                            }
                        })
                        .catch(function() {});
                };
                win.addEventListener("click", permissionHandler, true);
                win.addEventListener("touchend", permissionHandler, true);
                win.addEventListener("pointerup", permissionHandler, true);
            } else {
                addOrientationListener();
            }

            win.__composeMediaPlayerProjectionMotionListeners[id] = {
                handler: handler,
                removePermissionListeners: removePermissionListeners
            };
            return id;
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun stopWebProjectionDeviceMotionListener(listenerId: Int): Unit =
    js(
        """
        {
            if (listenerId < 0 || typeof window === "undefined") return;
            const listeners = window.__composeMediaPlayerProjectionMotionListeners;
            if (!listeners) return;
            const listener = listeners[listenerId];
            if (!listener) return;
            listener.removePermissionListeners();
            window.removeEventListener("deviceorientation", listener.handler, true);
            delete listeners[listenerId];
        }
        """,
    )

private const val WEB_PROJECTION_MOTION_UNAVAILABLE_ID = -1
