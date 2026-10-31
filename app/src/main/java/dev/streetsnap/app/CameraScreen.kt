@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.streetsnap.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.hardware.display.DisplayManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Surface
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ViewPort
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.streetsnap.app.composition.CropTrial
import dev.streetsnap.app.composition.GuideDirection
import dev.streetsnap.app.composition.MoveGuide
import dev.streetsnap.app.composition.MoveGuides
import dev.streetsnap.app.composition.arrowAngleDeg
import dev.streetsnap.app.composition.arrowLabel
import dev.streetsnap.app.inference.InferenceStats
import dev.streetsnap.app.model.PoseModelCatalog
import dev.streetsnap.app.pose.PoseModelStatus
import dev.streetsnap.app.pose.PoseOverlayLayer
import dev.streetsnap.app.pose.PoseUiState
import dev.streetsnap.camera.CameraCapabilities
import dev.streetsnap.camera.CameraState
import dev.streetsnap.camera.ExposureMode
import dev.streetsnap.camera.NormalizedBoundingBox
import dev.streetsnap.camera.FocalLength
import dev.streetsnap.camera.WhiteBalanceMode
import dev.streetsnap.color.ColorPreset
import dev.streetsnap.color.FilmRegistry
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import dev.streetsnap.app.composition.AdviceOrigin
import dev.streetsnap.app.composition.AssistStatus
import dev.streetsnap.app.composition.CompositionOutcome
import dev.streetsnap.app.composition.CompositionOverlay
import dev.streetsnap.app.composition.CompositionUiState
import dev.streetsnap.app.composition.ModelInstallStatus

@Composable
fun CameraScreen(activity: MainActivity, model: CameraViewModel = viewModel()) {
    val cameraState by model.camera.state.collectAsStateWithLifecycle()
    val streaming by model.previewStreaming.collectAsStateWithLifecycle()
    val renderingError by model.previewError.collectAsStateWithLifecycle()
    val restored by model.controlsRestored.collectAsStateWithLifecycle()
    val state = cameraState.copy(isReady = cameraState.isReady && streaming && renderingError == null && restored)
    val settings by model.settings.collectAsStateWithLifecycle()
    val inFlight by model.inFlight.collectAsStateWithLifecycle()
    val latestPhoto by model.latestPhoto.collectAsStateWithLifecycle()
    val lutLoading by model.lutLoading.collectAsStateWithLifecycle()
    val loaded by model.settingsLoaded.collectAsStateWithLifecycle()
    val compositionState by model.composition.state.collectAsStateWithLifecycle()
    val compositionSource by model.composition.source.collectAsStateWithLifecycle()
    val compositionOverlay by model.composition.overlay.collectAsStateWithLifecycle()
    var permitted by remember { mutableStateOf(activity.hasCameraPermission()) }
    var denied by remember { mutableStateOf(false) }
    var adjustmentTab by remember { mutableStateOf<AdjustmentTab?>(null) }
    var showCapabilities by remember { mutableStateOf(false) }
    var showBodyPicker by remember { mutableStateOf(false) }
    // GR (and other no-film bodies) must not keep the 胶片 tab open across a body switch.
    LaunchedEffect(settings.cameraBody) {
        if (adjustmentTab == AdjustmentTab.FILM &&
            !BodyCatalog.supportsFilmRolls(BodyCatalog.resolve(settings.cameraBody))) {
            adjustmentTab = AdjustmentTab.LOOK
        }
    }
    var retry by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    BackHandler(enabled = adjustmentTab != null && !showCapabilities && !showBodyPicker) { adjustmentTab = null }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = it
        denied = !it
    }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importLut)
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permitted = activity.hasCameraPermission()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> model.onForegroundChanged(true)
                Lifecycle.Event.ON_PAUSE -> model.onForegroundChanged(false)
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        model.onForegroundChanged(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.lifecycle.removeObserver(observer); model.onForegroundChanged(false) }
    }
    LaunchedEffect(Unit) { if (!permitted) permission.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(activity, loaded, settings.captureOrientation) {
        if (loaded) activity.applyCaptureOrientation(settings.captureOrientation)
    }
    LaunchedEffect(model) {
        model.notices.collect { notice ->
            snackbar.showSnackbar(notice.text, duration = SnackbarDuration.Short)
        }
    }
    val currentReady by rememberUpdatedState(permitted && state.isReady && !showCapabilities && !showBodyPicker)
    DisposableEffect(activity, model) {
        activity.volumeShutter = { trigger ->
            if (currentReady) {
                if (trigger) model.shoot()
                true
            } else false
        }
        onDispose { activity.volumeShutter = null }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        // Edge-to-edge: the camera tree itself is NOT wrapped in safeDrawingPadding.
        // Padding the whole tree translates nameplate + viewfinder + shutter as one
        // rigid block whenever the inset set changes (transient system bars, IME,
        // returning from another activity), which reads as the whole screen jumping
        // down. Each branch instead consumes only the edge its chrome touches (T116).
        Box(Modifier.fillMaxSize()) {
            if (!permitted) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    PermissionPanel(denied, { permission.launch(Manifest.permission.CAMERA) }, activity::openAppSettings)
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val landscape = maxWidth > maxHeight
                    val density = LocalDensity.current
                    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
                    val navigationBarBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
                    if (landscape && maxWidth >= 600.dp) {
                        // Large landscape screens keep the all-controls side rail; the
                        // phone portrait path below is the floating full-screen viewfinder.
                        val sideWidth = minOf(356.dp, maxWidth * .48f)
                        Row(Modifier.fillMaxSize()) {
                            Viewfinder(model, state, settings, retry, true, compositionSource, compositionOverlay, compositionState,
                                Modifier.weight(1f).fillMaxHeight(), onRetry = { retry++ },
                                onOpenLooks = { adjustmentTab = AdjustmentTab.LOOK },
                                systemBarTop = statusBarTop)
                            ShootingControls(state, settings, inFlight, loaded, lutLoading, model,
                                Modifier.width(sideWidth).fillMaxHeight(),
                                adjustmentTab, { adjustmentTab = it }, sidePanel = true,
                                panelHeight = 264.dp,
                                importLut = { lutPicker.launch(arrayOf("*/*")) },
                                openBodyPicker = { showBodyPicker = true },
                                compositionState = compositionState,
                                latestPhoto = latestPhoto, openPhoto = activity::openPhoto)
                        }
                    } else if (maxHeight > maxWidth) {
                        // Phone portrait: a camera-back layout. Nameplate, the exact 3:4
                        // viewfinder screen and an opaque control cluster stack edge to
                        // edge, so there is no letterbox void between picture and controls.
                        // T121: the nameplate is the FUNCTIONAL active body (drives focal
                        // lengths, looks and the film section), or hidden for a bigger frame.
                        val hideNameplate = settings.nameplateHidden
                        // Tall enough for the body card to read as a camera plate rather than
                        // a flattened strip (T22: 48dp crowded the 74x40 artwork).
                        val nameplateHeight = if (hideNameplate) 0.dp else 80.dp
                        // Fixed content the cluster can show: divider + paddings + the
                        // constant 36dp override-chip slot + shoulder strip + focal row +
                        // fixed focal/shutter gap + 92dp shutter row = 254dp. The chip slot
                        // is always reserved (T116) so it must be counted here; when the
                        // reservation was still 218dp the weighted slack spacer measured
                        // zero and the whole cluster top-aligned (T118). On short screens
                        // the 3:4 picture shrinks a touch instead of clipping.
                        // The bottom band owns the navigation-bar inset; reserve it here so
                        // the 3:4 picture sizing matches the old safe-area layout.
                        val clusterMinHeight = 254.dp + navigationBarBottom
                        val framedWidth = minOf(maxWidth,
                            (maxHeight - statusBarTop - nameplateHeight - clusterMinHeight) * 3f / 4f)
                        Column(Modifier.fillMaxSize().background(Color.Black)) {
                            // Status-bar inset is consumed at the very top edge only: it
                            // grows/shrinks this black strip instead of translating the
                            // viewfinder and shutter as one block. windowInsetsTopHeight
                            // also consumes the inset for the subtree below.
                            Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
                            // Second collector of the same StateFlow: the Viewfinder keeps
                            // its own for the landscape / hidden-nameplate floating path.
                            val nameplateLookCard by model.lookAdviceCard.collectAsStateWithLifecycle()
                            if (!hideNameplate) {
                                val nameplateCamera = BodyCatalog.resolve(settings.cameraBody)
                                val activePreset = settings.colorRecipe().preset
                                val lookName = settings.filmId?.let { FilmRegistry.find(it)?.displayName }
                                    ?: activePreset.displayName
                                val focalLine = nameplateCamera?.let { body ->
                                    when {
                                        // A zoom span reads as a range ("24–75mm"); short fixed sets
                                        // stay enumerated ("28／40mm"); single-focal easter eggs
                                        // show just the one frame.
                                        body.focalMm.size >= 4 -> "${body.focalMm.first()}–${body.focalMm.last()}mm"
                                        else -> body.focalMm.joinToString("／") { "${it}mm" }
                                    }
                                } ?: "28／35／50mm"
                                ViewfinderNameplate(
                                    camera = nameplateCamera,
                                    title = nameplateCamera?.model ?: "通用",
                                    subtitle = if (nameplateCamera != null)
                                        "${nameplateCamera.brand} · $focalLine" else "通用影调 · $lookName",
                                    onPick = { showBodyPicker = true },
                                    modifier = Modifier.fillMaxWidth().height(nameplateHeight),
                                    trailing = {
                                        val card = nameplateLookCard
                                        // The row cannot fit both the full chip (label + dismiss +
                                        // try/keep actions) and the badge at phone widths, so while a
                                        // suggestion is active the chip owns the row — the body card
                                        // already names the film family — and the badge otherwise
                                        // remains the constant right-side resident.
                                        Spacer(Modifier.weight(1f))
                                        if (card != null) {
                                            NameplateLookChip(card,
                                                onTry = model::trySuggestedLook,
                                                onKeep = model::keepSuggestedLook,
                                                onRevert = model::revertSuggestedLook,
                                                onDismiss = model::dismissLookAdvice)
                                        } else {
                                            FilmFrameBadge(settings.filmId, activePreset, lookName,
                                                onClick = { adjustmentTab = AdjustmentTab.LOOK })
                                        }
                                    })
                            }
                            Viewfinder(model, state, settings, retry, false, compositionSource, compositionOverlay, compositionState,
                                Modifier.width(framedWidth).aspectRatio(3f / 4f), framed = true, onRetry = { retry++ },
                                onOpenLooks = { adjustmentTab = AdjustmentTab.LOOK },
                                nameplateHidden = hideNameplate)
                            ViewfinderOverlayBar(state, settings, inFlight, loaded, model,
                                Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(), framed = true,
                                onTab = { adjustmentTab = it },
                                latestPhoto = latestPhoto, openPhoto = activity::openPhoto)
                        }
                        if (adjustmentTab != null) {
                            ControlBottomSheet(adjustmentTab!!, onDismiss = { adjustmentTab = null },
                                onTab = { adjustmentTab = it },
                                state = state, settings = settings, importing = lutLoading, model = model,
                                importLut = { lutPicker.launch(arrayOf("*/*")) },
                                openBodyPicker = { showBodyPicker = true },
                                compositionState = compositionState)
                        }
                    } else {
                        // Small landscape / split-screen: the viewfinder owns the space and
                        // shutter/focal controls float over its lower edge.
                        Viewfinder(model, state, settings, retry, landscape, compositionSource, compositionOverlay, compositionState,
                            Modifier.fillMaxSize(), onRetry = { retry++ },
                            onOpenLooks = { adjustmentTab = AdjustmentTab.LOOK },
                            systemBarTop = statusBarTop)
                        ViewfinderOverlayBar(state, settings, inFlight, loaded, model,
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
                            onTab = { adjustmentTab = it },
                            latestPhoto = latestPhoto, openPhoto = activity::openPhoto)
                        if (adjustmentTab != null) {
                            ControlBottomSheet(adjustmentTab!!, onDismiss = { adjustmentTab = null },
                                onTab = { adjustmentTab = it },
                                state = state, settings = settings, importing = lutLoading, model = model,
                                importLut = { lutPicker.launch(arrayOf("*/*")) },
                                openBodyPicker = { showBodyPicker = true },
                                compositionState = compositionState)
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp))
        }
    }
    if (showCapabilities) CapabilitiesDialog(state.capabilities, state.error, { showCapabilities = false })
    if (showBodyPicker) BodyPicker(settings.cameraBody, settings.nameplateHidden, settings.captureOrientation,
        model::setCameraBody, model::setNameplateHidden, model::setCaptureOrientation,
        onDiagnostics = { showCapabilities = true }, onDismiss = { showBodyPicker = false })
}

@Composable
private fun PermissionPanel(denied: Boolean, request: () -> Unit, settings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("让相机准备好", style = MaterialTheme.typography.headlineSmall)
        Text("途帧仅需相机权限。照片写入系统相册，不读取你的图库。",
            Modifier.padding(vertical = 16.dp).widthIn(max = 360.dp), textAlign = TextAlign.Center)
        Button(onClick = request) { Text(if (denied) "重新授权" else "允许使用相机") }
        if (denied) TextButton(onClick = settings) { Text("前往系统设置") }
    }
}

@Composable
@SuppressLint("MissingPermission") // CameraScreen only composes this after checking CAMERA permission.
private fun Viewfinder(
    model: CameraViewModel,
    state: CameraState,
    settings: AppSettings,
    retry: Int,
    landscape: Boolean,
    compositionSource: dev.streetsnap.camera.CompositionFrameSource?,
    compositionOverlay: CompositionOverlay?,
    compositionState: CompositionUiState,
    modifier: Modifier,
    onRetry: () -> Unit,
    // Portrait camera-back layout: the caller already sizes this composable to the exact
    // 3:4 screen, so overlays only need a small lift off its bottom edge.
    framed: Boolean = false,
    onOpenLooks: () -> Unit = {},
    /**
     * Portrait with the nameplate row hidden: the film badge and look suggestion have no
     * out-of-frame row to live in, so they fall back to floating over the top-end corner.
     */
    nameplateHidden: Boolean = false,
    // Raw system-bar insets read once at the root and forwarded explicitly. Passing dp
    // instead of statusBarsPadding() keeps sibling panels from receiving already-consumed
    // insets and avoids any whole-surface translation on transient inset changes (T116).
    systemBarTop: Dp = 0.dp,
    systemBarBottom: Dp = 0.dp,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val preview = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(preview, lifecycle) {
        val observer = Observer<PreviewView.StreamState> { model.onPreviewStreaming(it == PreviewView.StreamState.STREAMING) }
        preview.previewStreamState.observe(lifecycle, observer)
        onDispose {
            preview.previewStreamState.removeObserver(observer)
            model.onPreviewStreaming(false)
        }
    }
    DisposableEffect(preview, model) {
        val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        fun updateRotation() {
            preview.display?.let { model.camera.updateTargetRotation(it.rotation) }
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = updateRotation()
            override fun onDisplayRemoved(displayId: Int) = updateRotation()
            override fun onDisplayChanged(displayId: Int) {
                if (preview.display?.displayId == displayId) updateRotation()
            }
        }
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displays.unregisterDisplayListener(listener) }
    }
    var width by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    var bindingError by remember { mutableStateOf<String?>(null) }
    val previewError by model.previewError.collectAsStateWithLifecycle()
    val controlsRestored by model.controlsRestored.collectAsStateWithLifecycle()
    val streaming by model.previewStreaming.collectAsStateWithLifecycle()
    val histogram by model.histogram.collectAsStateWithLifecycle()
    val lookAdviceCard by model.lookAdviceCard.collectAsStateWithLifecycle()
    val exposureAdviceCard by model.exposureAdviceCard.collectAsStateWithLifecycle()
    // T40: the one-shot pulse only fires the (opt-in) buzz; visibility is driven by the
    // persistent confirmed phase, which stays true while the achieved framing holds and
    // clears as soon as it drifts away — the goal no longer flashes for 1.3 s and vanishes.
    val alignmentPulse by model.composition.alignmentPulse.collectAsStateWithLifecycle()
    val alignmentConfirmed by model.composition.alignmentConfirmed.collectAsStateWithLifecycle()
    // T72 pose assistant: the frame source is non-null only while live recognition is bound.
    val poseState by model.pose.state.collectAsStateWithLifecycle()
    val poseSource by model.pose.source.collectAsStateWithLifecycle()
    // T40: the success sentence announces the achieved phase once, then the card fades out and
    // only the persistent green frame + check remain (iPhone level/Mola restraint: a quiet
    // steady-state, no permanent banner). Drifting out dismisses the timer and fades the card
    // straight back in with the next move sentence.
    var alignmentCardDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(alignmentConfirmed) {
        if (alignmentConfirmed) {
            alignmentCardDismissed = false
            delay(2_200L)
            alignmentCardDismissed = true
        } else {
            alignmentCardDismissed = false
        }
    }
    val adviceCardAlpha by animateFloatAsState(
        if (alignmentConfirmed && alignmentCardDismissed) 0f else 1f,
        animationSpec = tween(320, easing = FastOutSlowInEasing), label = "adviceCardAlpha")
    val vibrator = remember(context) { context.getSystemService(Vibrator::class.java) }
    LaunchedEffect(alignmentPulse) {
        if (alignmentPulse > 0L && settings.alignmentHapticEnabled) {
            @Suppress("MissingPermission") // VIBRATE is a normal permission, granted at install.
            vibrator?.vibrate(VibrationEffect.createOneShot(20L, 60))
        }
    }
    val osdMode = settings.viewfinderOsd
    val histogramWanted = ViewfinderOsd.showsHistogram(osdMode)
    val meteringWanted = histogramWanted || settings.exposureAdviceEnabled
    val hasSize = width > 0 && height > 0
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val manualFocus = state.settings.focusDistanceDiopters != null
    val canTapFocus = state.isReady && !manualFocus && state.capabilities?.supportsFocusMetering == true
    LaunchedEffect(manualFocus, retry, landscape, width, height) { focusPoint = null }
    LaunchedEffect(focusPoint, state.isFocusing, state.isFocusLocked) {
        if (!state.isFocusing && !state.isFocusLocked) {
            delay(4_000)
            focusPoint = null
        }
    }
    // The adjustment panel only changes the preview's size, not its 4:3 framing.
    // PreviewView handles those resizes without tearing down the camera session.
    LaunchedEffect(preview, lifecycle, retry, hasSize, landscape, compositionSource, poseSource, meteringWanted) {
        if (!hasSize) return@LaunchedEffect
        model.onBindingStarted()
        bindingError = null
        model.clearPreviewError()
        try {
            suspendCancellableCoroutine { continuation ->
                preview.doOnLayout { if (continuation.isActive) continuation.resume(Unit) }
            }
            val rotation = preview.display?.rotation ?: Surface.ROTATION_0
            val ratio = if (landscape) android.util.Rational(4, 3) else android.util.Rational(3, 4)
            val viewPort = ViewPort.Builder(ratio, rotation)
                .setScaleType(ViewPort.FILL_CENTER).build()
            model.camera.bind(lifecycle, preview.surfaceProvider, model.colors.previewEffect(model::onPreviewError), viewPort, rotation,
                compositionFrames = compositionSource,
                poseFrames = poseSource,
                lumaMeter = if (meteringWanted) model.lumaMeter else null)
            model.onCameraBound()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            bindingError = error.message ?: "相机未能启动"
        }
    }
    BoxWithConstraints(modifier.background(Color.Black)) {
        val ratio = if (landscape) 4f / 3f else 3f / 4f
        val previewWidth = min(maxWidth.value, maxHeight.value * ratio).dp
        val previewHeight = previewWidth / ratio
        // Framed portrait: the box already is the exact 3:4 screen between nameplate and
        // control cluster, so cards hug the screen edge. Floating portrait lifts cards
        // above the shutter/focal bar; landscape barely lifts them at all.
        val floatingClearance = when {
            framed -> 16.dp
            maxHeight > maxWidth -> 92.dp
            else -> 10.dp
        }
        // Framed fills its box. The floating portrait path pins to TopCenter; landscape
        // keeps the 3:4 picture centred beside/below its chrome.
        val previewAlignment = if (framed || maxWidth > maxHeight) Alignment.Center else Alignment.TopCenter
        // clipToBounds keeps the T35 long-press trial zoom from painting outside the 3:4 picture.
        Box(Modifier.size(previewWidth, previewHeight).align(previewAlignment).clipToBounds()) {
            // T35: while the advice card is long-pressed, the preview zooms to the suggested
            // crop. Display-only: ImageCapture is a separate use case, photos stay full frame.
            var trialCrop by remember { mutableStateOf<NormalizedBoundingBox?>(null) }
            val suggestedBox = compositionOverlay?.suggestion?.let {
                NormalizedBoundingBox(it.left, it.top, it.right, it.bottom)
            }
            // A scene change publishes a different suggestion (or null); the held trial then
            // ends visually even before the finger lifts.
            val activeTrial = trialCrop?.takeIf { it == suggestedBox }?.let(CropTrial::from)
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize().onSizeChanged {
                width = it.width
                height = it.height
            }.graphicsLayer {
                val trial = activeTrial
                if (trial != null) {
                    scaleX = trial.scale
                    scaleY = trial.scale
                    transformOrigin = TransformOrigin(trial.centerU, trial.centerV)
                    translationX = (0.5f - trial.centerU) * size.width
                    translationY = (0.5f - trial.centerV) * size.height
                }
            })
            val roll = rememberLevelAngle(settings.level, preview)
            // Rule advice refreshes several times per second; ease the rectangle so the
            // guide tracks the scene without visibly jumping between nearby crops.
            val smoothCrop = rememberSmoothCrop(compositionOverlay)
            Canvas(Modifier.fillMaxSize().pointerInput(preview, canTapFocus) {
                detectTapGestures(
                    onTap = { position ->
                        if (canTapFocus) {
                            focusPoint = Offset(position.x / width, position.y / height)
                            model.focus(preview.meteringPointFactory.createPoint(position.x, position.y))
                        }
                    },
                    onLongPress = { position ->
                        if (canTapFocus) {
                            focusPoint = Offset(position.x / width, position.y / height)
                            model.focus(preview.meteringPointFactory.createPoint(position.x, position.y), lock = true)
                        }
                    },
                )
            }) {
                if (settings.grid && compositionOverlay == null) {
                    val color = Color.White.copy(alpha = .32f)
                    for (i in 1..2) {
                        drawLine(color, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.dp.toPx())
                        drawLine(color, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.dp.toPx())
                    }
                }
                if (settings.level && roll != null) {
                    // Horizon level near the top edge (DSLR convention), never over the
                    // centre of the picture: a faint fixed reference plus a rolling line;
                    // the two coincide and turn lime when the phone is level.
                    val pivot = Offset(size.width / 2f, size.height * .085f)
                    val halfLength = min(size.width * .075f, 28.dp.toPx())
                    val tick = 4.dp.toPx()
                    val reference = Color.White.copy(alpha = .32f)
                    drawLine(reference, Offset(pivot.x - halfLength, pivot.y), Offset(pivot.x + halfLength, pivot.y), 1.5.dp.toPx())
                    drawLine(reference, Offset(pivot.x - halfLength, pivot.y - tick), Offset(pivot.x - halfLength, pivot.y + tick), 1.dp.toPx())
                    drawLine(reference, Offset(pivot.x + halfLength, pivot.y - tick), Offset(pivot.x + halfLength, pivot.y + tick), 1.dp.toPx())
                    val radians = Math.toRadians(roll.toDouble())
                    val delta = Offset((kotlin.math.cos(radians) * halfLength).toFloat(), (kotlin.math.sin(radians) * halfLength).toFloat())
                    val levelColor = if (abs(roll) < 1.5f) Color(0xFFE2FF79) else Color.White.copy(alpha = .78f)
                    drawLine(levelColor, pivot - delta, pivot + delta, 2.dp.toPx())
                }
                // Small corner histogram window (Lightroom/ProShot convention): a quiet
                // 100x42 translucent panel tucked under the top-right pills. The numeric
                // exposure readout moved to the always-on shoulder strip above the shutter,
                // so this never grows into a wide card covering the frame.
                if (histogramWanted) histogram?.let { counts ->
                    val panelWidth = 100.dp.toPx()
                    val panelHeight = 42.dp.toPx()
                    val margin = 10.dp.toPx()
                    // Below the top-right composition/realtime/AF pills.
                    val panelLeft = size.width - panelWidth - margin
                    val panelTop = 52.dp.toPx()
                    val corner = 6.dp.toPx()
                    drawRoundRect(Color.Black.copy(alpha = .42f),
                        topLeft = Offset(panelLeft, panelTop),
                        size = androidx.compose.ui.geometry.Size(panelWidth, panelHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner))
                    val tallest = counts.maxOrNull()?.toFloat() ?: 0f
                    if (tallest > 0f) {
                        val innerPad = 3.dp.toPx()
                        val plotHeight = panelHeight - innerPad * 2
                        val barWidth = panelWidth / counts.size
                        counts.forEachIndexed { index, count ->
                            val barHeight = (count / tallest * plotHeight).coerceAtLeast(1f)
                            drawRect(Color.White.copy(alpha = .72f),
                                topLeft = Offset(panelLeft + index * barWidth, panelTop + panelHeight - innerPad - barHeight),
                                size = androidx.compose.ui.geometry.Size(barWidth * .82f, barHeight))
                        }
                    }
                }
                focusPoint?.let { point ->
                    val radius = minOf(22.dp.toPx(), size.minDimension / 4f)
                    val x = (point.x * size.width).coerceIn(radius, size.width - radius)
                    val y = (point.y * size.height).coerceIn(radius, size.height - radius)
                    val color = when {
                        state.isFocusing -> Color.White
                        state.focusResult == true -> Color(0xFFE2FF79)
                        state.focusResult == false -> Color(0xFFFFB36B)
                        else -> Color.White
                    }
                    drawRect(color, Offset(x - radius, y - radius),
                        androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = Stroke(1.5.dp.toPx()))
                }
                // T77: the HAL's own face-tracking rectangle (largest face, portrait-normalized
                // sensor coordinates). The 3A library drives face/eye AF by itself; this thin
                // box only shows which face it follows and it disappears under manual focus.
                // FILL_CENTER viewport crop is not compensated yet — treat placement as
                // loaded-only until verified on real devices.
                state.liveFaces?.primary?.let { face ->
                    val left = face.left.coerceIn(0f, 1f) * size.width
                    val top = face.top.coerceIn(0f, 1f) * size.height
                    val boxWidth = (face.right - face.left).coerceAtLeast(0f) * size.width
                    val boxHeight = (face.bottom - face.top).coerceAtLeast(0f) * size.height
                    drawRect(
                        Color.White.copy(alpha = .5f),
                        Offset(left, top),
                        androidx.compose.ui.geometry.Size(boxWidth, boxHeight),
                        style = Stroke(1.dp.toPx()),
                    )
                }
                // While the T35 trial zoom fills the screen the guide rectangle and shade
                // are the whole viewfinder, so hide them instead of masking the preview.
                if (activeTrial == null) {
                    // Reading the animated values here (inside the draw scope) means
                    // animation ticks only redraw this canvas, not recompose the screen.
                    // Geometry, origin and alpha come from the smooth state rather than the
                    // overlay itself, so the guide can cross-fade for ~180 ms after a scene
                    // change / expiry instead of winking out (T40 anti-flicker pass).
                    val crop = smoothCrop.crop
                    val guideAlpha = smoothCrop.alpha.value
                    if (crop != null && guideAlpha > 0f) {
                    val left = (crop.left.coerceIn(0f, 1f) * size.width)
                    val top = (crop.top.coerceIn(0f, 1f) * size.height)
                    val right = (crop.right.coerceIn(0f, 1f) * size.width)
                    val bottom = (crop.bottom.coerceIn(0f, 1f) * size.height)
                    if (right > left && bottom > top) {
                        val tint = when {
                            alignmentConfirmed -> Color(0xFF54C97E)
                            smoothCrop.snappedOrigin == AdviceOrigin.MODEL_SELECTION -> Color(0xFFE2FF79)
                            else -> Color(0xFFB7E3F6)
                        }
                        // Once the framing is achieved the guide stops dimming the picture;
                        // the thin green frame is enough while the user takes the shot.
                        val shade = Color.Black.copy(alpha = (if (alignmentConfirmed) .10f else .26f) * guideAlpha)
                        drawRect(shade, size = androidx.compose.ui.geometry.Size(size.width, top))
                        drawRect(shade, Offset(0f, bottom), androidx.compose.ui.geometry.Size(size.width, size.height - bottom))
                        drawRect(shade, Offset(0f, top), androidx.compose.ui.geometry.Size(left, bottom - top))
                        drawRect(shade, Offset(right, top), androidx.compose.ui.geometry.Size(size.width - right, bottom - top))
                        drawRect(tint.copy(alpha = (if (alignmentConfirmed) .9f else .95f) * guideAlpha), Offset(left, top),
                            androidx.compose.ui.geometry.Size(right - left, bottom - top),
                            style = Stroke(if (alignmentConfirmed) 3.dp.toPx() else 2.dp.toPx()))
                        for (i in 1..2) {
                            val x = left + (right - left) * i / 3f
                            val y = top + (bottom - top) * i / 3f
                            drawLine(tint.copy(alpha = (if (alignmentConfirmed) .26f else .40f) * guideAlpha), Offset(x, top), Offset(x, bottom), .75.dp.toPx())
                            drawLine(tint.copy(alpha = (if (alignmentConfirmed) .26f else .40f) * guideAlpha), Offset(left, y), Offset(right, y), .75.dp.toPx())
                        }
                        if (alignmentConfirmed) {
                            // Persistent confirmation mark for as long as the framing holds (T40).
                            val mark = minOf(26.dp.toPx(), (right - left) / 5f, (bottom - top) / 5f)
                            // Inside the bottom-right corner: the advice card lives bottom-left
                            // and the top-right can be covered by the optional histogram card.
                            val center = Offset(right - mark * 1.35f, bottom - mark * 1.35f)
                            drawCircle(Color.Black.copy(alpha = .40f * guideAlpha), mark * .95f, center)
                            val checkStroke = 2.5.dp.toPx()
                            val p0 = Offset(center.x - mark * .45f, center.y + mark * .05f)
                            val p1 = Offset(center.x - mark * .12f, center.y + mark * .38f)
                            val p2 = Offset(center.x + mark * .48f, center.y - mark * .35f)
                            drawLine(tint.copy(alpha = guideAlpha), p0, p1, checkStroke, cap = StrokeCap.Round)
                            drawLine(tint.copy(alpha = guideAlpha), p1, p2, checkStroke, cap = StrokeCap.Round)
                        }
                    }
                    }
                }
            }
            if (activeTrial != null) {
                Text("试取景 · 松开还原",
                    Modifier.align(Alignment.TopCenter).padding(top = systemBarTop + 14.dp)
                        .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    color = Color.White, fontSize = 12.sp)
            }
            // The top pill row would collide with the "试取景 · 松开还原" label; it is
            // transient anyway (finger still down), so hide the whole row during the trial.
            if (activeTrial == null) Row(Modifier.align(Alignment.TopStart).fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = systemBarTop + 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                // Single quiet corner entry: off -> histogram -> histogram + exposure readout -> off.
                OsdCycleButton(osdMode, model::cycleViewfinderOsd)
                Spacer(Modifier.weight(1f))
                // 快拍模式: the AI row does not exist — pose/composition/realtime live only in
                // pro mode, which is one tap away from the bottom-left quick toggle.
                if (!settings.quickShotMode) {
                    OverlayPill(
                        label = "姿势",
                        enabled = true, description = "姿势助手开关",
                        selected = settings.poseAssistantEnabled,
                        onClick = { model.setPoseAssistantEnabled(!settings.poseAssistantEnabled) })
                    OverlayPill(
                        label = if (!compositionState.isRequestRunning) "构图" else "分析中",
                        enabled = state.isReady && !compositionState.isRequestRunning,
                        description = "分析一次构图",
                        onClick = model::requestComposition)
                    OverlayPill(
                        label = if (settings.compositionRealtime) "实时 · 开" else "实时",
                        enabled = true, description = "实时构图开关", selected = settings.compositionRealtime,
                        onClick = { model.setCompositionRealtime(!settings.compositionRealtime) })
                }
                val focusLabel = when {
                    manualFocus -> "MF"
                    state.isFocusLocked -> "AF-L"
                    state.capabilities?.autoFocusMode != null -> "AF"
                    else -> "固定焦点"
                }
                OverlayPill(focusLabel, onClick = {
                        focusPoint = null
                        if (state.isFocusLocked) model.autoFocus() else model.toggleFocusMode()
                    },
                    enabled = state.isReady && (if (manualFocus) state.capabilities?.autoFocusMode != null
                        else state.isFocusLocked || state.capabilities?.supportsManualFocus == true),
                    selected = manualFocus || state.isFocusLocked,
                    description = when {
                        state.isFocusLocked -> "解除对焦锁定"
                        manualFocus -> "切换自动对焦"
                        state.capabilities?.supportsManualFocus == true -> "切换手动对焦"
                        else -> "当前对焦模式"
                    })
            }
            // Persistent bloom toggle, stacked under the OSD corner button; the right
            // column hosts only the optional small histogram window.
            HdrToggleButton(settings.hdrEnabled, model::toggleHdr,
                Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = systemBarTop + 52.dp))
            // Clipped-highlight/shadow hint, in-frame under the HDF corner button on every
            // layout. It used to share the black control band with the shoulder strip in
            // framed mode, but the band has no slack once the fixed override-chip slot is
            // reserved, so the card collided with the meter readout (T118).
            // T121: AI hints are suppressed entirely in 快拍模式.
            if (!settings.quickShotMode) exposureAdviceCard?.let { card ->
                ExposureAdviceCardView(card, model::dismissExposureAdvice,
                    Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = systemBarTop + 100.dp))
            }
            // In framed portrait the film badge and look suggestion live in the nameplate
            // row (outside the picture), so they float in-frame only on landscape paths and
            // when the nameplate is hidden. The right column holds at most the small
            // histogram window now (the numeric readout moved to the shoulder strip), so
            // the badge has one stable slot: under the histogram or on the top row.
            if (!framed || nameplateHidden) {
                val badgeTop by animateDpAsState(
                    if (histogramWanted) 102.dp else 52.dp, label = "filmBadgeTop")
                val badgePreset = settings.colorRecipe().preset
                val badgeLabel = settings.filmId?.let { FilmRegistry.find(it)?.displayName }
                    ?: badgePreset.displayName
                FilmFrameBadge(settings.filmId, badgePreset, badgeLabel, onOpenLooks,
                    Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = systemBarTop + badgeTop))
                // T121: same 30dp single-line chip as the nameplate row (the old wide card
                // covered too much picture); never shown in 快拍模式.
                if (!settings.quickShotMode) lookAdviceCard?.let { card ->
                    NameplateLookChip(card,
                        onTry = model::trySuggestedLook,
                        onKeep = model::keepSuggestedLook,
                        onRevert = model::revertSuggestedLook,
                        onDismiss = model::dismissLookAdvice,
                        Modifier.align(Alignment.TopEnd)
                            .padding(end = 10.dp, top = systemBarTop + badgeTop + 50.dp))
                }
            }
            // The DSLR-style numeric readout (mode/aperture/shutter/ISO/EV) is now the
            // T121: composition hint is one 26dp single-line edge pill — never a multi-line
            // card covering the frame. One line carries the executable move; the model text
            // and timing stay available via semantics / long-press preview. Absent entirely
            // in 快拍模式 (the analysis source is unbound there anyway).
            if (!settings.quickShotMode && settings.compositionEnabled) {
                // Lift over the pose card (bottom-centre, ~168dp in portrait) and over the
                // bottom-left quick/pro toggle (~58dp). Landscape keeps separate slots.
                val poseCardVisible = settings.poseAssistantEnabled && poseState.live && activeTrial == null
                val adviceBottom by animateDpAsState(
                    floatingClearance + (if (poseCardVisible && !landscape) 172.dp else 56.dp),
                    animationSpec = tween(220, easing = FastOutSlowInEasing), label = "adviceCardBottom")
                val line = when {
                    alignmentConfirmed -> "构图到位 · 可以拍了"
                    else -> compositionOverlay?.suggestion?.let { it.actionText ?: it.modelText }
                }
                if (line != null) {
                    val detail = compositionOverlay?.suggestion?.modelText
                    Box(Modifier.align(Alignment.BottomStart)
                        .padding(start = 10.dp, bottom = adviceBottom + systemBarBottom)
                        .graphicsLayer { alpha = adviceCardAlpha }
                        .heightIn(min = 26.dp).widthIn(max = 232.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color.Black.copy(alpha = .62f))
                        // T35: long-press the pill to preview the suggested framing; releasing
                        // restores. Consuming the down also blocks tap-focus underneath.
                        .pointerInput(compositionOverlay) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                                val box = compositionOverlay?.suggestion?.let {
                                    NormalizedBoundingBox(it.left, it.top, it.right, it.bottom)
                                }
                                if (box != null && CropTrial.from(box) != null) {
                                    trialCrop = box
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.changes.none { it.pressed }) break
                                    }
                                    trialCrop = null
                                }
                            }
                        }
                        .semantics {
                            contentDescription = buildString {
                                append(line)
                                if (detail != null && detail != line) append("；").append(detail)
                                append("；长按预览建议构图")
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.CenterStart) {
                        Text(line, color = if (alignmentConfirmed) Color(0xFF54C97E) else Color.White,
                            fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val focusHint = when {
                state.isFocusing -> "对焦中"
                state.isFocusLocked -> "对焦已锁定 · 点 AF-L 解除"
                focusPoint != null && state.focusResult == false -> "未合焦 · 可重试或直接拍摄"
                else -> null
            }
            focusHint?.let { Text(it, Modifier.align(Alignment.BottomCenter)
                .padding(bottom = floatingClearance + 8.dp + systemBarBottom),
                color = Color.White, fontSize = 12.sp) }
            // The quick/pro mode switch lives bottom-left in every layout: 快拍 (default)
            // hides every AI overlay and unbinds its analysis sources; 专业 keeps them.
            QuickShotToggle(settings.quickShotMode, model::setQuickShotMode,
                Modifier.align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = floatingClearance + 2.dp + systemBarBottom))
            // Bottom-right movement arrow: a glanceable "which way do I nudge the camera"
            // cue derived from the same geometry/dead zones as the advice card sentence.
            // Hidden in the green aligned phase, the trial zoom, and 快拍模式.
            val moveGuide = if (!settings.quickShotMode && !alignmentConfirmed && activeTrial == null)
                suggestedBox?.let(MoveGuides::fromCrop) else null
            val arrowAlpha by animateFloatAsState(
                if (moveGuide != null) 1f else 0f,
                animationSpec = tween(180, easing = FastOutSlowInEasing), label = "moveArrowAlpha")
            if (arrowAlpha > 0.01f) {
                MoveGuideIndicator(moveGuide, arrowAlpha,
                    Modifier.align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = floatingClearance + systemBarBottom))
            }
            // T72 pose assistant. Hidden in 快拍模式 and during the trial zoom.
            if (!settings.quickShotMode && settings.poseAssistantEnabled && activeTrial == null) {
                PoseOverlayLayer(poseState, settings.alignmentHapticEnabled, model::nextPose,
                    Modifier.fillMaxSize())
            }
        }
        val error = bindingError ?: previewError ?: state.error
        if (error != null) {
            Column(Modifier.background(Color.Black.copy(alpha = .84f)).padding(24.dp).widthIn(max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("相机暂不可用", style = MaterialTheme.typography.titleMedium)
                Text(error, Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center)
                Button(onClick = onRetry) { Text("重试") }
            }
        } else if (!state.isBound || !controlsRestored || !streaming) {
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
        }
    }
}

private enum class AdjustmentTab(val label: String) {
    LOOK("影调"), FILM("胶片"), WHITE_BALANCE("白平衡"), SHOOTING("拍摄"),
}

private enum class LookParameter(val label: String, val signed: Boolean = true) {
    INTENSITY("强度", false), SATURATION("饱和度"), HUE("色相"), CONTRAST("对比度"),
    BRIGHTNESS("亮度"), HIGHLIGHTS("高光"), SHADOWS("阴影"), SHARPNESS("锐度"),
    CLARITY("清晰度"), GRAIN("颗粒", false), VIGNETTE("暗角", false), DIFFUSION("柔光", false),
}

/** A currently-active manual override surfaced as a tappable/long-press-resettable chip. */
private data class ActiveOverride(
    val label: String,
    val tab: AdjustmentTab,
    val resetDescription: String,
    val onReset: () -> Unit,
)

/** Small translucent chip that floats over the viewfinder instead of sitting on a grey bar. */
@Composable
private fun OverlayPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    description: String? = null,
    onLongClick: (() -> Unit)? = null,
    longClickDescription: String? = null,
) {
    val highlighted = selected
    val container = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = .20f)
        else Color.Black.copy(alpha = .42f)
    val content = if (highlighted) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .92f)
    Box(modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(container)
        .combinedClickable(
            enabled = enabled, role = Role.Button,
            onLongClick = onLongClick, onLongClickLabel = longClickDescription,
            onClick = onClick,
        )
        .semantics {
            if (description != null) contentDescription = description
            this.selected = selected
        }.padding(horizontal = 15.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) content else content.copy(alpha = .4f),
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Top-left corner entry for the viewfinder OSD cycle; quiet when off, primary-tinted while showing. */
@Composable
private fun OsdCycleButton(mode: String, onCycle: () -> Unit) {
    val active = ViewfinderOsd.showsHistogram(mode)
    val description = if (active) "正在显示直方图，点按关闭取景信息" else "显示曝光直方图"
    val tint = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .78f)
    Box(Modifier.size(38.dp).clip(CircleShape)
        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .20f) else Color.Black.copy(alpha = .32f))
        .clickable(role = Role.Button, onClick = onCycle)
        .semantics { contentDescription = description; this.selected = active },
        contentAlignment = Alignment.Center) {
        // Tiny histogram glyph: three bars of different height.
        Canvas(Modifier.size(16.dp)) {
            val fractions = listOf(.45f, .95f, .65f)
            val gap = 1.5.dp.toPx()
            val barWidth = (size.width - gap * (fractions.size - 1)) / fractions.size
            fractions.forEachIndexed { index, fraction ->
                val barHeight = size.height * fraction
                drawRect(tint,
                    topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight))
            }
        }
    }
}

/** Tiny three-bar "analysis" mark: reads as an instrument readout, not a promo dot. */
@Composable
private fun AnalysisGlyph(modifier: Modifier = Modifier, tint: Color = Color.White.copy(alpha = .72f)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val bar = w * .18f
        val heights = listOf(h * .45f, h * .9f, h * .62f)
        val xs = listOf(w * .12f, w * .41f, w * .70f)
        for (i in 0..2) {
            drawRoundRect(
                tint,
                Offset(xs[i], h - heights[i]),
                androidx.compose.ui.geometry.Size(bar, heights[i]),
                androidx.compose.ui.geometry.CornerRadius(bar * .5f, bar * .5f),
            )
        }
    }
}

/**
 * Compact nameplate form of the realtime look suggestion. Deliberately styled as a system
 * analysis readout — hairline outline, cool glyph, neutral text actions, no accent CTA
 * colour — so it never reads as an in-viewfinder promotion (user feedback, 2026-10-19).
 * Suggesting state: the label area previews the look, × dismisses. Trying state: only
 * Revert/Keep remain; the reason stays available through semantics.
 */
@Composable
private fun NameplateLookChip(
    card: LookAdviceCard,
    onTry: () -> Unit,
    onKeep: () -> Unit,
    onRevert: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Color.Black.copy(alpha = .42f))
            .border(1.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(15.dp))
            .then(if (card.trying) Modifier else
                Modifier.clickable(role = Role.Button, onClick = onTry)
                    .semantics { contentDescription = "AI 影调建议：${card.label}，${card.reason}，点按试用" })
            .padding(start = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // System-notification pattern: the whole chip IS the primary action (tap to try /
        // tap to keep), and the only inline control is the dismiss/exit affordance. A
        // separate coloured "试用" CTA both read as an ad banner and overflowed the 412dp
        // nameplate row, crushing itself to a few pixels (2026-10-19).
        if (!card.trying) {
            AnalysisGlyph(Modifier.size(13.dp, 11.dp))
            Spacer(Modifier.width(6.dp))
        }
        // Built-in labels carry a code prefix ("NC · 经典负片") the chip drops; the trying
        // state shares the row with two text actions and falls back to the short code.
        val shortLabel = card.label.substringAfter(" · ")
        val chipLabel = if (card.trying && " · " in card.label)
            "试用中 · " + card.label.substringBefore(" · ")
        else (if (card.trying) "试用中 · " else "AI 建议 · ") + shortLabel
        Text(
            chipLabel,
            Modifier.then(if (card.trying) Modifier.padding(end = 4.dp) else Modifier)
                .widthIn(max = if (card.trying) 92.dp else 108.dp),
            color = Color.White.copy(alpha = .9f), fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (card.trying) {
            NameplateChipAction("还原", onRevert)
            NameplateChipAction("保留", onKeep, emphasized = true)
        } else {
            NameplateChipAction("×", onDismiss, description = "忽略此建议")
        }
    }
}

@Composable
private fun NameplateChipAction(
    label: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
    description: String? = null,
) {
    // Quiet hierarchy through alpha and a faint fill on the primary action — never the
    // brand yellow, which made the suggestion look like a banner advertisement.
    TextButton(onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(30.dp)
            .then(if (emphasized)
                Modifier.background(Color.White.copy(alpha = .14f), RoundedCornerShape(11.dp)) else Modifier)
            .semantics { if (description != null) contentDescription = description }) {
        Text(label, fontSize = 11.sp,
            color = if (emphasized) Color.White.copy(alpha = .95f) else Color.White.copy(alpha = .6f))
    }
}

@Composable
private fun LookAdviceAction(
    label: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
    description: String? = null,
) {
    // Neutral actions only: the primary one gets a faint fill instead of brand yellow.
    TextButton(onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.heightIn(min = 36.dp)
            .then(if (emphasized)
                Modifier.background(Color.White.copy(alpha = .14f), RoundedCornerShape(14.dp)) else Modifier)
            .semantics { if (description != null) contentDescription = description }) {
        Text(label, fontSize = 12.sp,
            color = if (emphasized) Color.White.copy(alpha = .95f) else Color.White.copy(alpha = .6f))
    }
}

/**
 * T121 compact exposure hint: a single-line 26dp pill under the HDF button — the old
 * two-line card covered too much picture. Suggest-only; × dismisses for the session (T31).
 */
@Composable
private fun ExposureAdviceCardView(card: ExposureAdviceCard, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .heightIn(min = 26.dp).widthIn(max = 232.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color.Black.copy(alpha = .62f))
            .padding(start = 10.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(card.message, Modifier.weight(1f, fill = false).padding(end = 4.dp),
            color = Color.White, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LookAdviceAction("×", onDismiss, description = "忽略曝光提示")
    }
}

/**
 * Bottom-left mode switch. 快拍 (default): pure camera — no pose/composition/AI overlays,
 * analysis sources unbound; looks/filters and HDF stay. 专业: every assistant available.
 */
@Composable
private fun QuickShotToggle(on: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val tint = if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .85f)
        Box(Modifier.size(42.dp).clip(CircleShape)
            .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = .20f)
                else Color.Black.copy(alpha = .34f))
            .border(1.dp, if (on) MaterialTheme.colorScheme.primary.copy(alpha = .85f)
                else Color.White.copy(alpha = .22f), CircleShape)
            .clickable(role = Role.Switch) { onToggle(!on) }
            .semantics {
                contentDescription = if (on)
                    "快拍模式已开启：姿势、构图与 AI 建议已隐藏，点按切换专业模式"
                else "专业模式：姿势、构图与 AI 建议可用，点按切换快拍模式"
                stateDescription = if (on) "快拍" else "专业"
                this.selected = on
            }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(18.dp)) {
                // Single-stroke lightning bolt.
                val w = 2.4.dp.toPx()
                val p = listOf(Offset(10.5f, 1f), Offset(3.5f, 9.5f), Offset(8.2f, 9.5f),
                    Offset(7f, 17f), Offset(14.5f, 7.5f), Offset(9.6f, 7.5f), Offset(10.5f, 1f))
                for (i in 0 until p.size - 1) {
                    drawLine(tint, p[i], p[i + 1], w, cap = StrokeCap.Round)
                }
            }
        }
        Text(if (on) "快拍" else "专业", fontSize = 9.sp,
            color = Color.White.copy(alpha = .85f), maxLines = 1)
    }
}

/** Always-on HDF toggle (Ricoh-style highlight diffusion) under the OSD corner button; quiet when off, primary-tinted while on. */
@Composable
private fun HdrToggleButton(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .78f)
    Box(modifier.size(38.dp).clip(CircleShape)
        .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = .20f) else Color.Black.copy(alpha = .32f))
        .clickable(role = Role.Button, onClick = onToggle)
        .semantics {
            contentDescription = "HDF 高光扩散开关（理光风格高光柔化与光晕）"
            stateDescription = if (on) "已开启" else "已关闭"
            this.selected = on
        },
        contentAlignment = Alignment.Center) {
        Text("HDF", color = tint, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * Bottom-right animated nudge answering "which way do I move?". Purely informational
 * (not clickable), driven by the same geometry as the advice-card sentence
 * ([MoveGuides]). The chevron endlessly slides a few dp toward its own direction so
 * the cue reads as motion even in a static room. [guide] may be null while [alpha]
 * is still fading out; the last direction then keeps rendering through the fade.
 */
@Composable
private fun MoveGuideIndicator(guide: MoveGuide?, alpha: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "moveArrow")
    val progress by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(760, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "moveArrowDrift")
    val direction = guide?.direction
    // T76: larger disc, darker backing, brighter/thicker chevrons and a longer slide so the
    // nudge reads at a glance over busy pictures.
    Box(modifier.size(46.dp).clip(CircleShape)
        .background(Color.Black.copy(alpha = .50f * alpha))
        .semantics {
            if (direction != null) contentDescription = "移动方向提示：${direction.arrowLabel}"
        }) {
        val arrowColor = Color.White.copy(alpha = (.75f + .25f * progress) * alpha)
        when {
            direction == null -> Unit // fade-out tail only
            direction == GuideDirection.FORWARD -> {
                // Two small chevrons converging: step closer.
                Canvas(Modifier.fillMaxSize().graphicsLayer {
                    rotationZ = 90f
                    translationY = density * (-5.2.dp.value + 3.4.dp.value * progress)
                }) { drawGuideChevron(arrowColor, halfWidth = 3.0.dp.toPx(), halfHeight = 4.4.dp.toPx(),
                    strokeWidth = 2.8.dp.toPx()) }
                Canvas(Modifier.fillMaxSize().graphicsLayer {
                    rotationZ = -90f
                    translationY = density * (5.2.dp.value - 3.4.dp.value * progress)
                }) { drawGuideChevron(arrowColor, halfWidth = 3.0.dp.toPx(), halfHeight = 4.4.dp.toPx(),
                    strokeWidth = 2.8.dp.toPx()) }
            }
            else -> Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = direction.arrowAngleDeg }) {
                Canvas(Modifier.fillMaxSize().graphicsLayer {
                    translationX = density * (-3.dp.value + 6.dp.value * progress)
                }) {
                    drawGuideChevron(arrowColor, halfWidth = 4.6.dp.toPx(), halfHeight = 7.2.dp.toPx(),
                        strokeWidth = 3.2.dp.toPx())
                }
            }
        }
    }
}

/** A round-capped ">" centred in the canvas, [halfWidth] back from the tip. */
private fun DrawScope.drawGuideChevron(color: Color, halfWidth: Float, halfHeight: Float, strokeWidth: Float) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    drawLine(color, Offset(cx - halfWidth, cy - halfHeight), Offset(cx, cy), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(cx, cy), Offset(cx - halfWidth, cy + halfHeight), strokeWidth, cap = StrokeCap.Round)
}

/**
 * Phone portrait top chrome. In follow-look mode the body tracks the active look/film
 * family (the general family shows the neutral lens mark) and the subtitle names the
 * look; a pinned body shows its brand. Tap: follow-look opens the look sheet, a pinned
 * body opens the picker. Sits above the 3:4 screen like the plate on a camera back.
 */
@Composable
private fun ViewfinderNameplate(
    camera: CameraModel?,
    title: String,
    subtitle: String,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    // Film badge (and, when present, the compact look-suggestion chip) own the wasted
    // right side of the nameplate instead of floating over the viewfinder (T38).
    // The content is RowScope so callers can use a weight spacer to push it right.
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // Same mid-grey card as the body-picker tiles (#2D3335): the dark vector bodies
        // only separate from a lighter field, not from black chrome. Sized to the artwork's
        // native 180:112 study ratio (~1.6): the old 92x52 (1.77) card left the body reading
        // as squashed, and the 80dp row had unused vertical headroom (2026-10-19).
        Box(Modifier.size(98.dp, 62.dp).clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2D3335))
            .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onPick)
            .semantics {
                contentDescription = if (camera != null) "相机铭牌：${camera.brand} ${camera.model}，点按更换"
                    else "相机铭牌：$title，点按更换影调"
            },
            contentAlignment = Alignment.Center) {
            // Canvas has no intrinsic size; an explicit size is mandatory in the wrapped row.
            if (camera != null) CameraArtwork(camera, Modifier.size(90.dp, 56.dp))
            else NeutralLookArtwork(Modifier.size(38.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Color.White.copy(alpha = .92f), fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color.White.copy(alpha = .5f), fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/**
 * Phone portrait capture surface. Framed mode is an opaque camera-back control cluster
 * beneath the 3:4 screen (a hairline marks the edge, then chips/focal/shutter sit as one
 * vertically centred group); the small-landscape fallback keeps controls floating over
 * the viewfinder on a gradient.
 * Only the high-frequency focal choice and the shutter stay; looks, white balance and
 * other pro controls are surfaced here *only* while a manual override is active (a
 * tappable chip that jumps straight to the right sheet section), otherwise they live
 * in [ControlBottomSheet].
 */
@Composable
private fun ViewfinderOverlayBar(
    state: CameraState,
    settings: AppSettings,
    inFlight: Int,
    loaded: Boolean,
    model: CameraViewModel,
    modifier: Modifier = Modifier,
    onTab: (AdjustmentTab?) -> Unit,
    latestPhoto: Uri?,
    openPhoto: (Uri) -> Unit,
    framed: Boolean = false,
) {
    // T121: the focal set belongs to the active body (GR 28/40, X100VI 35/50, Rollei 35
    // just 35…), intersected with what the phone's digital crop actually supports.
    val bodyFocalLengths = BodyCatalog.focalLengths(BodyCatalog.resolve(settings.cameraBody))
    val focalLengths = bodyFocalLengths
    val supportedFocalLengths = state.capabilities?.focalLengthOptions.orEmpty()
    val quickShot = settings.quickShotMode
    val exposureMode = state.settings.exposureMode
    val ev = state.settings.exposureCompensationIndex * (state.capabilities?.exposureCompensationStepEv ?: 0.0)
    val wbName = state.settings.whiteBalance.takeIf { it != WhiteBalanceMode.AUTO }?.displayName
    // Only *manual overrides that can ruin a shot* get a persistent chip (Halide Mark III
    // pattern): non-auto WB, diffusion, MF, S/M exposure, EV. Tap jumps to the section,
    // long-press resets just that override. The creative look/film choice is WYSIWYG, so
    // it stays inside the sheet instead of taking a permanent slot.
    val overrides: List<ActiveOverride> = buildList {
        wbName?.let {
            add(ActiveOverride("白平衡 · $it", AdjustmentTab.WHITE_BALANCE, "复位白平衡") {
                model.setWhiteBalance(WhiteBalanceMode.AUTO)
                model.resetWhiteBalanceShift()
            })
        }
        if (settings.hdfEnabled) add(ActiveOverride("柔光", AdjustmentTab.LOOK, "关闭柔光") { model.toggleHdf() })
        if (settings.hdrEnabled) add(ActiveOverride("HDF", AdjustmentTab.LOOK, "关闭 HDF") { model.toggleHdr() })
        if (state.settings.focusDistanceDiopters != null)
            add(ActiveOverride("MF", AdjustmentTab.SHOOTING, "恢复自动对焦") { model.autoFocus() })
        if (exposureMode != ExposureMode.PROGRAM) add(ActiveOverride(
            when (exposureMode) { ExposureMode.MANUAL -> "M · ISO ${state.settings.iso ?: "—"}"; else -> "S" },
            AdjustmentTab.SHOOTING, "复位拍摄参数", model::resetShootingParameters))
        if (kotlin.math.abs(ev) > 0.001f && exposureMode != ExposureMode.MANUAL)
            add(ActiveOverride("EV %+.1f".format(ev), AdjustmentTab.SHOOTING, "曝光归零") { model.setExposure(0) })
    }

    val chrome = if (framed) Modifier.background(Color.Black)
        else Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .58f))))
    // T116: the focal row and shutter are anchored to the bottom of the band. The cluster
    // used to vertically centre its whole content between two weighted spacers, so the
    // moment an override chip appeared (柔光/HDF/non-auto WB/MF/S/M/EV) the shutter jumped
    // down ~22dp — the reported "whole screen shifts down". Override chips keep a
    // constant-height slot (its height is reserved in clusterMinHeight) and the transient
    // exposure card floats inside the 3:4 viewfinder, so nothing under the user's thumb
    // ever reflows. The inner Column MUST fill the band: without that the weighted spacer
    // measures zero and the whole cluster top-aligns against the divider (T118 regression:
    // shoulder strip touched the frame edge and crowded the cards).
    Box(modifier.then(chrome)
        .padding(top = if (framed) 8.dp else 46.dp, bottom = if (framed) 8.dp else 10.dp)) {
        Column(Modifier.fillMaxWidth().then(if (framed) Modifier.fillMaxHeight() else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // Hairline that separates the 3:4 screen from the camera-back control cluster.
            if (framed) HorizontalDivider(thickness = .8.dp, color = Color.White.copy(alpha = .07f))
            // All slack collects above the controls; the shutter group is bottom-anchored.
            if (framed) Spacer(Modifier.weight(1f))
            // 快拍模式 hides the DSLR readout for a plain camera-back; pro mode keeps it.
            if (!quickShot) ExposureShoulderStrip(state, model,
                Modifier.padding(bottom = if (framed) 5.dp else 4.dp))
            // Active manual overrides. Framed portrait keeps a constant-height slot so
            // chips fade in/out instead of pushing the shutter down; landscape keeps the
            // inline conditional row (its bar floats over the picture, no band slack).
            val chipAlpha by animateFloatAsState(if (overrides.isEmpty()) 0f else 1f,
                tween(160, easing = FastOutSlowInEasing), label = "overrideChipAlpha")
            if (framed) {
                Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
                    if (chipAlpha > 0.01f) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                                .horizontalScroll(rememberScrollState()).graphicsLayer { alpha = chipAlpha },
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            overrides.forEach { override ->
                                OverlayPill(override.label, selected = true,
                                    onClick = { onTab(override.tab) }, description = "打开${override.label}调整",
                                    onLongClick = override.onReset, longClickDescription = override.resetDescription)
                            }
                        }
                    }
                }
            } else if (overrides.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    overrides.forEach { override ->
                        OverlayPill(override.label, selected = true,
                            onClick = { onTab(override.tab) }, description = "打开${override.label}调整",
                            onLongClick = override.onReset, longClickDescription = override.resetDescription)
                    }
                }
            }
        // Focal lengths: the one pro control worth keeping on the capture screen.
        // In framed mode it belongs to the shutter group with a fixed, deliberate gap.
        // Fixed-lens bodies with a single frame (禄来 35) show no row at all.
        if (focalLengths.size > 1) Row(Modifier.padding(bottom = if (framed) 14.dp else 6.dp).clip(RoundedCornerShape(22.dp))
            .background(Color.Black.copy(alpha = .42f)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            focalLengths.forEach { focal ->
                val enabled = focal in supportedFocalLengths && state.isReady && loaded
                val selected = state.settings.focalLength == focal
                Box(Modifier.height(38.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable(enabled = enabled, role = Role.Button) { model.setFocalLength(focal) }
                    .semantics { this.selected = selected; contentDescription = "焦段 ${focal.label}" }
                    .padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Text(focal.label, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                        color = when {
                            !enabled -> Color.White.copy(alpha = .32f)
                            selected -> MaterialTheme.colorScheme.onPrimary
                            else -> Color.White.copy(alpha = .92f)
                        })
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(92.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            Box(Modifier.weight(1f, fill = false).width(80.dp).height(72.dp), contentAlignment = Alignment.Center) {
                RecentPhoto(latestPhoto, openPhoto)
            }
            val canShoot = state.isReady && !state.isCapturing && inFlight < 2 && loaded
            Box(Modifier.size(78.dp).border(2.dp, if (canShoot) Color.White else Color.Gray, CircleShape)
                .padding(7.dp).clip(CircleShape).background(if (canShoot) MaterialTheme.colorScheme.primary else Color.DarkGray)
                .clickable(enabled = canShoot, role = Role.Button) { model.shoot() }
                .semantics { contentDescription = "拍照" }, contentAlignment = Alignment.Center) {
                if (state.isCapturing) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                else if (inFlight > 0) Text("$inFlight / 2", fontFamily = FontFamily.Monospace,
                    color = if (canShoot) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp)
            }
            // Single gateway to every pro control (look/film/WB/shooting/composition).
            // The sheet's own scrim/drag handle dismisses it, so this is always "open".
            Column(Modifier.weight(1f, fill = false).width(80.dp).height(72.dp).clip(RoundedCornerShape(14.dp))
                .clickable(enabled = loaded, role = Role.Button) { onTab(AdjustmentTab.LOOK) }
                .semantics { contentDescription = "打开控制面板" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("调整", fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                // Live mode/shutter/ISO/EV live on the shoulder strip above, so the gateway
                // stays a clean labelled button instead of carrying a compressed readout.
                Text("参数", fontSize = 10.sp, color = Color.White.copy(alpha = .72f), maxLines = 1)
            }
        }
        }
    }
}

/**
 * DSLR-style shoulder readout directly above the shutter: mode / fixed aperture / shutter /
 * ISO / EV on one quiet monospace strip. In P and S the shutter and ISO are the camera's
 * ACTUAL live values from CaptureResult — never an "AUTO" placeholder, never a guessed
 * number; in M they are the dials. The mode letter and EV open small popups anchored on the
 * strip, so no extra control ever floats over the picture. Aperture appears only on devices
 * that actually report one. The green pip marks values driven by live metering.
 */
@Composable
private fun ExposureShoulderStrip(
    state: CameraState,
    model: CameraViewModel,
    modifier: Modifier = Modifier,
) {
    val caps = state.capabilities
    val capture = state.settings
    val mode = capture.exposureMode
    val live = state.liveExposure
    val shutterNs = if (mode == ExposureMode.MANUAL) capture.exposureTimeNs else live?.exposureTimeNs
    val iso = if (mode == ExposureMode.MANUAL) capture.iso else live?.iso
    val liveFresh = mode != ExposureMode.MANUAL && live != null &&
        (live.iso != null || live.exposureTimeNs != null)
    val ev = capture.exposureCompensationIndex * (caps?.exposureCompensationStepEv ?: 0.0)
    val modes = caps?.exposureModes ?: listOf(ExposureMode.PROGRAM)

    Row(modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = .42f))
        .horizontalScroll(rememberScrollState())
        .semantics(mergeDescendants = true) {}
        .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposureModeChip(mode, modes, enabled = state.isReady && modes.size > 1, onSelect = model::setExposureMode)
        caps?.fixedAperture?.let { aperture ->
            ShoulderText("f/%.1f".format(aperture), description = "固定光圈")
        }
        ShoulderText(shutterNs?.let(::shutterLabel) ?: "—", strong = true,
            description = if (liveFresh) "实时快门速度" else "快门速度")
        ShoulderText(iso?.let { "ISO $it" } ?: "—", strong = true,
            description = if (liveFresh) "实时感光度" else "感光度")
        if (liveFresh) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF54C97E))
                .semantics { contentDescription = "实时测光读数" })
        }
        ExposureEvChip(
            ev = ev,
            manual = mode == ExposureMode.MANUAL,
            enabled = state.isReady && mode != ExposureMode.MANUAL &&
                (caps?.let { it.exposureCompensationMin < it.exposureCompensationMax } == true),
            evIndex = capture.exposureCompensationIndex,
            evMin = caps?.exposureCompensationMin ?: 0,
            evMax = caps?.exposureCompensationMax ?: 0,
            onChange = model::setExposure,
        )
    }
}

@Composable
private fun ShoulderText(value: String, strong: Boolean = false, description: String) {
    Text(value, color = Color.White.copy(alpha = if (strong) .95f else .72f),
        fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
        fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.semantics { contentDescription = description })
}

/** Mode letter with a P/S/M popup (A never appears: phone apertures are fixed). */
@Composable
private fun ExposureModeChip(
    mode: ExposureMode,
    modes: List<ExposureMode>,
    enabled: Boolean,
    onSelect: (ExposureMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val modeHint = when (mode) {
        ExposureMode.PROGRAM -> "P 档：快门、ISO 自动"
        ExposureMode.SHUTTER_PRIORITY -> "S 档：设定快门，ISO 自动"
        ExposureMode.MANUAL -> "M 档：手动快门、ISO"
    }
    Box {
        Row(Modifier.clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
            .semantics {
                contentDescription = "拍摄档位，$modeHint"
                this.selected = true
            }.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(mode.label, color = Color.White.copy(alpha = if (enabled) .95f else .8f),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1)
            if (enabled) Text("▾", color = Color.White.copy(alpha = .6f), fontSize = 9.sp, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            modes.forEach { candidate ->
                val hint = when (candidate) {
                    ExposureMode.PROGRAM -> "快门、ISO 自动"
                    ExposureMode.SHUTTER_PRIORITY -> "设定快门，ISO 自动"
                    ExposureMode.MANUAL -> "手动快门、ISO"
                }
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    expanded = false
                    onSelect(candidate)
                }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(candidate.label, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (candidate == mode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface)
                    Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** EV readout that pops a small compensation scale anchored to the strip (never over the frame). */
@Composable
private fun ExposureEvChip(
    ev: Double,
    manual: Boolean,
    enabled: Boolean,
    evIndex: Int,
    evMin: Int,
    evMax: Int,
    onChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // At zero the strip shows just "EV"; a non-zero compensation reads as EV +0.3 etc.
    val label = when {
        manual -> "EV —"
        kotlin.math.abs(ev) > 0.001 -> "EV %+.1f".format(ev)
        else -> "EV"
    }
    Box {
        Text(label, color = Color.White.copy(alpha = if (enabled) .95f else .55f),
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            fontWeight = if (kotlin.math.abs(ev) > 0.001) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
                .semantics {
                    contentDescription = if (manual) "手动档不适用曝光补偿"
                    else "曝光补偿，当前 ${if (kotlin.math.abs(ev) > 0.001) "%+.1f EV".format(ev) else "0 EV"}"
                }.padding(horizontal = 4.dp, vertical = 2.dp))
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(Modifier.width(236.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("曝光补偿", Modifier.weight(1f), fontSize = 13.sp)
                    Text("%+.1f EV".format(ev), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                }
                Slider(value = evIndex.toFloat(),
                    onValueChange = { onChange(it.roundToInt()) },
                    valueRange = evMin.toFloat()..evMax.toFloat(),
                    steps = (evMax - evMin - 1).coerceAtLeast(0))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onChange(0) }, enabled = evIndex != 0) { Text("归零") }
                }
            }
        }
    }
}

/** One shallow, modal bottom sheet for every pro control; opens already on the chosen section. */
@Composable
private fun ControlBottomSheet(
    tab: AdjustmentTab,
    onDismiss: () -> Unit,
    onTab: (AdjustmentTab?) -> Unit,
    state: CameraState,
    settings: AppSettings,
    importing: Boolean,
    model: CameraViewModel,
    importLut: () -> Unit,
    openBodyPicker: () -> Unit,
    compositionState: CompositionUiState,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = Color.Transparent, scrimColor = Color.Black.copy(alpha = .55f),
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) }) {
        AdjustmentPanel(state, settings, importing, model, tab, onTab = onTab, importLut,
            openBodyPicker, compositionState,
            Modifier.fillMaxWidth().height(500.dp).navigationBarsPadding()
                .padding(horizontal = 10.dp).padding(bottom = 10.dp))
    }
}

@Composable
private fun ShootingControls(
    state: CameraState,
    settings: AppSettings,
    inFlight: Int,
    loaded: Boolean,
    importing: Boolean,
    model: CameraViewModel,
    modifier: Modifier,
    adjustmentTab: AdjustmentTab?,
    onTab: (AdjustmentTab?) -> Unit,
    sidePanel: Boolean,
    panelHeight: Dp,
    importLut: () -> Unit,
    openBodyPicker: () -> Unit,
    compositionState: CompositionUiState,
    latestPhoto: Uri?,
    openPhoto: (Uri) -> Unit,
) {
    val body = BodyCatalog.resolve(settings.cameraBody)
    val focalLengths = BodyCatalog.focalLengths(body)
    val capabilities = state.capabilities
    val supportedFocalLengths = capabilities?.focalLengthOptions.orEmpty()
    val minimumFocalWidth = maxOf(48.dp, (48f * LocalDensity.current.fontScale).dp)
    val focalHint = when {
        capabilities == null -> "焦段信息尚不可用"
        focalLengths.none { it in supportedFocalLengths } && state.settings.focalLength == FocalLength.NATIVE ->
            if (capabilities.nativeEquivalentFocalLengthMm == null) "焦段信息不可用 · 当前为原生视角"
            else "当前机型的焦段暂不可用 · 当前为原生视角"
        focalLengths.any { it !in supportedFocalLengths } -> "灰色视角暂不可用"
        else -> null
    }
    // The rail is an opaque camera-back surface: paint edge to edge under the system
    // bars, then inset the controls themselves. In portrait framed mode the status-bar
    // inset is already consumed upstream by the black top strip.
    Column(modifier.background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.systemBars)
        .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f)) {
                if (focalLengths.size == 1) {
                    Text("${focalLengths.first().label} 定焦",
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                } else {
                val distribute = maxWidth >= minimumFocalWidth * 3 + 8.dp
                Row(if (distribute) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    focalLengths.forEach { focal ->
                        val enabled = focal in supportedFocalLengths && state.isReady && loaded
                        val selected = enabled && state.settings.focalLength == focal
                        TextButton(onClick = { model.setFocalLength(focal) }, enabled = enabled,
                            modifier = (if (distribute) Modifier.weight(1f) else Modifier.widthIn(min = minimumFocalWidth))
                                .height(48.dp).semantics { this.selected = selected },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(10.dp)) {
                            Text(focal.label, Modifier.background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent,
                                RoundedCornerShape(7.dp)).padding(horizontal = 4.dp, vertical = 4.dp),
                                color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
                                else if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                }
            }
            TextButton(onClick = {
                onTab(if (adjustmentTab == AdjustmentTab.WHITE_BALANCE) null else AdjustmentTab.WHITE_BALANCE)
            }, enabled = loaded, modifier = Modifier.height(48.dp).widthIn(min = 56.dp)
                .semantics { contentDescription = "白平衡与漂移" }) {
                Text("WB", fontFamily = FontFamily.Monospace,
                    color = if (adjustmentTab == AdjustmentTab.WHITE_BALANCE) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Two independent optical diffusion layers with deliberately distinct names:
            // 柔光 = global soft diffusion (Black Pro-Mist style, hdfEnabled);
            // HDF = Ricoh-style highlight diffusion with outer halo (hdrEnabled).
            Box(Modifier.width(56.dp).height(48.dp).clip(RoundedCornerShape(10.dp))
                .background(if (settings.hdfEnabled) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
                .toggleable(value = settings.hdfEnabled, enabled = loaded, role = Role.Switch) { model.toggleHdf() }
                .semantics {
                    contentDescription = "柔光开关（全局柔化，黑柔滤镜风格）"
                    stateDescription = if (settings.hdfEnabled) "开启" else "关闭"
                }, contentAlignment = Alignment.Center) {
                Text("柔光", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    color = if (settings.hdfEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.width(56.dp).height(48.dp).clip(RoundedCornerShape(10.dp))
                .background(if (settings.hdrEnabled) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
                .toggleable(value = settings.hdrEnabled, enabled = loaded, role = Role.Switch) { model.toggleHdr() }
                .semantics {
                    contentDescription = "HDF 高光扩散开关（理光风格高光柔化与光晕）"
                    stateDescription = if (settings.hdrEnabled) "开启" else "关闭"
                }, contentAlignment = Alignment.Center) {
                Text("HDF", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    color = if (settings.hdrEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Same live shoulder readout as the phone capture screen (mode/aperture/shutter/ISO/EV).
        ExposureShoulderStrip(state, model, Modifier.fillMaxWidth().padding(vertical = 2.dp))
        if (focalHint != null) Text(focalHint, Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (adjustmentTab != AdjustmentTab.LOOK) {
            Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                // T121: film section only exists on bodies that allow it (GR has none).
                if (BodyCatalog.supportsFilmRolls(body)) {
                    // This chip opens the FILM section, so its empty-state label says 胶片.
                    ControlButton(FilmRegistry.find(settings.filmId)?.displayName ?: "胶片", settings.filmId != null,
                        enabled = loaded, onClick = { onTab(AdjustmentTab.FILM) })
                }
                // Keep the collapsed surface camera-like: the active body's own high-frequency
                // looks, never another brand's simulations.
                val quickPresets = buildList {
                    addAll(BodyCatalog.presets(body).take(3))
                    ColorPreset.entries.firstOrNull { it.name == settings.preset }?.let { selected ->
                        if (selected !in this) add(selected)
                    }
                }
                quickPresets.forEach { preset ->
                    ControlButton(preset.displayName, settings.filmId == null && settings.preset == preset.name,
                        enabled = loaded, onClick = { model.setPreset(preset) })
                }
            }
        }
        if (adjustmentTab != null) {
            AdjustmentPanel(state, settings, importing, model, adjustmentTab, onTab, importLut, openBodyPicker, compositionState,
                if (sidePanel) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth()
                    .height(panelHeight + if (adjustmentTab == AdjustmentTab.LOOK) 48.dp else 0.dp))
        } else if (sidePanel) Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().height(90.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            Box(Modifier.weight(1f, fill = false).width(80.dp).height(72.dp), contentAlignment = Alignment.Center) {
                RecentPhoto(latestPhoto, openPhoto)
            }
            val canShoot = state.isReady && !state.isCapturing && inFlight < 2 && loaded
            Box(Modifier.size(78.dp).border(2.dp, if (canShoot) Color.White else Color.Gray, CircleShape)
                .padding(7.dp).clip(CircleShape).background(if (canShoot) MaterialTheme.colorScheme.primary else Color.DarkGray)
                .clickable(enabled = canShoot, role = Role.Button) { model.shoot() }
                .semantics { contentDescription = "拍照" }, contentAlignment = Alignment.Center) {
                if (state.isCapturing) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                else if (inFlight > 0) Text("$inFlight / 2", fontFamily = FontFamily.Monospace,
                    color = if (canShoot) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp)
            }
            Column(Modifier.weight(1f, fill = false).width(80.dp).height(72.dp).clip(RoundedCornerShape(14.dp))
                .clickable(enabled = loaded, role = Role.Button) {
                    onTab(if (adjustmentTab == null) AdjustmentTab.LOOK else null)
                }.semantics { contentDescription = if (adjustmentTab == null) "打开调整面板" else "收起调整面板" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(if (adjustmentTab == null) "ADJ" else "收起", fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (adjustmentTab != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text("参数", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RecentPhoto(uri: Uri?, openPhoto: (Uri) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val cancellation = remember(uri, resolver) { CancellationSignal() }
    DisposableEffect(cancellation) { onDispose { cancellation.cancel() } }
    val thumbnail by produceState<ImageBitmap?>(null, uri, resolver) {
        value = null
        if (uri != null) {
            try {
                value = withContext(Dispatchers.IO) {
                    resolver.loadThumbnail(uri, Size(160, 160), cancellation).asImageBitmap()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The saved URI remains tappable even if its thumbnail is unavailable.
            }
        }
    }
    val outline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(9.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(1.dp, outline.copy(alpha = .25f), RoundedCornerShape(9.dp))
        .clickable(enabled = uri != null, role = Role.Button) { uri?.let(openPhoto) }
        .semantics { contentDescription = if (uri != null) "查看最近拍摄的照片" else "尚无照片" },
        contentAlignment = Alignment.Center) {
        thumbnail?.let { bitmap ->
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } ?: Canvas(Modifier.size(24.dp)) {
            drawRect(outline, style = Stroke(1.5.dp.toPx()))
            drawCircle(outline, 2.dp.toPx(), Offset(size.width * .7f, size.height * .3f))
            drawLine(outline, Offset(0f, size.height * .8f), Offset(size.width * .35f, size.height * .45f), 1.5.dp.toPx())
            drawLine(outline, Offset(size.width * .35f, size.height * .45f), Offset(size.width * .8f, size.height), 1.5.dp.toPx())
        }
    }
}

@Composable
private fun ControlButton(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.height(48.dp).widthIn(min = 48.dp)
            .semantics { this.selected = selected },
        shape = RoundedCornerShape(10.dp)) {
        Text(label, Modifier.background(
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent,
            RoundedCornerShape(7.dp)).padding(horizontal = 5.dp, vertical = 4.dp),
            color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
            else if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AdjustmentPanel(
    state: CameraState,
    settings: AppSettings,
    importing: Boolean,
    model: CameraViewModel,
    tab: AdjustmentTab,
    onTab: (AdjustmentTab?) -> Unit,
    importLut: () -> Unit,
    openBodyPicker: () -> Unit,
    compositionState: CompositionUiState,
    modifier: Modifier,
) {
    val contentScroll = rememberScrollState()
    LaunchedEffect(tab) { contentScroll.scrollTo(0) }
    // T121: the 胶片 tab exists only on bodies that have a film section (理光 GR does not).
    val activeBody = BodyCatalog.resolve(settings.cameraBody)
    val tabs = buildList {
        add(AdjustmentTab.LOOK)
        if (BodyCatalog.supportsFilmRolls(activeBody)) add(AdjustmentTab.FILM)
        add(AdjustmentTab.WHITE_BALANCE)
        add(AdjustmentTab.SHOOTING)
    }
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
            tabs.forEach { item ->
                ControlButton(item.label, item == tab, onClick = { onTab(item) })
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(contentScroll).padding(horizontal = 12.dp)) {
            when (tab) {
                AdjustmentTab.LOOK -> LookAdjustments(settings, importing, model, importLut)
                AdjustmentTab.FILM -> if (BodyCatalog.supportsFilmRolls(activeBody)) FilmAdjustments(settings, model)
                    else LookAdjustments(settings, importing, model, importLut)
                AdjustmentTab.WHITE_BALANCE -> WhiteBalanceAdjustments(state, settings, model)
                AdjustmentTab.SHOOTING -> ShootingAdjustments(state, settings, model, openBodyPicker, compositionState)
            }
        }
    }
}

@Composable
private fun LookAdjustments(settings: AppSettings, importing: Boolean, model: CameraViewModel, importLut: () -> Unit) {
    CameraLookPicker(settings,
        onBody = { model.setCameraBody(BodyCatalog.id(it)) },
        onPreset = model::setPreset)
    var parameter by remember { mutableStateOf(LookParameter.SATURATION) }
    val look = settings.activeLook
    val value = when (parameter) {
        LookParameter.INTENSITY -> look.intensity
        LookParameter.SATURATION -> look.saturation
        LookParameter.HUE -> look.hue
        LookParameter.CONTRAST -> look.contrast
        LookParameter.BRIGHTNESS -> look.brightness
        LookParameter.HIGHLIGHTS -> look.highlights
        LookParameter.SHADOWS -> look.shadows
        LookParameter.SHARPNESS -> look.sharpness
        LookParameter.CLARITY -> look.clarity
        LookParameter.GRAIN -> look.grain
        LookParameter.VIGNETTE -> look.vignette
        LookParameter.DIFFUSION -> settings.hdfStrength
    }
    val setValue: (Float) -> Unit = { amount ->
        when (parameter) {
            LookParameter.INTENSITY -> model.setColor(intensity = amount)
            LookParameter.SATURATION -> model.setColor(saturation = amount)
            LookParameter.HUE -> model.setColor(hue = amount)
            LookParameter.CONTRAST -> model.setColor(contrast = amount)
            LookParameter.BRIGHTNESS -> model.setColor(brightness = amount)
            LookParameter.HIGHLIGHTS -> model.setColor(highlights = amount)
            LookParameter.SHADOWS -> model.setColor(shadows = amount)
            LookParameter.SHARPNESS -> model.setColor(sharpness = amount)
            LookParameter.CLARITY -> model.setColor(clarity = amount)
            LookParameter.GRAIN -> model.setColor(grain = amount)
            LookParameter.VIGNETTE -> model.setColor(vignette = amount)
            LookParameter.DIFFUSION -> model.setHdfStrength(amount)
        }
    }
    Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically) {
        LookParameter.entries.forEach { item ->
            ControlButton(item.label, item == parameter, onClick = { parameter = item })
        }
    }
    if (parameter == LookParameter.DIFFUSION) {
        // The optical layers are global (not per-look color parameters), so the chip
        // opens a dedicated two-layer section instead of the single generic slider.
        DiffusionAdjustments(settings, model)
        return
    }
    val range = if (parameter.signed) -1f..1f else 0f..1f
    val valueLabel = if (parameter.signed) "%+d".format((value * 4).roundToInt()) else "${(value * 100).roundToInt()}%"
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(parameter.label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(valueLabel, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium)
    }
    val step = if (parameter.signed) .25f else .1f
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", "降低${parameter.label}", value > range.start) { setValue((value - step).coerceIn(range)) }
        Slider(value = value.coerceIn(range), onValueChange = setValue, valueRange = range,
            steps = if (parameter.signed) 7 else 9, modifier = Modifier.weight(1f)
                .semantics { contentDescription = parameter.label })
        StepButton("+", "提高${parameter.label}", value < range.endInclusive) { setValue((value + step).coerceIn(range)) }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = importLut, enabled = !importing, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (importing) "导入中…" else "导入 LUT", fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = model::resetCurrentLook, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("重置当前影调", fontSize = 12.sp)
        }
    }
    Text(if (settings.filmId != null) "胶片 · ${FilmRegistry.find(settings.filmId)?.displayName.orEmpty()} · 独立记忆参数"
        else if (settings.preset == ColorPreset.CUSTOM.name && settings.lutName != null) "LUT · ${settings.lutName}"
        else "每套影调单独记忆参数。",
        Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * The two software-simulated optical diffusion layers, both global across looks/films:
 * Two independent software-simulated optical diffusion layers, both global across
 * looks/films: 柔光 (hdfEnabled) softens the whole frame like a Black Pro-Mist; HDF
 * (hdrEnabled, Ricoh Highlight Diffusion Filter style) blooms only the near-clipping
 * highlights with an outer halo. Both carry strength sliders (T116); the halo layer keeps
 * its original HDF name — the T116 rename to 光晕 was reverted in T118.
 */
@Composable
private fun DiffusionAdjustments(settings: AppSettings, model: CameraViewModel) {
    DiffusionStrengthRow(
        title = "柔光", hint = "全局柔化 · 黑柔滤镜风格，对整张画面生效",
        enabled = settings.hdfEnabled, strength = settings.hdfStrength,
        toggleDescription = "柔光开关（全局柔化，黑柔滤镜风格）",
        sliderDescription = "柔光强度",
        onToggle = model::toggleHdf, onStrength = model::setHdfStrength)
    DiffusionStrengthRow(
        title = "HDF", hint = "理光高光扩散 · 只在接近过曝的高光上柔化并形成外圈辉光",
        enabled = settings.hdrEnabled, strength = settings.hdrStrength,
        toggleDescription = "HDF 高光扩散开关（理光风格高光柔化与光晕）",
        sliderDescription = "HDF 强度",
        onToggle = model::toggleHdr, onStrength = model::setHdrStrength)
    Text("柔光与 HDF 对所有影调 / 胶片生效，强度各自独立记忆；拖动强度会自动开启。",
        Modifier.padding(top = 2.dp, bottom = 10.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DiffusionStrengthRow(
    title: String,
    hint: String,
    enabled: Boolean,
    strength: Float,
    toggleDescription: String,
    sliderDescription: String,
    onToggle: () -> Unit,
    onStrength: (Float) -> Unit,
) {
    val range = 0f..1f
    val step = .1f
    // Adjusting strength while the layer is off is an implicit enable.
    val adjust: (Float) -> Unit = { amount ->
        onStrength(amount.coerceIn(range))
        if (!enabled) onToggle()
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
            Spacer(Modifier.weight(1f))
            Box(Modifier.height(30.dp).clip(RoundedCornerShape(9.dp))
                .background(if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = .16f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                .toggleable(value = enabled, role = Role.Switch) { onToggle() }
                .semantics {
                    contentDescription = toggleDescription
                    stateDescription = if (enabled) "已开启" else "已关闭"
                }
                .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text(if (enabled) "已开启" else "已关闭", fontSize = 12.sp,
                    color = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${(strength * 100).roundToInt()}%", Modifier.width(46.dp).padding(start = 8.dp),
                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
        }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", "降低$sliderDescription", strength > range.start) { adjust(strength - step) }
            Slider(value = strength.coerceIn(range), onValueChange = adjust, valueRange = range,
                steps = 9, modifier = Modifier.weight(1f).graphicsLayer { alpha = if (enabled) 1f else .45f }
                    .semantics { contentDescription = sliderDescription })
            StepButton("+", "提高$sliderDescription", strength < range.endInclusive) { adjust(strength + step) }
        }
        Text(hint, Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WhiteBalanceAdjustments(state: CameraState, settings: AppSettings, model: CameraViewModel) {
    val modes = state.capabilities?.whiteBalanceModes.orEmpty()
    Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically) {
        modes.forEach { mode ->
            ControlButton(mode.displayName, state.settings.whiteBalance == mode,
                enabled = state.isReady, onClick = { model.setWhiteBalance(mode) })
        }
        if (modes.isEmpty()) Text("连接相机后显示白平衡选项", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 280.dp || fontScale > 1.4f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                WhiteBalancePad(settings.whiteBalanceAmber, settings.whiteBalanceGreen,
                    { amber, green -> model.setWhiteBalanceShift(amber, green) },
                    Modifier.fillMaxWidth().height(156.dp))
                WhiteBalanceSteps(settings, model, Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WhiteBalancePad(settings.whiteBalanceAmber, settings.whiteBalanceGreen,
                    { amber, green -> model.setWhiteBalanceShift(amber, green) }, Modifier.weight(1f).height(140.dp))
                WhiteBalanceSteps(settings, model, Modifier.width(148.dp))
            }
        }
    }
    Text("A 暖 · B 冷    G 绿 · M 洋红", Modifier.padding(bottom = 10.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WhiteBalanceSteps(settings: AppSettings, model: CameraViewModel, modifier: Modifier) {
    Column(modifier) {
        DriftAxis("A / B", settings.whiteBalanceAmber, "往蓝偏移", "往琥珀偏移",
            { model.setWhiteBalanceShift(amber = it) })
        DriftAxis("G / M", settings.whiteBalanceGreen, "往洋红偏移", "往绿偏移",
            { model.setWhiteBalanceShift(green = it) })
        TextButton(onClick = model::resetWhiteBalanceShift, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("重置漂移", fontSize = 12.sp)
        }
    }
}

@Composable
private fun WhiteBalancePad(amber: Int, green: Int, onChange: (Int, Int) -> Unit, modifier: Modifier) {
    val latestOnChange by rememberUpdatedState(onChange)
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.semantics {
        contentDescription = "白平衡漂移坐标"
        stateDescription = "琥珀蓝轴 $amber，绿洋红轴 $green"
    }) {
        Canvas(Modifier.fillMaxSize().padding(16.dp).clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun update(position: Offset) {
                        val inset = 7.dp.toPx()
                        val a = ((position.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f) * 18f - 9f)
                            .roundToInt().coerceIn(-9, 9)
                        val g = (9f - (position.y - inset) / (size.height - 2 * inset).coerceAtLeast(1f) * 18f)
                            .roundToInt().coerceIn(-9, 9)
                        latestOnChange(a, g)
                    }
                    update(down.position)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.firstOrNull()?.let { change ->
                            update(change.position)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            }) {
            drawRect(Brush.horizontalGradient(listOf(Color(0xFF244365), Color(0xFF66502F))))
            drawRect(Brush.verticalGradient(listOf(Color(0x5538834E), Color.Transparent, Color(0x555D326A))))
            val inset = 7.dp.toPx()
            val width = (size.width - 2 * inset).coerceAtLeast(1f)
            val height = (size.height - 2 * inset).coerceAtLeast(1f)
            for (i in 0..6) {
                val line = Color.White.copy(alpha = if (i == 3) .4f else .13f)
                val x = inset + width * i / 6
                val y = inset + height * i / 6
                drawLine(line, Offset(x, inset), Offset(x, inset + height), 1.dp.toPx())
                drawLine(line, Offset(inset, y), Offset(inset + width, y), 1.dp.toPx())
            }
            val point = Offset(inset + (amber + 9) / 18f * width, inset + (9 - green) / 18f * height)
            drawCircle(Color.Black.copy(alpha = .6f), 7.dp.toPx(), point)
            drawCircle(Color.White, 5.dp.toPx(), point)
            drawCircle(accent, 2.dp.toPx(), point)
        }
        Text("G", Modifier.align(Alignment.TopCenter), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text("M", Modifier.align(Alignment.BottomCenter), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text("B", Modifier.align(Alignment.CenterStart), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text("A", Modifier.align(Alignment.CenterEnd), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

@Composable
private fun DriftAxis(label: String, value: Int, minusLabel: String, plusLabel: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", minusLabel, value > -9) { onChange((value - 1).coerceAtLeast(-9)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%+d".format(value), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        StepButton("+", plusLabel, value < 9) { onChange((value + 1).coerceAtMost(9)) }
    }
}

@Composable
private fun StepButton(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)
        .semantics { contentDescription = description }) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
    }
}

@Composable
private fun ShootingAdjustments(state: CameraState, settings: AppSettings, model: CameraViewModel,
    openBodyPicker: () -> Unit, compositionState: CompositionUiState) {
    val controlsRestored by model.controlsRestored.collectAsStateWithLifecycle()
    val poseState by model.pose.state.collectAsStateWithLifecycle()
    val capabilities = state.capabilities
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("拍摄参数", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = model::resetShootingParameters, enabled = state.isReady,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "复位拍摄参数" }) { Text("复位", fontSize = 12.sp) }
    }
    Text("复位：自动曝光、EV 归零；设备支持时恢复 AF，保留影调与白平衡。",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (capabilities != null && controlsRestored) {
        val exposureMode = state.settings.exposureMode
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ControlButton("P", exposureMode == ExposureMode.PROGRAM, enabled = state.isReady,
                onClick = { model.setExposureMode(ExposureMode.PROGRAM) })
            ControlButton("A", false, enabled = false, onClick = {})
            ControlButton("S", exposureMode == ExposureMode.SHUTTER_PRIORITY,
                enabled = state.isReady && capabilities.supportsShutterPriority,
                onClick = { model.setExposureMode(ExposureMode.SHUTTER_PRIORITY) })
            ControlButton("M", exposureMode == ExposureMode.MANUAL,
                enabled = state.isReady && capabilities.supportsManualExposure,
                onClick = { model.setExposureMode(ExposureMode.MANUAL) })
        }
        Text(when (exposureMode) {
            ExposureMode.PROGRAM -> "P · 快门、ISO 自动"
            ExposureMode.SHUTTER_PRIORITY -> "S · 设定快门，ISO 自动"
            ExposureMode.MANUAL -> "M · 手动设定快门、ISO"
        }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val fixedAperture = capabilities.fixedAperture
        Text(if (fixedAperture != null) "固定光圈 f/${"%.1f".format(fixedAperture)} · A 档不可用"
            else "当前镜头不支持光圈控制 · A 档不可用",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val minimum = capabilities.exposureCompensationMin
        val maximum = capabilities.exposureCompensationMax
        if (minimum < maximum) {
            val ev = state.settings.exposureCompensationIndex * capabilities.exposureCompensationStepEv
            AdjustmentSlider("曝光补偿", "%+.1f EV".format(ev), state.settings.exposureCompensationIndex.toFloat(),
                minimum.toFloat()..maximum.toFloat(), enabled = exposureMode != ExposureMode.MANUAL,
                steps = (maximum - minimum - 1).coerceAtLeast(0),
                onChange = { model.setExposure(it.roundToInt()) })
        }
        val isoMin = capabilities.isoMin
        val isoMax = capabilities.isoMax
        val timeMin = capabilities.exposureTimeMinNs
        val timeMax = capabilities.exposureTimeMaxNs
        if (exposureMode != ExposureMode.PROGRAM && timeMin != null && timeMax != null) {
            val iso = state.settings.iso ?: 200
            val shutter = state.settings.exposureTimeNs ?: 8_000_000L
            if (exposureMode == ExposureMode.MANUAL && isoMin != null && isoMax != null) {
                AdjustmentSlider("ISO", iso.toString(), iso.toFloat().coerceIn(isoMin.toFloat(), isoMax.toFloat()),
                    isoMin.toFloat()..isoMax.toFloat(), onChange = { model.setManualExposure(it.roundToInt(), shutter) })
            }
            val minLog = kotlin.math.log10(timeMin.toDouble()).toFloat()
            val maxLog = kotlin.math.log10(min(timeMax, 1_000_000_000L).toDouble()).toFloat()
            if (minLog < maxLog) AdjustmentSlider("快门", shutterLabel(shutter),
                kotlin.math.log10(shutter.toDouble()).toFloat().coerceIn(minLog, maxLog), minLog..maxLog,
                onChange = {
                    val exposure = Math.pow(10.0, it.toDouble()).toLong().coerceIn(timeMin, min(timeMax, 1_000_000_000L))
                    if (exposureMode == ExposureMode.SHUTTER_PRIORITY) model.setShutterPriority(exposure)
                    else model.setManualExposure(iso, exposure)
                })
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
        Text("对焦", style = MaterialTheme.typography.titleSmall)
        val manual = state.settings.focusDistanceDiopters != null
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            ControlButton("AF 自动", !manual && capabilities.autoFocusMode != null,
                enabled = state.isReady && capabilities.autoFocusMode != null, onClick = model::autoFocus)
            ControlButton("MF 预设", manual, enabled = state.isReady && capabilities.supportsManualFocus,
                onClick = { model.setManualFocus(settings.focusDiopters) })
        }
        Text(if (manual) "焦点保持预设距离；点 AF 自动恢复自动对焦。"
            else if (capabilities.supportsFocusMetering) "点按画面对焦 · 长按锁定 · 点 AF 解除锁定"
            else "当前设备不支持点按对焦。", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (capabilities.supportsManualFocus && (capabilities.minimumFocusDistanceDiopters ?: 0f) > 0f) {
            if (manual) {
                val distance = state.settings.focusDistanceDiopters ?: 0f
                AdjustmentSlider("焦点", if (distance < .01f) "∞" else "%.2f D".format(distance), distance,
                    0f..capabilities.minimumFocusDistanceDiopters!!, onChange = model::setManualFocus)
            }
        }
    }
    // T121: 快拍模式 is the master simplicity switch; the toggle also lives on the
    // viewfinder bottom-left, mirrored here so the sheet stays a complete settings surface.
    ToggleRow("快拍模式", settings.quickShotMode) { model.setQuickShotMode(it) }
    Text("开启后只保留拍照、滤镜、HDF 与基础拍摄参数；姿势引导、构图辅助与 AI 建议全部隐藏且不读取画面。",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ToggleRow("三分网格", settings.grid) { model.toggleGrid() }
    ToggleRow("水平仪", settings.level) { model.toggleLevel() }
    if (settings.quickShotMode) {
        Text("专业功能（姿势引导、构图辅助、影调与曝光建议）已随快拍模式关闭；关掉快拍模式即可恢复。",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        ToggleRow("影调建议", settings.lookAdviceEnabled) { model.setLookAdviceEnabled(it) }
        Text("根据取景画面的光线与色彩，偶尔推荐合适的胶片或影调；只建议，不会自动更改。点取景器卡片可试用，也能一键还原。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleRow("曝光提示", settings.exposureAdviceEnabled) { model.setExposureAdviceEnabled(it) }
        Text("高光大面积过亮或暗部太黑时给出 EV 调整方向，明暗反差都大时提示开启 HDF；只建议，不会自动改曝光。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CompositionAssistantControls(compositionState, settings, model, state)
        PoseAssistantControls(poseState, settings, model)
    }
    TextButton(onClick = openBodyPicker, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("相机机型与铭牌设置", fontSize = 12.sp)
    }
    Text("参数自动记忆。音量键可直接拍照。", Modifier.padding(vertical = 10.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PoseAssistantControls(
    state: PoseUiState,
    settings: AppSettings,
    model: CameraViewModel,
) {
    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
    Text("姿势助手 · 实验性", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("火柴人姿势引导", style = MaterialTheme.typography.bodyMedium)
            Text(when (state.modelStatus) {
                PoseModelStatus.INSTALLED -> "实时识别已就绪 · 白骨架贴合人体，琥珀色小人示范目标姿势"
                PoseModelStatus.DOWNLOADING -> "正在下载实时识别模型…"
                PoseModelStatus.FAILED -> "实时识别模型不可用 · 当前为演示模式"
                PoseModelStatus.CHECKING -> "正在检查模型…"
                PoseModelStatus.ABSENT -> "未装模型时为演示模式：只浏览姿势，不读取画面"
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(settings.poseAssistantEnabled, { model.setPoseAssistantEnabled(it) },
            modifier = Modifier.semantics { contentDescription = "姿势助手开关" })
    }
    Text("对准一个人，白骨架会贴合 ta 的身体；琥珀色小人指出手、腰、头该怎么摆，底部小字告诉你差在哪里。摆对保持两秒自动换下一个姿势。",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    when (state.modelStatus) {
        PoseModelStatus.CHECKING -> Text("检查模型安装状态…", style = MaterialTheme.typography.labelSmall)
        PoseModelStatus.ABSENT -> {
            Text("实时识别模型：${formatBytes(PoseModelCatalog.lite.bytes)} · 仅下载到本机，不随 App 打包，可随时删除",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = model::installPoseModel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("下载实时识别模型（${formatBytes(PoseModelCatalog.lite.bytes)}）")
            }
        }
        PoseModelStatus.DOWNLOADING -> {
            val total = state.totalBytes.coerceAtLeast(1L)
            val progress = (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
            androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("下载 ${formatBytes(state.downloadedBytes)} / ${formatBytes(total)}", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = model::cancelPoseInstall) { Text("取消") }
            }
        }
        PoseModelStatus.INSTALLED -> {
            Text("实时识别模型已安装 · ${formatBytes(PoseModelCatalog.lite.bytes)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = model::removePoseModel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("删除本地模型")
            }
        }
        PoseModelStatus.FAILED -> {
            Text(state.installError ?: "模型下载失败", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = model::installPoseModel, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
        }
    }
    Text("模型来自 Google MediaPipe（Apache-2.0），所有计算都在手机本地完成，画面不上传、不保存。",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun CompositionAssistantControls(
    state: CompositionUiState,
    settings: AppSettings,
    model: CameraViewModel,
    cameraState: CameraState,
) {
    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
    Text("构图辅助 · 实验性", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("推荐构图框", style = MaterialTheme.typography.bodyMedium)
            Text(when (state.status) {
                AssistStatus.OFF -> "关闭，不读取取景画面"
                AssistStatus.UNINSTALLED -> "画面分析可用，AI 模型未安装"
                AssistStatus.PAUSED -> "后台已暂停"
                AssistStatus.LOADING -> "加载本地模型…"
                AssistStatus.READY -> state.statusText ?: "点取景器上的构图，分析当前画面"
                AssistStatus.FAILED -> state.statusText ?: "本地推理失败"
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(settings.compositionEnabled, { model.setCompositionEnabled(it) },
            modifier = Modifier.semantics { contentDescription = "构图辅助开关" })
    }
    ToggleRow("实时构图 · 自动更新建议", settings.compositionRealtime, model::setCompositionRealtime)
    ToggleRow("到位轻震反馈", settings.alignmentHapticEnabled) { model.setAlignmentHapticEnabled(it) }
    Text("按建议移动、推荐框与当前取景重合时，框线短暂变绿并出现小勾；开启此项再附带一次极轻震动，全程无声。",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(compositionTimingLabel(state), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.status == AssistStatus.READY && settings.compositionRealtime) Text(
        "画面分析约每秒更新 · AI 完成后至少间隔 ${formatInferenceTime(state.sampleIntervalMs)}，画面未变则复用",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // Only one ranker ships now; show its real name instead of a picker (legacy SmolVLM
    // selections migrate to it automatically).
    val modelEntry = dev.streetsnap.app.model.D1ModelCatalog.find(settings.compositionModel)
        ?: dev.streetsnap.app.model.D1ModelCatalog.find(dev.streetsnap.app.model.D1ModelCatalog.DEFAULT_MODEL_ID)
    Text("模型：${modelEntry?.title ?: settings.compositionModel}",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // Inference performance stays visible without opening the developer section: users
    // asked to see how slow/fast the on-device model actually is on their phone.
    InferencePerformanceSummary(state, state.install == ModelInstallStatus.INSTALLED)
    DeveloperDiagnostics(state, cameraState, modelEntry)
    when (state.install) {
        ModelInstallStatus.CHECKING -> Text("检查模型安装状态…", style = MaterialTheme.typography.labelSmall)
        ModelInstallStatus.ABSENT -> TextButton(onClick = model.composition::prepareInstall,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看安装信息") }
        ModelInstallStatus.PREPARING -> Text("读取模型清单…", style = MaterialTheme.typography.labelSmall)
        ModelInstallStatus.AVAILABLE -> {
            Text("下载量：${formatBytes(state.bytes ?: 0L)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = model.composition::install, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("明确安装 ${formatBytes(state.bytes ?: 0L)}")
            }
        }
        ModelInstallStatus.DOWNLOADING -> {
            val total = state.bytes ?: 1L
            val progress = (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
            androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("下载 ${formatBytes(state.downloadedBytes)} / ${formatBytes(total)}", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = model.composition::cancelInstall) { Text("取消") }
            }
        }
        ModelInstallStatus.INSTALLED -> {
            Text("已安装 · ${formatBytes(state.bytes ?: 0L)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = model.composition::removeModel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("删除本地模型")
            }
        }
        ModelInstallStatus.REMOVING -> Text("删除本地模型…", style = MaterialTheme.typography.labelSmall)
        ModelInstallStatus.FAILED -> {
            Text(state.installError ?: "模型操作失败", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = model.composition::prepareInstall, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
        }
    }
    Text("点“构图”分析一次，也可开启实时。先显示画面分析建议，AI 比较候选后再择优；框仅供取景参考，照片不会自动裁切。",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp))
}

/**
 * Always-visible on-device model performance: rolling wall-clock of the last ten
 * rankings (success/timeout/failure all count) plus the cheap rule analysis. The
 * same numbers used to be buried inside the collapsed developer section.
 */
@Composable
private fun InferencePerformanceSummary(state: CompositionUiState, installed: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text("推理性能", fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        val history = state.inferenceHistoryMs
        when {
            history.isNotEmpty() -> {
                val sorted = history.sorted()
                Text("AI 推理：最近 ${history.last()} ms · 中位 ${sorted[sorted.size / 2]} ms · 最小 ${sorted.first()} / 最大 ${sorted.last()}（${history.size} 次）",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f))
            }
            installed -> Text("AI 推理：等待首次推理完成…",
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Text("AI 推理：安装模型后显示实测耗时",
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.fastAdviceMs?.let {
            Text("画面分析：$it ms", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f))
        }
    }
}

/**
 * Collapsed by default. Groups the real inference pipeline, per-pass performance, the
 * token/TTFT contract (d1 is a discriminative single forward — both stated, not faked),
 * model details, process-lifetime counters, process memory (CPU inference has no separate
 * device memory) and the camera/HAL readings. Every number is observed state; architecture
 * lines describe the shipping graph. Nothing is estimated or fabricated.
 */
@Composable
private fun DeveloperDiagnostics(
    state: CompositionUiState,
    cameraState: CameraState,
    modelEntry: dev.streetsnap.app.model.D1ModelEntry?,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(role = Role.Button) { expanded = !expanded }
        .semantics { contentDescription = if (expanded) "收起开发者诊断" else "展开开发者诊断" },
        verticalAlignment = Alignment.CenterVertically) {
        Text("开发者诊断", Modifier.weight(1f), fontSize = 12.sp)
        Text(if (expanded) "收起 ▴" else "展开 ▾", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) {
        val stats by InferenceStats.snapshots.collectAsStateWithLifecycle()
        // One 1 s tick while expanded: refreshes the memory sample and the live foreground
        // duration. PSS sampling itself stays off the steady-state (collapsed) path.
        var memory by remember { mutableStateOf<ProcessMemorySnapshot?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                memory = ProcessMemoryProbe.sample()
                delay(1_000L)
            }
        }
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        Column(Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DiagSection("推理链路")
            Text(
                "分析帧 640×480（KEEP_ONLY_LATEST）→ 96px 统计与规则候选 → A/B/C 合成 512² sheet" +
                    " → 视觉塔 FP16（1024 patches）→ 文本嵌入 Q4 → 决策头 Q4 输出 3 logits" +
                    " → 换序双轮一致性门 → margin ≥ 0.10 采纳，否则弃权。单飞串行，慢则降频不排队。",
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f))

            DiagSection("推理性能")
            DiagLine("画面分析", (state.fastAdviceMs?.let { "$it ms" } ?: "—") + " · 96px 规则层")
            val segments = listOf(
                "视觉塔 ${state.lastVisionMs?.let { ms -> formatInferenceTime(ms) } ?: "—"}",
                "嵌入 ${state.lastEmbeddingsMs?.let { ms -> formatInferenceTime(ms) } ?: "—"}",
                "决策头 ${state.lastDecisionMs?.let { ms -> formatInferenceTime(ms) } ?: "—"}",
            ).joinToString(" · ")
            DiagLine("分段耗时", segments + "（最近一次首轮）")
            DiagLine("总耗时", (state.lastInferenceMs?.let { formatInferenceTime(it) } ?: "—") +
                " · 含换序一致性轮")
            val history = state.inferenceHistoryMs
            if (history.isNotEmpty()) {
                val sorted = history.sorted()
                DiagLine("最近 10 次",
                    "中位 ${sorted[sorted.size / 2]} · 最小 ${sorted.first()} / 最大 ${sorted.last()} ms")
            } else {
                DiagLine("最近 10 次", "尚无完成记录")
            }
            DiagLine("首结果延迟", stats.firstRankMs?.let { formatInferenceTime(it) } ?: "—")
            DiagLine("调度", "预算 %.0f s · 冷却 %d ms".format(state.inferenceBudgetMs / 1000f, state.sampleIntervalMs))
            state.lastOutcome?.let { DiagLine("上次结果", when (it) {
                CompositionOutcome.RETURNED -> "已选择构图"
                CompositionOutcome.NO_RESULT -> "模型未明确选择"
                CompositionOutcome.UNSTABLE_SELECTION -> "一致性检查未过"
                CompositionOutcome.TIMED_OUT -> "超时"
                CompositionOutcome.SCENE_CHANGED -> "画面变化，旧结果忽略"
                CompositionOutcome.FAILED -> "失败"
            }) }

            DiagSection("模型情况")
            DiagLine("模型", "${modelEntry?.id ?: "—"} · ${modelEntry?.revision?.take(7) ?: "—"}")
            DiagLine("量化构成", "视觉塔 FP16 · 嵌入 Q4 · 决策头 Q4")
            DiagLine("磁盘占用", (state.bytes?.let { formatBytes(it) } ?: "—") + " · 清单校验字节")
            DiagLine("后端", state.runtimeLabel ?: "会话未就绪")
            DiagLine("建图耗时", (state.runtimeReadyMs?.let { "$it ms" } ?: "—") + " · 三个 ORT session")
            DiagLine("运行态", state.status.name + (state.statusText?.let { " · $it" } ?: ""))
            DiagLine("输入 tokens", (state.lastPromptTokens?.let { "$it · 固定 prompt comp-choice-v0" }
                ?: "—（完成一次推理后显示）"))
            DiagLine("输出 tokens", "0 · 判别式输出 3 个选项 logits，不生成文本")
            DiagLine("TTFT", "不适用 · 单次前向无 token 解码，亦无 KV cache；响应延迟见上方首结果延迟")

            DiagSection("本次启动统计")
            DiagLine("排名作业", "完成 ${stats.rankingsFinished} · 采纳 ${stats.adopted}" +
                " / 弃权 ${stats.abstained} / 超时 ${stats.timedOut} / 失败 ${stats.failed}")
            DiagLine("采纳率", stats.adoptionRate?.let { "%.0f%%".format(it * 100f) } ?: "—")
            DiagLine("平均耗时",
                (stats.averageRankMs?.let { formatInferenceTime(it) } ?: "—") + " · 每次排名墙钟均值")
            val currentSessionMs = with(InferenceStats) { currentForegroundDurationMs(nowElapsed) }
            DiagLine("前台会话", "${stats.foregroundSessions + if (currentSessionMs != null) 1 else 0} 次" +
                " · 平均 ${stats.averageForegroundMs?.let { formatInferenceTime(it) } ?: "—"}" +
                (currentSessionMs?.let { cur -> " · 本次 ${formatInferenceTime(cur)}" } ?: ""))

            DiagSection("进程内存（CPU 推理，无独立显存）")
            memory?.let { mem ->
                DiagLine("PSS", "总 ${formatMemoryKb(mem.totalPssKb.toLong())}" +
                    " · native ${formatMemoryKb(mem.nativePssKb.toLong())}" +
                    " · Java ${formatMemoryKb(mem.dalvikPssKb.toLong())}")
                DiagLine("native 堆", formatMemoryKb(mem.nativeAllocatedKb) +
                    " · 含 ORT arena 与 MediaPipe，每秒刷新")
            } ?: DiagLine("内存", "采样中…")

            DiagSection("相机 / HAL")
            val live = cameraState.liveExposure
            if (live != null) {
                DiagLine("实时测光", buildString {
                    append(live.iso?.let { iso -> "ISO $iso" } ?: "ISO —")
                    append(" · ")
                    append(live.exposureTimeNs?.let(::shutterLabel) ?: "快门 —")
                    live.aperture?.let { append(" · f/%.1f".format(it)) }
                    live.focalLengthMm?.let { append(" · 实际焦距 %.1f mm".format(it)) }
                })
            } else {
                DiagLine("实时测光", "尚无相机读数")
            }
            val faces = cameraState.liveFaces
            if (faces != null) {
                DiagLine("人脸统计", "HAL 报告 ${faces.count} 张脸" +
                    if (cameraState.settings.focusDistanceDiopters != null) " · 手动对焦下已停用" else "")
            } else {
                DiagLine("人脸统计", "无读数（手动对焦或会话未就绪）")
            }
            cameraState.capabilities?.let { caps ->
                if (caps.supportsFaceDetection) {
                    val eyeNote = if (caps.faceDetectMode == 2) " · 含眼部点位" else ""
                    DiagLine("人脸检测", "HAL 模式 ${caps.faceDetectMode}$eyeNote · 最多 ${caps.maxFaceCount} 张")
                } else {
                    DiagLine("人脸检测", "该设备 HAL 未上报人脸统计能力")
                }
                DiagLine("曝光档", caps.exposureModes.joinToString(" / ") { it.label } +
                    if (caps.fixedAperture != null) " · 固定 f/%.1f".format(caps.fixedAperture) else " · 无光圈档")
                DiagLine("ISO 范围", "${caps.isoMin ?: "—"}–${caps.isoMax ?: "—"}")
                val timeMin = caps.exposureTimeMinNs?.let(::shutterLabel) ?: "—"
                val timeMax = caps.exposureTimeMaxNs?.let(::shutterLabel) ?: "—"
                DiagLine("快门范围", "$timeMin – $timeMax")
                DiagLine("EV 范围", "${caps.exposureCompensationMin}…${caps.exposureCompensationMax} × ${caps.exposureCompensationStepEv}")
            }
        }
    }
}

@Composable
private fun DiagSection(title: String) {
    Text(title, Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 1.dp),
        fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
}

@Composable
private fun DiagLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(68.dp), fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(value, Modifier.weight(1f), fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f),
            fontFamily = FontFamily.Monospace)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

@Composable
private fun compositionTimingLabel(state: CompositionUiState): String {
    val started = state.inferenceStartedAtMs
    var elapsedMs by remember(started) { mutableStateOf(0L) }
    LaunchedEffect(started) {
        if (started != null) while (true) {
            elapsedMs = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            delay(500L)
        }
    }
    val fast = state.fastAdviceMs?.let { "画面分析 ${formatInferenceTime(it)}" }
    val source = when (state.adviceOrigin) {
        AdviceOrigin.IMAGE_ANALYSIS -> "画面分析建议"
        AdviceOrigin.MODEL_SELECTION -> "AI 选择"
        null -> null
    }
    if (started != null) return listOfNotNull(source, fast, "AI 比较中 ${formatInferenceTime(elapsedMs)}").joinToString(" · ")
    if (state.isRequestRunning) return if (source == null) "正在获取构图画面"
        else listOfNotNull(source, fast, "准备 AI 比较").joinToString(" · ")
    val duration = state.modelSelectionMs?.let(::formatInferenceTime)
    val model = when (state.lastOutcome) {
        CompositionOutcome.RETURNED -> duration?.let { "AI 比较 $it" }
        CompositionOutcome.NO_RESULT -> "AI 未明确选择${duration?.let { " · $it" } ?: ""}"
        CompositionOutcome.UNSTABLE_SELECTION -> "AI 比较不稳定${duration?.let { " · $it" } ?: ""}"
        CompositionOutcome.TIMED_OUT -> "AI 比较超时${duration?.let { " · $it" } ?: ""}"
        CompositionOutcome.SCENE_CHANGED -> "画面已变化，旧比较已忽略"
        CompositionOutcome.FAILED -> "AI 暂不可用"
        null -> null
    }
    if (source != null) return listOfNotNull(source, fast, model).joinToString(" · ")
    return when (state.status) {
        AssistStatus.OFF -> "点“构图”获取建议"
        AssistStatus.PAUSED -> "构图辅助已暂停"
        else -> model ?: "点“构图”分析当前画面"
    }
}

private fun formatInferenceTime(milliseconds: Long): String = if (milliseconds >= 1_000L) {
    "%.1f 秒".format(milliseconds / 1_000f)
} else {
    "$milliseconds ms"
}

@Composable
private fun AdjustmentSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(valueLabel, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        if (range.start < range.endInclusive) Slider(value.coerceIn(range.start, range.endInclusive), onChange,
            valueRange = range, enabled = enabled, steps = steps)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun CapabilitiesDialog(capabilities: CameraCapabilities?, error: String?, close: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = close, title = { Text("设备能力") }, confirmButton = {
        TextButton(onClick = close) { Text("完成") }
    }, dismissButton = {
        if (capabilities != null) TextButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("street-snap 相机能力", capabilities.toJson()))
        }) { Text("复制能力信息") }
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
            if (capabilities != null) {
                Text("后摄 ID ${capabilities.cameraId}")
                Text("主摄等效：${capabilities.nativeEquivalentFocalLengthMm?.let { "%.1f mm".format(it) } ?: "设备未报告"}")
                Text("可用视角：${capabilities.focalLengthOptions.joinToString { it.label }}")
                Text("手动曝光：${if (capabilities.supportsManualExposure) "支持" else "不支持"}")
                Text("自动对焦：${if (capabilities.autoFocusMode != null) "支持" else "不支持"}")
                Text("点按对焦：${if (capabilities.supportsFocusMetering) "支持" else "不支持"}")
                Text("手动对焦：${if (capabilities.supportsManualFocus) "支持" else "不支持"}")
                Text("白平衡：${capabilities.whiteBalanceModes.joinToString { it.displayName }}")
                Text("对焦校准：${capabilities.focusDistanceCalibration}")
                Text("EV：${capabilities.exposureCompensationMin}…${capabilities.exposureCompensationMax} × ${capabilities.exposureCompensationStepEv}")
                capabilities.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } else Text(error ?: "相机连接后显示镜头能力。")
            HorizontalDivider()
            Text("冷启动、取景帧率及荣耀折叠屏表现需要真机验收；此页仅报告系统能力。",
                style = MaterialTheme.typography.bodySmall)
        }
    })
}

/** Formats a real CaptureResult exposure time as a camera-style fraction or seconds. */
internal fun shutterLabel(timeNs: Long): String = if (timeNs < 1_000_000_000L)
    "1/${(1_000_000_000.0 / timeNs).roundToInt()} s" else "%.1f s".format(timeNs / 1_000_000_000.0)

/** Normalized crop rectangle eased toward each incoming rule suggestion. */
private data class SmoothCrop(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Holds the four eased crop edges. The values are read inside the Canvas draw scope,
 * so animation ticks trigger a redraw only, not recomposition of the camera screen.
 */
private class SmoothCropState {
    // Order: left, top, right, bottom.
    val edges = Array(4) { Animatable(0f) }
    // 0f while no guide is shown; eased to 1f on appearance and back on clear so scene
    // changes / expiry cross-fade instead of blinking (T40).
    val alpha = Animatable(0f)
    var ready by mutableStateOf(false)
    var snappedOrigin: AdviceOrigin? = null

    val crop: SmoothCrop?
        get() = if (ready) {
            SmoothCrop(edges[0].value, edges[1].value, edges[2].value, edges[3].value)
        } else {
            null
        }
}

/**
 * Rule advice refreshes several times per second; ease the rectangle so nearby crops
 * glide instead of jumping. The first target, a rule/model handover (origin change),
 * and recovery after the overlay was invalidated snap immediately — we never slide
 * across a zoom/rotation/model change. Degenerate rectangles keep the previous frame.
 */
@Composable
private fun rememberSmoothCrop(overlay: CompositionOverlay?): SmoothCropState {
    val state = remember { SmoothCropState() }
    LaunchedEffect(overlay) {
        val current = overlay
        if (current == null) {
            // Zoom, rotation or model switch invalidated the guide; fade the last frame out
            // before dropping it (next guidance still snaps). A new overlay arriving inside
            // this window cancels the fade and raises the alpha straight back to 1.
            if (state.ready) {
                state.alpha.animateTo(0f, tween(durationMillis = 180, easing = FastOutSlowInEasing))
            }
            state.ready = false
            state.snappedOrigin = null
            return@LaunchedEffect
        }
        val suggestion = current.suggestion
        if (suggestion.right <= suggestion.left || suggestion.bottom <= suggestion.top) return@LaunchedEffect
        val targets = floatArrayOf(suggestion.left, suggestion.top, suggestion.right, suggestion.bottom)
        if (!state.ready || state.snappedOrigin != current.origin) {
            state.edges.forEachIndexed { index, animatable -> animatable.snapTo(targets[index]) }
            state.ready = true
            state.snappedOrigin = current.origin
        } else {
            // Each new overlay cancels the previous tween mid-flight; animate from the
            // current position so 250 ms cadence updates form one continuous glide.
            coroutineScope {
                state.edges.forEachIndexed { index, animatable ->
                    launch {
                        animatable.animateTo(
                            targets[index],
                            tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        )
                    }
                }
            }
        }
        // No-op while already visible; eases the first appearance (and recovers a fade-out).
        state.alpha.animateTo(1f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
    }
    return state
}
