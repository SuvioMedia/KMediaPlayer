@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.UIKitView
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSCoder
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.SceneKit.SCNCamera
import platform.SceneKit.SCNCullModeFront
import platform.SceneKit.SCNGeometry
import platform.SceneKit.SCNGeometryElement
import platform.SceneKit.SCNGeometryPrimitiveTypeTriangles
import platform.SceneKit.SCNGeometrySource
import platform.SceneKit.SCNGeometrySourceSemanticTexcoord
import platform.SceneKit.SCNGeometrySourceSemanticVertex
import platform.SceneKit.SCNMaterial
import platform.SceneKit.SCNMatrix4MakeRotation
import platform.SceneKit.SCNMatrix4MakeScale
import platform.SceneKit.SCNMatrix4MakeTranslation
import platform.SceneKit.SCNMatrix4Mult
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNPlane
import platform.SceneKit.SCNScene
import platform.SceneKit.SCNVector3Make
import platform.SceneKit.SCNView
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewMeta
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val iosSurfaceLogger = TaggedLogger("iOSVideoPlayerSurface")

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VideoPlayerSurface(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
) {
    if (playerState is PreviewableVideoPlayerState) {
        VideoPlayerSurfacePreview(modifier = modifier, overlay = overlay)
        return
    }
    if (playerState is VideoPlayerSurfaceProvider) {
        playerState.RenderVideoPlayerSurface(
            modifier = modifier,
            contentScale = contentScale,
            overlay = overlay,
        )
        return
    }
    require(playerState is DefaultVideoPlayerState) {
        "Unsupported video player state: ${playerState::class}"
    }

    // Set pauseOnDispose to false to prevent pausing during screen rotation
    VideoPlayerSurfaceImpl(
        playerState,
        modifier,
        contentScale,
        overlay,
        isInFullscreenView = false,
        pauseOnDispose = false,
    )
}

@OptIn(ExperimentalForeignApi::class)
@Suppress("CyclomaticComplexMethod")
@Composable
fun VideoPlayerSurfaceImpl(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    isInFullscreenView: Boolean = false,
    pauseOnDispose: Boolean = true,
) {
    if (playerState is PreviewableVideoPlayerState) {
        VideoPlayerSurfacePreview(modifier = modifier, overlay = overlay)
        return
    }
    if (playerState is VideoPlayerSurfaceProvider) {
        playerState.RenderVideoPlayerSurface(
            modifier = modifier,
            contentScale = contentScale,
            overlay = overlay,
        )
        return
    }
    require(playerState is DefaultVideoPlayerState) {
        "Unsupported video player state: ${playerState::class}"
    }
    DefaultVideoPlayerSurfaceImpl(
        playerState = playerState,
        modifier = modifier,
        contentScale = contentScale,
        overlay = overlay,
        isInFullscreenView = isInFullscreenView,
        pauseOnDispose = pauseOnDispose,
    )
}

@OptIn(ExperimentalForeignApi::class)
@Suppress("CyclomaticComplexMethod")
@Composable
private fun DefaultVideoPlayerSurfaceImpl(
    playerState: DefaultVideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    isInFullscreenView: Boolean = false,
    pauseOnDispose: Boolean = true,
) {
    // Cleanup when deleting the view
    DisposableEffect(Unit) {
        onDispose {
            iosSurfaceLogger.d { "[VideoPlayerSurface] Disposing" }
            // Only pause if pauseOnDispose is true (prevents pausing during rotation or fullscreen transitions)
            if (pauseOnDispose) {
                iosSurfaceLogger.d { "[VideoPlayerSurface] Pausing on dispose" }
                playerState.pause()
            } else {
                iosSurfaceLogger.d { "[VideoPlayerSurface] Not pausing on dispose (rotation or fullscreen transition)" }
            }
        }
    }

    val currentPlayer = playerState.player
    val usesProjectionRenderer =
        playerState.projection.usesIosSceneKitProjectionRenderer(playerState.projectionTextureCrop)
    IosProjectionDeviceMotionEffect(
        playerState = playerState,
        enabled = usesProjectionRenderer && playerState.hasMedia,
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (playerState.hasMedia) {
            key(usesProjectionRenderer) {
                UIKitView(
                    modifier =
                        contentScale.toCanvasModifier(
                            aspectRatio = playerState.aspectRatio,
                            width = playerState.metadata.width,
                            height = playerState.metadata.height,
                        ),
                    factory = {
                        if (usesProjectionRenderer) {
                            ProjectionPlayerUIView(frame = cValue<CGRect>()).apply {
                                configure(
                                    player = currentPlayer,
                                    projection = playerState.projection,
                                    projectionView = playerState.projectionView,
                                    textureCrop = playerState.projectionTextureCrop,
                                    contentScale = contentScale,
                                )
                            }
                        } else {
                            PlayerUIView(frame = cValue<CGRect>()).apply {
                                player = currentPlayer
                                backgroundColor = UIColor.blackColor
                                clipsToBounds = true

                                val videoPlayerLayer = layer as? AVPlayerLayer
                                if (videoPlayerLayer != null) {
                                    playerState.bindPlayerLayer(videoPlayerLayer, isInFullscreenView)
                                }
                            }
                        }
                    },
                    update = { view ->
                        when (view) {
                            is ProjectionPlayerUIView -> {
                                view.configure(
                                    player = currentPlayer,
                                    projection = playerState.projection,
                                    projectionView = playerState.projectionView,
                                    textureCrop = playerState.projectionTextureCrop,
                                    contentScale = contentScale,
                                )
                                view.hidden = !playerState.hasMedia
                            }
                            is PlayerUIView -> {
                                view.player = currentPlayer
                                (view.layer as? AVPlayerLayer)?.let { layer ->
                                    playerState.bindPlayerLayer(layer, isInFullscreenView)
                                }

                                // Hide or show the view depending on the presence of media
                                view.hidden = !playerState.hasMedia

                                // Update the videoGravity when contentScale changes
                                val videoGravity =
                                    when (contentScale) {
                                        ContentScale.Crop,
                                        ContentScale.FillHeight,
                                        -> AVLayerVideoGravityResizeAspectFill
                                        ContentScale.FillWidth -> AVLayerVideoGravityResizeAspectFill
                                        ContentScale.FillBounds -> AVLayerVideoGravityResize // no aspect-ratio
                                        ContentScale.Fit,
                                        ContentScale.Inside,
                                        -> AVLayerVideoGravityResizeAspect

                                        else -> AVLayerVideoGravityResizeAspect
                                    }
                                view.videoGravity = videoGravity

                                iosSurfaceLogger.d {
                                    "View configured with contentScale: $contentScale, videoGravity: $videoGravity"
                                }
                            }
                        }
                    },
                    onRelease = { view ->
                        when (view) {
                            is ProjectionPlayerUIView ->
                                view.configure(
                                    player = null,
                                    projection = VideoProjectionSettings(),
                                    projectionView = VideoProjectionViewSettings(),
                                    textureCrop = VideoTextureCrop(),
                                    contentScale = ContentScale.Fit,
                                )
                            is PlayerUIView -> {
                                (view.layer as? AVPlayerLayer)?.let(playerState::releasePlayerLayer)
                                view.player = null
                            }
                        }
                    },
                )
            }

            // Add Compose-based subtitle layer
            if (playerState.subtitlesEnabled &&
                playerState.currentSubtitleTrack != null &&
                playerState.currentSubtitleTrack?.isEmbedded != true
            ) {
                val currentTime =
                    if (playerState.userDragging) {
                        playerState.duration *
                            (playerState.sliderPos / VideoPlayerState.SLIDER_SCALE).toDouble().coerceIn(0.0, 1.0)
                    } else {
                        playerState.preciseCurrentTime
                    } + playerState.subtitleOffset

                ComposeSubtitleLayer(
                    currentTime = currentTime,
                    duration = playerState.duration,
                    isPlaying = playerState.isPlaying,
                    subtitleTrack = playerState.currentSubtitleTrack,
                    subtitlesEnabled = playerState.subtitlesEnabled,
                    textStyle = playerState.subtitleTextStyle,
                    backgroundColor = playerState.subtitleBackgroundColor,
                )
            }
        }

        // Render the overlay content on top of the video with fillMaxSize modifier
        // to ensure it takes the full height of the parent Box
        Box(modifier = Modifier.fillMaxSize()) {
            overlay()
        }
    }

    // Handle fullscreen mode
    if (playerState.isFullscreen && !isInFullscreenView) {
        openFullscreenView(playerState) { state, mod, inFullscreen ->
            // Set pauseOnDispose to false to prevent pausing during fullscreen transitions
            VideoPlayerSurfaceImpl(state, mod, contentScale, overlay, inFullscreen, pauseOnDispose = false)
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class PlayerUIView : UIView {
    companion object : UIViewMeta() {
        override fun layerClass(): ObjCClass = AVPlayerLayer
    }

    constructor(frame: CValue<CGRect>) : super(frame)
    constructor(coder: NSCoder) : super(coder)

    var player: AVPlayer?
        get() = (layer as? AVPlayerLayer)?.player
        set(value) {
            (layer as? AVPlayerLayer)?.player = value
        }

    var videoGravity: String?
        get() = (layer as? AVPlayerLayer)?.videoGravity
        set(value) {
            (layer as? AVPlayerLayer)?.videoGravity = value
        }
}

@OptIn(ExperimentalForeignApi::class)
private class ProjectionPlayerUIView : UIView {
    private val leftEyeView: ProjectionEyeSceneView
    private val rightEyeView: ProjectionEyeSceneView
    private var stereo = false

    constructor(frame: CValue<CGRect>) : super(frame) {
        leftEyeView = ProjectionEyeSceneView()
        rightEyeView = ProjectionEyeSceneView()
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
        addSubview(leftEyeView.sceneView)
        addSubview(rightEyeView.sceneView)
        rightEyeView.sceneView.hidden = true
    }

    constructor(coder: NSCoder) : super(coder) {
        leftEyeView = ProjectionEyeSceneView()
        rightEyeView = ProjectionEyeSceneView()
    }

    fun configure(
        player: AVPlayer?,
        projection: VideoProjectionSettings,
        projectionView: VideoProjectionViewSettings,
        textureCrop: VideoTextureCrop,
        contentScale: ContentScale,
    ) {
        val normalized = projection.normalized()
        val plan =
            normalized.toVideoProjectionRenderPlan(
                VideoProjectionRenderOptions(textureCrop = textureCrop),
            )
        stereo = plan.stereo
        leftEyeView.configure(
            player = player,
            projection = normalized,
            textureWindow = plan.leftEyeTexture,
            projectionView = projectionView,
            contentScale = contentScale,
        )
        rightEyeView.configure(
            player = player,
            projection = normalized,
            textureWindow = plan.rightEyeTexture,
            projectionView = projectionView,
            contentScale = contentScale,
        )
        rightEyeView.sceneView.hidden = !stereo
        setNeedsLayout()
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val width = bounds.useContents { size.width }
        val height = bounds.useContents { size.height }
        if (stereo) {
            val leftWidth = width / 2.0
            leftEyeView.sceneView.setFrame(CGRectMake(0.0, 0.0, leftWidth, height))
            rightEyeView.sceneView.setFrame(CGRectMake(leftWidth, 0.0, width - leftWidth, height))
        } else {
            leftEyeView.sceneView.setFrame(bounds)
            rightEyeView.sceneView.setFrame(CGRectMake(0.0, 0.0, 0.0, 0.0))
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class ProjectionEyeSceneView {
    val sceneView =
        SCNView(frame = cValue<CGRect>()).apply {
            backgroundColor = UIColor.blackColor
            allowsCameraControl = false
            playing = true
            scene = SCNScene()
        }

    private val scene = sceneView.scene ?: SCNScene()
    private val cameraNode = SCNNode()
    private var contentNode: SCNNode? = null
    private var lastGeometryKey: String? = null

    init {
        sceneView.scene = scene
        cameraNode.camera =
            SCNCamera().apply {
                fieldOfView = DEFAULT_IOS_CAMERA_FOV_DEGREES
            }
        cameraNode.position = SCNVector3Make(0f, 0f, 0f)
        scene.rootNode.addChildNode(cameraNode)
        sceneView.pointOfView = cameraNode
    }

    fun configure(
        player: AVPlayer?,
        projection: VideoProjectionSettings,
        textureWindow: VideoTextureWindow,
        projectionView: VideoProjectionViewSettings,
        contentScale: ContentScale,
    ) {
        val geometryKey =
            "${projection.projectionType}:${projection.fovDegrees}:${projection.aspectRatio}:" +
                "${textureWindow.rotation}:$contentScale"
        if (geometryKey != lastGeometryKey) {
            contentNode?.removeFromParentNode()
            contentNode = createContentNode(projection, contentScale)
            contentNode?.let(scene.rootNode::addChildNode)
            lastGeometryKey = geometryKey
        }
        val material = contentNode?.geometry?.firstMaterial ?: return
        material.diffuse.contents = player
        material.diffuse.contentsTransform = textureWindow.toContentsTransform()
        material.diffuse.mipFilter = 1L
        material.diffuse.minificationFilter = 1L
        material.diffuse.magnificationFilter = 1L
        cameraNode.eulerAngles = projectionView.normalized().toCameraEulerAngles()
    }

    private fun createContentNode(
        projection: VideoProjectionSettings,
        contentScale: ContentScale,
    ): SCNNode {
        val material =
            SCNMaterial().apply {
                doubleSided = true
                cullMode = SCNCullModeFront
                diffuse.contents = UIColor.blackColor
            }
        val geometry = projection.createProjectionGeometry(contentScale).apply { firstMaterial = material }
        return SCNNode.nodeWithGeometry(geometry).apply {
            position = SCNVector3Make(0f, 0f, projection.contentDistance())
        }
    }
}

private fun VideoProjectionSettings.createProjectionGeometry(contentScale: ContentScale): SCNGeometry =
    when (projectionType) {
        VideoProjectionType.Equirect180,
        VideoProjectionType.Equirect360,
        -> createIosEquirectGeometry(fovDegrees)

        VideoProjectionType.Fisheye180,
        VideoProjectionType.Fisheye190,
        VideoProjectionType.Fisheye200,
        VideoProjectionType.Fisheye220,
        -> createIosFisheyeGeometry(fovDegrees)

        VideoProjectionType.Eac360 -> createIosEacGeometry()

        VideoProjectionType.Flat -> {
            val aspectRatio = aspectRatio.toDouble().coerceAtLeast(0.01)
            val planeHeight =
                when (contentScale) {
                    ContentScale.FillHeight,
                    ContentScale.FillBounds,
                    -> IOS_FLAT_PLANE_BASE_WIDTH / aspectRatio
                    else -> IOS_FLAT_PLANE_BASE_WIDTH / aspectRatio
                }
            SCNPlane.planeWithWidth(IOS_FLAT_PLANE_BASE_WIDTH, planeHeight)
        }
    }

private data class IosProjectionVertex(
    val x: Float,
    val y: Float,
    val z: Float,
    val u: Double,
    val v: Double,
)

private fun createIosEquirectGeometry(fovDegrees: Float): SCNGeometry {
    val vertices = mutableListOf<IosProjectionVertex>()
    val horizontalFov = fovDegrees.coerceAtLeast(1f).toDouble().toRadiansDouble()
    for (row in 0 until IOS_EQUIRECT_ROWS) {
        val pitch0 = -HALF_PI_RADIANS + PI * row.toDouble() / IOS_EQUIRECT_ROWS.toDouble()
        val pitch1 = -HALF_PI_RADIANS + PI * (row + 1).toDouble() / IOS_EQUIRECT_ROWS.toDouble()
        for (column in 0 until IOS_EQUIRECT_COLUMNS) {
            val yaw0 = -horizontalFov * 0.5 + horizontalFov * column.toDouble() / IOS_EQUIRECT_COLUMNS.toDouble()
            val yaw1 = -horizontalFov * 0.5 + horizontalFov * (column + 1).toDouble() / IOS_EQUIRECT_COLUMNS.toDouble()
            vertices.addQuad(
                equirectVertex(yaw0, pitch0, horizontalFov),
                equirectVertex(yaw1, pitch0, horizontalFov),
                equirectVertex(yaw0, pitch1, horizontalFov),
                equirectVertex(yaw1, pitch1, horizontalFov),
            )
        }
    }
    return createIosProjectionGeometry(vertices)
}

private fun equirectVertex(
    yaw: Double,
    pitch: Double,
    horizontalFov: Double,
): IosProjectionVertex {
    val cosPitch = cos(pitch)
    return IosProjectionVertex(
        x = (sin(yaw) * cosPitch * IOS_PROJECTION_SPHERE_RADIUS).toFloat(),
        y = (sin(pitch) * IOS_PROJECTION_SPHERE_RADIUS).toFloat(),
        z = (-cos(yaw) * cosPitch * IOS_PROJECTION_SPHERE_RADIUS).toFloat(),
        u = yaw / horizontalFov + 0.5,
        v = 0.5 - pitch / PI,
    )
}

private fun createIosFisheyeGeometry(fovDegrees: Float): SCNGeometry {
    val vertices = mutableListOf<IosProjectionVertex>()
    val maxTheta = fovDegrees.coerceAtLeast(1f).toDouble().toRadiansDouble() * 0.5
    for (row in 0 until IOS_FISHEYE_ROWS) {
        val theta0 = maxTheta * row.toDouble() / IOS_FISHEYE_ROWS.toDouble()
        val theta1 = maxTheta * (row + 1).toDouble() / IOS_FISHEYE_ROWS.toDouble()
        for (column in 0 until IOS_FISHEYE_COLUMNS) {
            val phi0 = -PI + TWO_PI * column.toDouble() / IOS_FISHEYE_COLUMNS.toDouble()
            val phi1 = -PI + TWO_PI * (column + 1).toDouble() / IOS_FISHEYE_COLUMNS.toDouble()
            vertices.addQuad(
                fisheyeVertex(theta0, phi0, maxTheta),
                fisheyeVertex(theta0, phi1, maxTheta),
                fisheyeVertex(theta1, phi0, maxTheta),
                fisheyeVertex(theta1, phi1, maxTheta),
            )
        }
    }
    return createIosProjectionGeometry(vertices)
}

private fun fisheyeVertex(
    theta: Double,
    phi: Double,
    maxTheta: Double,
): IosProjectionVertex {
    val sinTheta = sin(theta)
    val radius = if (maxTheta <= 0.0) 0.0 else theta / maxTheta * 0.5
    return IosProjectionVertex(
        x = (sinTheta * cos(phi) * IOS_PROJECTION_SPHERE_RADIUS).toFloat(),
        y = (sinTheta * sin(phi) * IOS_PROJECTION_SPHERE_RADIUS).toFloat(),
        z = (-cos(theta) * IOS_PROJECTION_SPHERE_RADIUS).toFloat(),
        u = 0.5 + cos(phi) * radius,
        v = 0.5 - sin(phi) * radius,
    )
}

private fun createIosEacGeometry(): SCNGeometry {
    val vertices = mutableListOf<IosProjectionVertex>()
    vertices.addEacFace(cellX = 0.0, cellY = 0.0) { sc, tc -> IosDirection(sc, tc, -1.0) }
    vertices.addEacFace(cellX = 1.0, cellY = 0.0) { sc, tc -> IosDirection(1.0, tc, sc) }
    vertices.addEacFace(cellX = 2.0, cellY = 0.0) { sc, tc -> IosDirection(-sc, tc, 1.0) }
    vertices.addEacFace(cellX = 0.0, cellY = 1.0) { sc, tc -> IosDirection(-1.0, tc, -sc) }
    vertices.addEacFace(cellX = 1.0, cellY = 1.0) { sc, tc -> IosDirection(sc, 1.0, tc) }
    vertices.addEacFace(cellX = 2.0, cellY = 1.0) { sc, tc -> IosDirection(sc, -1.0, -tc) }
    return createIosProjectionGeometry(vertices)
}

private data class IosDirection(
    val x: Double,
    val y: Double,
    val z: Double,
)

private fun MutableList<IosProjectionVertex>.addEacFace(
    cellX: Double,
    cellY: Double,
    directionFor: (Double, Double) -> IosDirection,
) {
    for (row in 0 until IOS_EAC_FACE_SEGMENTS) {
        val tc0 = -1.0 + 2.0 * row.toDouble() / IOS_EAC_FACE_SEGMENTS.toDouble()
        val tc1 = -1.0 + 2.0 * (row + 1).toDouble() / IOS_EAC_FACE_SEGMENTS.toDouble()
        for (column in 0 until IOS_EAC_FACE_SEGMENTS) {
            val sc0 = -1.0 + 2.0 * column.toDouble() / IOS_EAC_FACE_SEGMENTS.toDouble()
            val sc1 = -1.0 + 2.0 * (column + 1).toDouble() / IOS_EAC_FACE_SEGMENTS.toDouble()
            addQuad(
                eacVertex(sc0, tc0, cellX, cellY, directionFor),
                eacVertex(sc1, tc0, cellX, cellY, directionFor),
                eacVertex(sc0, tc1, cellX, cellY, directionFor),
                eacVertex(sc1, tc1, cellX, cellY, directionFor),
            )
        }
    }
}

private fun eacVertex(
    sc: Double,
    tc: Double,
    cellX: Double,
    cellY: Double,
    directionFor: (Double, Double) -> IosDirection,
): IosProjectionVertex {
    val direction = directionFor(sc, tc).normalized()
    val localU = 0.5 + atan(sc) / HALF_PI_RADIANS
    val localV = 0.5 - atan(tc) / HALF_PI_RADIANS
    return IosProjectionVertex(
        x = (direction.x * IOS_PROJECTION_SPHERE_RADIUS).toFloat(),
        y = (direction.y * IOS_PROJECTION_SPHERE_RADIUS).toFloat(),
        z = (direction.z * IOS_PROJECTION_SPHERE_RADIUS).toFloat(),
        u = (cellX + localU) / 3.0,
        v = (cellY + localV) / 2.0,
    )
}

private fun IosDirection.normalized(): IosDirection {
    val length = sqrt(x * x + y * y + z * z).coerceAtLeast(0.0001)
    return IosDirection(x / length, y / length, z / length)
}

private fun MutableList<IosProjectionVertex>.addQuad(
    topLeft: IosProjectionVertex,
    topRight: IosProjectionVertex,
    bottomLeft: IosProjectionVertex,
    bottomRight: IosProjectionVertex,
) {
    add(topLeft)
    add(bottomLeft)
    add(topRight)
    add(topRight)
    add(bottomLeft)
    add(bottomRight)
}

@OptIn(ExperimentalForeignApi::class)
private fun createIosProjectionGeometry(vertices: List<IosProjectionVertex>): SCNGeometry =
    SCNGeometry.geometryWithSources(
        sources =
            listOf(
                SCNGeometrySource.geometrySourceWithData(
                    data = vertices.positionData(),
                    semantic = SCNGeometrySourceSemanticVertex,
                    vectorCount = vertices.size.toLong(),
                    floatComponents = true,
                    componentsPerVector = POSITION_COMPONENTS_PER_VERTEX.toLong(),
                    bytesPerComponent = FLOAT_BYTES.toLong(),
                    dataOffset = 0,
                    dataStride = POSITION_COMPONENTS_PER_VERTEX.toLong() * FLOAT_BYTES.toLong(),
                ),
                SCNGeometrySource.geometrySourceWithData(
                    data = vertices.textureData(),
                    semantic = SCNGeometrySourceSemanticTexcoord,
                    vectorCount = vertices.size.toLong(),
                    floatComponents = true,
                    componentsPerVector = TEXTURE_COMPONENTS_PER_VERTEX.toLong(),
                    bytesPerComponent = FLOAT_BYTES.toLong(),
                    dataOffset = 0,
                    dataStride = TEXTURE_COMPONENTS_PER_VERTEX.toLong() * FLOAT_BYTES.toLong(),
                ),
            ),
        elements =
            listOf(
                SCNGeometryElement.geometryElementWithData(
                    data = vertices.indexData(),
                    primitiveType = SCNGeometryPrimitiveTypeTriangles,
                    primitiveCount = (vertices.size / TRIANGLE_VERTEX_COUNT).toLong(),
                    bytesPerIndex = INT_BYTES.toLong(),
                ),
            ),
    )

@OptIn(ExperimentalForeignApi::class)
private fun List<IosProjectionVertex>.positionData(): NSData {
    val data = FloatArray(size * POSITION_COMPONENTS_PER_VERTEX)
    forEachIndexed { index, vertex ->
        val offset = index * POSITION_COMPONENTS_PER_VERTEX
        data[offset] = vertex.x
        data[offset + 1] = vertex.y
        data[offset + 2] = vertex.z
    }
    return data.toNSData()
}

@OptIn(ExperimentalForeignApi::class)
private fun List<IosProjectionVertex>.textureData(): NSData {
    val data = FloatArray(size * TEXTURE_COMPONENTS_PER_VERTEX)
    forEachIndexed { index, vertex ->
        val offset = index * TEXTURE_COMPONENTS_PER_VERTEX
        data[offset] = vertex.u.toFloat()
        data[offset + 1] = vertex.v.toFloat()
    }
    return data.toNSData()
}

@OptIn(ExperimentalForeignApi::class)
private fun List<IosProjectionVertex>.indexData(): NSData {
    val data = IntArray(size) { it }
    return data.toNSData()
}

@OptIn(ExperimentalForeignApi::class)
private fun FloatArray.toNSData(): NSData =
    usePinned { pinned ->
        NSData.dataWithBytes(
            bytes = pinned.addressOf(0),
            length = (size * FLOAT_BYTES).toULong(),
        )
    }

@OptIn(ExperimentalForeignApi::class)
private fun IntArray.toNSData(): NSData =
    usePinned { pinned ->
        NSData.dataWithBytes(
            bytes = pinned.addressOf(0),
            length = (size * INT_BYTES).toULong(),
        )
    }

private fun VideoProjectionSettings.contentDistance(): Float =
    when (projectionType) {
        VideoProjectionType.Equirect180,
        VideoProjectionType.Equirect360,
        VideoProjectionType.Fisheye180,
        VideoProjectionType.Fisheye190,
        VideoProjectionType.Fisheye200,
        VideoProjectionType.Fisheye220,
        VideoProjectionType.Eac360,
        -> 0f

        else -> -IOS_FLAT_PLANE_DISTANCE
    }

private fun VideoTextureWindow.toContentsTransform() =
    SCNMatrix4Mult(
        when (rotation) {
            VideoProjectionRotation.None -> SCNMatrix4MakeScale(1f, 1f, 1f)
            VideoProjectionRotation.Rotate90 -> SCNMatrix4MakeRotation(HALF_PI_RADIANS.toFloat(), 0f, 0f, 1f)
            VideoProjectionRotation.Rotate180 -> SCNMatrix4MakeRotation(PI.toFloat(), 0f, 0f, 1f)
            VideoProjectionRotation.Rotate270 -> SCNMatrix4MakeRotation(-HALF_PI_RADIANS.toFloat(), 0f, 0f, 1f)
        },
        SCNMatrix4Mult(
            SCNMatrix4MakeTranslation(left, top, 0f),
            SCNMatrix4MakeScale(right - left, bottom - top, 1f),
        ),
    )

private fun VideoProjectionViewSettings.toCameraEulerAngles() =
    normalized().let { view ->
        SCNVector3Make(
            view.pitchDegrees.toRadians(),
            view.yawDegrees.toRadians(),
            view.rollDegrees.toRadians(),
        )
    }

private fun Float.toRadians(): Float = this * RADIANS_PER_DEGREE

private fun Double.toRadiansDouble(): Double = this * RADIANS_PER_DEGREE_DOUBLE

private const val DEFAULT_IOS_CAMERA_FOV_DEGREES = 95.0
private const val IOS_PROJECTION_SPHERE_RADIUS = 10.0
private const val IOS_EQUIRECT_COLUMNS = 96
private const val IOS_EQUIRECT_ROWS = 48
private const val IOS_FISHEYE_COLUMNS = 96
private const val IOS_FISHEYE_ROWS = 48
private const val IOS_EAC_FACE_SEGMENTS = 32
private const val IOS_FLAT_PLANE_BASE_WIDTH = 4.0
private const val IOS_FLAT_PLANE_DISTANCE = 2.4f
private const val HALF_PI_RADIANS = PI / 2.0
private val RADIANS_PER_DEGREE = (PI / DEGREES_PER_HALF_ROTATION).toFloat()
private val RADIANS_PER_DEGREE_DOUBLE = PI / DEGREES_PER_HALF_ROTATION
private const val DEGREES_PER_HALF_ROTATION = 180.0
private const val TWO_PI = PI * 2.0
private const val TRIANGLE_VERTEX_COUNT = 3
private const val POSITION_COMPONENTS_PER_VERTEX = 3
private const val TEXTURE_COMPONENTS_PER_VERTEX = 2
private const val FLOAT_BYTES = 4
private const val INT_BYTES = 4
