package sample.app.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hosts player sheets in the native-video overlay on JVM. Material3's desktop ModalBottomSheet
 * creates a separate Dialog whose Tao parent viewport is the virtual desktop instead of the
 * bounded NativeView. BottomSheetScaffold retains Material3 state, visuals, nested scrolling, and
 * swipe gestures without leaving the correctly sized overlay ComposeScene.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerModalBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!samplePlayerSheetsUseInlineHost) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            content = content,
        )
        return
    }

    val density = LocalDensity.current
    val sheetState =
        remember(density) {
            SheetState(
                skipPartiallyExpanded = true,
                positionalThreshold = { with(density) { PLAYER_SHEET_POSITIONAL_THRESHOLD.toPx() } },
                velocityThreshold = { with(density) { PLAYER_SHEET_VELOCITY_THRESHOLD.toPx() } },
                initialValue = SheetValue.Hidden,
                skipHiddenState = false,
            )
        }
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val scope = rememberCoroutineScope()
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val scrimVisibility by
        animateFloatAsState(
            targetValue = if (sheetState.targetValue == SheetValue.Hidden) 0f else 1f,
            animationSpec = tween(PLAYER_SHEET_SCRIM_ANIMATION_MILLIS),
            label = "player sheet scrim",
        )

    // BottomSheetScaffold installs its draggable anchors during layout. Calling show() before the
    // Expanded anchor exists updates only the target value and leaves the sheet at Hidden.
    if (sheetState.hasExpandedState) {
        LaunchedEffect(sheetState) {
            sheetState.show()
            snapshotFlow { sheetState.currentValue }
                .first { value -> value == SheetValue.Hidden }
            latestOnDismiss()
        }
    }

    BottomSheetScaffold(
        sheetContent = content,
        modifier = Modifier.fillMaxSize().zIndex(100f),
        scaffoldState = scaffoldState,
        sheetPeekHeight = 0.dp,
        sheetMaxWidth = BottomSheetDefaults.SheetMaxWidth,
        sheetShape = BottomSheetDefaults.ExpandedShape,
        sheetContainerColor = BottomSheetDefaults.ContainerColor,
        sheetTonalElevation = 0.dp,
        sheetShadowElevation = BottomSheetDefaults.Elevation,
        sheetDragHandle = { BottomSheetDefaults.DragHandle() },
        sheetSwipeEnabled = true,
        containerColor = Color.Transparent,
        snackbarHost = {},
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        BottomSheetDefaults.ScrimColor.copy(
                            alpha = BottomSheetDefaults.ScrimColor.alpha * scrimVisibility,
                        ),
                    ).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { scope.launch { sheetState.hide() } },
                    ),
        )
    }
}

private const val PLAYER_SHEET_SCRIM_ANIMATION_MILLIS = 240
private val PLAYER_SHEET_POSITIONAL_THRESHOLD = 56.dp
private val PLAYER_SHEET_VELOCITY_THRESHOLD = 125.dp
