package dev.streetsnap.app.composition

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import dev.streetsnap.app.inference.CandidateRanker
import dev.streetsnap.app.inference.CropRanking
import dev.streetsnap.app.inference.D1OmniRuntime
import dev.streetsnap.app.inference.InferenceStats
import dev.streetsnap.app.model.D1ModelCatalog
import dev.streetsnap.app.model.D1ModelEntry
import dev.streetsnap.app.model.ModelDownloadPlan
import dev.streetsnap.app.model.ModelStore
import dev.streetsnap.camera.CompositionFrameSource
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long a scene-mismatched overlay may linger while the next analysed frame republishes
 * advice (T40 anti-flicker). Two analysis frames at 250 ms fit inside, so continuous camera
 * motion never blanks the guide; a real scene cut with no fresh advice drops it within this.
 */
private const val OVERLAY_CLEAR_GRACE_MS = 600L

internal enum class ModelInstallStatus { CHECKING, ABSENT, PREPARING, AVAILABLE, DOWNLOADING, INSTALLED, REMOVING, FAILED }
internal enum class AssistStatus { OFF, UNINSTALLED, PAUSED, LOADING, READY, FAILED }
internal enum class CompositionOutcome { RETURNED, NO_RESULT, UNSTABLE_SELECTION, TIMED_OUT, SCENE_CHANGED, FAILED }
internal enum class AdviceOrigin { IMAGE_ANALYSIS, MODEL_SELECTION }
internal data class CompositionUiState(
    val modelId: String = D1ModelCatalog.DEFAULT_MODEL_ID,
    val install: ModelInstallStatus = ModelInstallStatus.CHECKING,
    val bytes: Long? = null,
    val downloadedBytes: Long = 0,
    val installError: String? = null,
    val status: AssistStatus = AssistStatus.OFF,
    val statusText: String? = null,
    val lastInferenceMs: Long? = null,
    val sampleIntervalMs: Long = 3_000L,
    val inferenceBudgetMs: Long = 30_000L,
    val inferenceStartedAtMs: Long? = null,
    val lastOutcome: CompositionOutcome? = null,
    val adviceOrigin: AdviceOrigin? = null,
    val fastAdviceMs: Long? = null,
    val modelSelectionMs: Long? = null,
    /** Wall-clock durations of the most recent ranking passes (success/timeout/failure), for dev diagnostics. */
    val inferenceHistoryMs: List<Long> = emptyList(),
    /** Per-pass timings of the latest FIRST ranking pass (vision/tokenize+embeddings/decision), real measurements. */
    val lastVisionMs: Long? = null,
    val lastEmbeddingsMs: Long? = null,
    val lastDecisionMs: Long? = null,
    /** Actual BPE token count of the fixed choice prompt on the latest ranking pass. */
    val lastPromptTokens: Int? = null,
    /** Wall-clock spent constructing the three native ORT sessions (pre-bound warm-up). */
    val runtimeReadyMs: Long? = null,
    /** Real execution backend label (EP/threads) reported by the active ranker. */
    val runtimeLabel: String? = null,
    val isRequestRunning: Boolean = false,
)
internal data class CompositionOverlay(
    val suggestion: CompositionSuggestion,
    val expiresAtMs: Long,
    val origin: AdviceOrigin = AdviceOrigin.IMAGE_ANALYSIS,
    /** Rare, light-hearted wide-angle nudge. Never participates in ranking or gates. */
    val easterEgg: String? = null,
)

/**
 * Rule analysis runs several times per second. When the same scene yields the same crop,
 * wording and origin (and no easter egg is present on either side), republishing would
 * allocate an overlay and recompose the camera for zero visual change; the caller keeps
 * the existing object and only extends its lifetime.
 */
internal fun CompositionOverlay.carriesSameGuidance(
    origin: AdviceOrigin,
    candidate: CompositionCandidate,
    newEasterEgg: String?,
): Boolean = newEasterEgg == null && easterEgg == null && this.origin == origin &&
    suggestion.modelText == candidate.reason &&
    suggestion.actionText == candidate.action &&
    suggestion.left == candidate.crop.left && suggestion.top == candidate.crop.top &&
    suggestion.right == candidate.crop.right && suggestion.bottom == candidate.crop.bottom
private data class CompletedRanking(val first: CropRanking?, val validation: CropRanking?)

/** Optional inference has its own lifetime and never takes the camera's controls or shutter locks. */
internal class CompositionAssistant(context: Context, private val scope: CoroutineScope) : AutoCloseable {
    private data class Configuration(
        val id: String, val enabled: Boolean, val foreground: Boolean, val realtime: Boolean,
        /** Look advice needs the cheap rule-analysis frames even while composition itself is off. */
        val lookAdvice: Boolean,
    )

    /** Fired on Main when the frame source's scene signature changes; look advice uses it to drop pending cards. */
    @Volatile var sceneChangeListener: (() -> Unit)? = null

    /** Focal length changes must not restart the camera session; the egg reads it live. */
    @Volatile private var focalLengthMm: Int = 28
    @Volatile private var adviceCount: Int = 0
    @Volatile private var lastEasterEggAtMs: Long = 0L
    private val store = ModelStore(context)
    private val modelPreferences = context.getSharedPreferences("composition-models", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(CompositionUiState())
    val state = _state.asStateFlow()
    private val _source = MutableStateFlow<CompositionFrameSource?>(null)
    val source = _source.asStateFlow()
    private val _overlay = MutableStateFlow<CompositionOverlay?>(null)
    val overlay = _overlay.asStateFlow()
    /**
     * Elapsed-realtime timestamp of a one-shot "framing achieved" confirmation (T34),
     * or 0 before the first one. The tracker is fed every analysed frame, including
     * frames that reuse the existing overlay via carriesSameGuidance.
     */
    private val _alignmentPulse = MutableStateFlow(0L)
    val alignmentPulse = _alignmentPulse.asStateFlow()
    /**
     * Persistent "framing is aligned right now" phase (T40). Unlike [alignmentPulse] (a one-
     * shot edge used for the buzz/check animation), this stays true while the achieved framing
     * holds so the UI can keep a visible "ready to shoot" state instead of flashing for 1.3 s.
     */
    private val _alignmentConfirmed = MutableStateFlow(false)
    val alignmentConfirmed = _alignmentConfirmed.asStateFlow()
    private val alignmentTracker = AlignmentTracker()
    /**
     * T40 anti-flicker: while the user moves with the guide, every analysed frame briefly
     * reads as a scene mismatch. The old code nulled the overlay on each mismatch and the
     * same frame republished it after the advisor pass, so a null gap reached the screen
     * ~4x/s and the guide visibly winked. The visual clear waits one grace window; the
     * republished advice of any later frame cancels it. The alignment state machine still
     * resets immediately, so no praise can be earned across a genuine scene break.
     */
    private var overlayClearGrace: Job? = null
    /** T76: tap-to-focus briefly suppresses scene-change clears (AF hunt is not a new scene). */
    private val focusGate = FocusDisturbanceGate(now = { SystemClock.elapsedRealtime() })
    /** Coarse colour/light statistics from the latest rule-analysis frame; consumed later by look/exposure advice. */
    private val _sceneReading = MutableStateFlow<SceneReading?>(null)
    val sceneReading = _sceneReading.asStateFlow()
    private val resultGate = CompositionResultGate(maximumAgeMs = 65_000L)
    private val sessionMutex = Mutex()
    private val inferenceMutex = Mutex()
    @Volatile private var configuration: Configuration? = null
    private val sessionGeneration = AtomicLong(0L)
    private var sessionJob: Job? = null
    private var installJob: Job? = null
    private var expiryJob: Job? = null
    private var requestFrameTimeout: Job? = null
    private var plan: ModelDownloadPlan? = null
    @Volatile private var manualRequestPending = false
    private var closed = false

    /** Let CameraX observe the null source and unbind ImageAnalysis before shutting its executor down. */
    private fun retireSource(source: CompositionFrameSource?) {
        if (source == null) return
        source.invalidateFrames()
        scope.launch(NonCancellable) {
            delay(300)
            source.close()
        }
    }

    fun configure(
        id: String, enabled: Boolean, foreground: Boolean, realtime: Boolean = false,
        focalLengthMm: Int = 28, lookAdvice: Boolean = false,
    ) {
        if (closed) return
        this.focalLengthMm = focalLengthMm.takeIf { it > 0 } ?: 28
        val next = Configuration(
            D1ModelCatalog.find(id)?.id ?: D1ModelCatalog.DEFAULT_MODEL_ID,
            enabled, foreground, realtime, lookAdvice,
        )
        if (configuration == next) return
        val changedModel = configuration?.id != next.id
        configuration = next
        if (changedModel) {
            installJob?.cancel()
            plan = null
            _state.value = CompositionUiState(modelId = next.id)
        }
        restartSession()
    }

    /** Live focal-length updates for the rare wide-angle egg; sessions are not restarted for this. */
    fun updateFocalLength(equivalentMm: Int) {
        if (equivalentMm > 0) focalLengthMm = equivalentMm
    }

    /**
     * The user explicitly asked for AF (tap / long-press lock). The motor hunt and AE
     * re-convergence briefly perturb the scene signature, so scene-change clears are held
     * back for one focus grace window (T76); a real pan afterwards clears advice as usual.
     */
    fun notifyFocusEngaged() {
        focusGate.engage()
    }

    fun invalidateView() {
        resultGate.invalidate(SystemClock.elapsedRealtime())
        _source.value?.invalidateFrames()
        overlayClearGrace?.cancel()
        overlayClearGrace = null
        alignmentTracker.reset()
        _alignmentConfirmed.value = false
        _overlay.value = null
        _sceneReading.value = null
        expiryJob?.cancel()
        _state.update { it.copy(adviceOrigin = null) }
    }

    /** One manual request owns one fast suggestion and at most one model comparison. */
    fun requestAdvice() {
        val config = configuration ?: return
        if (closed || !config.enabled || !config.foreground || _state.value.isRequestRunning ||
            _state.value.install == ModelInstallStatus.REMOVING) return
        manualRequestPending = true
        _state.update { it.copy(isRequestRunning = true, statusText = "正在获取构图画面",
            lastOutcome = null, lastInferenceMs = null, modelSelectionMs = null) }
        _source.value?.requestNextFrame()
        val generation = sessionGeneration.get()
        requestFrameTimeout?.cancel()
        requestFrameTimeout = scope.launch(Dispatchers.Main.immediate) {
            delay(5_000L)
            if (manualRequestPending && sessionGeneration.get() == generation) {
                manualRequestPending = false
                _state.update { it.copy(isRequestRunning = false, statusText = "尚未收到取景画面，请稍后重试") }
                Log.w("StreetSnapComposition", "request_end outcome=NO_CAMERA_FRAME")
            }
        }
    }

    /** UI-thread only: every visible rectangle has an origin and a bounded lifetime. */
    private fun showAdvice(candidate: CompositionCandidate, origin: AdviceOrigin, subjectLooksDistant: Boolean = false) {
        val crop = candidate.crop
        val now = SystemClock.elapsedRealtime()
        val draw = adviceCount++
        // Feed the alignment state machine before the same-guidance early return:
        // a static near-final scene keeps republishing the same overlay every frame,
        // and those frames are exactly the confirmation streak we count.
        // T40: alignment reads "the asked-for move is satisfied" (centred, near-full frame),
        // not "crop == full frame within 3 %", which the 0.92 band could never reach.
        val guidance = AlignmentFeedback.guidanceFor(crop, candidate.style)
        val event = alignmentTracker.observe(guidance)
        _alignmentConfirmed.value = alignmentTracker.isConfirmedAligned
        if (event == AlignmentEvent.ALIGNED) {
            _alignmentPulse.value = now
        }
        // Fresh advice for the current view supersedes any pending scene-mismatch clear,
        // so moving with the guide glides the overlay instead of blinking it each frame.
        overlayClearGrace?.cancel()
        overlayClearGrace = null
        val egg = easterEgg(subjectLooksDistant, origin, draw, now)
        if (egg != null) lastEasterEggAtMs = now
        // Rule analysis now runs several times per second. On a static scene the same
        // crop comes back every time: republishing it allocates an overlay, emits a
        // StateFlow value and recomposes the camera ~4x/s for zero visual change.
        // Keep the existing overlay object (and its origin tint, which must change on
        // a rule/model handover), only extending its lifetime when it runs low.
        val current = _overlay.value
        if (current != null && current.carriesSameGuidance(origin, candidate, egg)) {
            if (current.expiresAtMs - now < 10_000L) scheduleOverlayExpiry(current, now + 30_000L)
            return
        }
        val answer = CompositionOverlay(
            CompositionSuggestion(crop.left, crop.top, crop.right, crop.bottom,
                candidate.reason, candidate.action),
            now + 30_000L,
            origin,
            egg,
        )
        _overlay.value = answer
        _state.update { it.copy(adviceOrigin = origin) }
        scheduleOverlayExpiry(answer, answer.expiresAtMs)
    }

    /** Publish null once this exact overlay reaches its (possibly extended) lifetime. */
    private fun scheduleOverlayExpiry(overlay: CompositionOverlay, expiresAtMs: Long) {
        expiryJob?.cancel()
        expiryJob = scope.launch(Dispatchers.Main.immediate) {
            delay((expiresAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            if (_overlay.value === overlay) {
                _overlay.value = null
                _state.update { it.copy(adviceOrigin = null) }
            }
        }
    }

    /** Rare, gentle wide-angle nudge. Pure UI flavour: it never changes a crop, score or gate. */
    private fun easterEgg(subjectLooksDistant: Boolean, origin: AdviceOrigin, draw: Int, nowMs: Long): String? =
        CompositionEasterEgg.decide(subjectLooksDistant, origin, focalLengthMm, draw, nowMs, lastEasterEggAtMs)

    /** Metadata only. A second, explicit install action is required after the size is visible. */
    fun prepareInstall() {
        val config = configuration ?: return
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            _state.update { it.copy(install = ModelInstallStatus.PREPARING, installError = null) }
            try {
                val model = requireNotNull(D1ModelCatalog.find(config.id))
                // A previously downloaded model lives in app-private storage and survives
                // same-signature updates with no network. Recognise it before any metadata
                // call, so a blocked mirror can never redisplay an installed model as
                // missing / failed after an app update.
                val local = withContext(Dispatchers.IO) { store.installedD1(model) }
                if (configuration?.id != config.id) return@launch
                if (local != null) {
                    plan = null
                    _state.update { it.copy(install = ModelInstallStatus.INSTALLED,
                        bytes = local.plan.totalBytes, installError = null) }
                    return@launch
                }
                val prepared = store.prepareD1Plan(model)
                if (configuration?.id != config.id) return@launch
                plan = prepared
                _state.update { it.copy(install = ModelInstallStatus.AVAILABLE, bytes = prepared.totalBytes) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (configuration?.id == config.id) _state.update { it.copy(install = ModelInstallStatus.FAILED,
                    installError = error.message ?: "无法读取模型安装信息") }
            }
        }
    }

    fun install() {
        val prepared = plan ?: return
        // AVAILABLE is published only after metadata preparation has produced this plan.
        // Do not reject the first tap merely because the preparation coroutine has not
        // completed its final dispatch yet.
        if (prepared.model.id != configuration?.id || _state.value.install != ModelInstallStatus.AVAILABLE) return
        installJob?.cancel()
        installJob = scope.launch {
            _state.update { it.copy(install = ModelInstallStatus.DOWNLOADING, downloadedBytes = 0, installError = null) }
            try {
                store.downloadD1(prepared) { progress ->
                    if (configuration?.id == prepared.model.id) _state.update { it.copy(
                        downloadedBytes = progress.downloadedBytes, bytes = progress.totalBytes) }
                }
                if (configuration?.id == prepared.model.id) {
                    _state.update { it.copy(install = ModelInstallStatus.INSTALLED, bytes = prepared.totalBytes) }
                    restartSession()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (configuration?.id == prepared.model.id) _state.update { it.copy(install = ModelInstallStatus.FAILED,
                    installError = error.message ?: "安装未完成") }
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
        _state.update { it.copy(install = if (plan == null) ModelInstallStatus.ABSENT else ModelInstallStatus.AVAILABLE,
            downloadedBytes = 0, installError = null) }
    }

    fun removeModel() {
        val config = configuration ?: return
        // Removing the imported copy is an explicit opt-out from automatic asset
        // import, including after the app restarts. Explicit downloads remain usable.
        modelPreferences.edit().putBoolean("bundled-disabled-${config.id}", true).apply()
        installJob?.cancel()
        sessionJob?.cancel()
        sessionGeneration.incrementAndGet()
        manualRequestPending = false
        requestFrameTimeout?.cancel()
        val oldSource = _source.value
        _source.value = null
        retireSource(oldSource)
        invalidateView()
        _state.update { it.copy(install = ModelInstallStatus.REMOVING, status = AssistStatus.UNINSTALLED,
            statusText = null, isRequestRunning = false, inferenceStartedAtMs = null,
            lastOutcome = null, lastInferenceMs = null, modelSelectionMs = null, fastAdviceMs = null,
            lastVisionMs = null, lastEmbeddingsMs = null, lastDecisionMs = null,
            lastPromptTokens = null, runtimeReadyMs = null, runtimeLabel = null) }
        installJob = scope.launch {
            try {
                // Runtime cleanup holds this lock until native sessions and any active inference end.
                sessionMutex.withLock { store.removeD1(requireNotNull(D1ModelCatalog.find(config.id))) }
                if (configuration?.id == config.id) {
                    plan = null
                    _state.update { it.copy(install = ModelInstallStatus.ABSENT, bytes = null, downloadedBytes = 0) }
                    restartSession(imageOnly = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (configuration?.id == config.id) {
                    _state.update { it.copy(install = ModelInstallStatus.FAILED,
                        installError = error.message ?: "删除未完成") }
                    // A model-management failure must not disable image-only advice.
                    restartSession(imageOnly = true)
                }
            }
        }
    }

    fun retry() { restartSession() }

    private fun restartSession(imageOnly: Boolean = false) {
        val config = configuration ?: return
        if (_state.value.install == ModelInstallStatus.REMOVING) return
        val generation = sessionGeneration.incrementAndGet()
        fun isCurrentSession() = configuration == config && sessionGeneration.get() == generation
        sessionJob?.cancel()
        manualRequestPending = false
        requestFrameTimeout?.cancel()
        val oldSource = _source.value
        _source.value = null
        retireSource(oldSource)
        invalidateView()
        _state.update { it.copy(status = when {
            !config.enabled -> AssistStatus.OFF
            !config.foreground -> AssistStatus.PAUSED
            else -> AssistStatus.LOADING
        }, statusText = null, lastInferenceMs = null, fastAdviceMs = null, modelSelectionMs = null,
            isRequestRunning = false, sampleIntervalMs = 3_000L, inferenceBudgetMs = 30_000L,
            inferenceStartedAtMs = null, lastOutcome = null,
            // Per-session values: the runtime is rebuilt (and no ranking has run) on restart.
            lastVisionMs = null, lastEmbeddingsMs = null, lastDecisionMs = null,
            lastPromptTokens = null, runtimeReadyMs = null, runtimeLabel = null) }
        sessionJob = scope.launch(Dispatchers.Main.immediate) {
            sessionMutex.withLock {
                val sessionScope = CoroutineScope(currentCoroutineContext())
                val runtimeReady = CompletableDeferred<CandidateRanker?>()
                val cadence = CompositionCadence()
                val selectionGate = CandidateSelectionGate(listOf(1, 2, 0))
                var runtime: CandidateRanker? = null
                var frames: CompositionFrameSource? = null
                var modelJob: Job? = null
                var runtimeJob: Job? = null
                var rankingDisabled = false
                var nextModelAtMs = 0L
                var lastRankedScene: (() -> Boolean)? = null
                var visibleScene: (() -> Boolean)? = null
                var selectedScene: (() -> Boolean)? = null
                var selectedCandidate: CompositionCandidate? = null

                fun clearVisibleAdvice() {
                    overlayClearGrace?.cancel()
                    overlayClearGrace = null
                    visibleScene = null
                    selectedScene = null
                    selectedCandidate = null
                    alignmentTracker.reset()
                    _alignmentConfirmed.value = false
                    _overlay.value = null
                    _sceneReading.value = null
                    expiryJob?.cancel()
                    _state.update { it.copy(adviceOrigin = null, lastOutcome = null,
                        lastInferenceMs = null, modelSelectionMs = null, fastAdviceMs = null) }
                }

                // Scene mismatch while the camera is moving: reset praise immediately, but let
                // the overlay survive [OVERLAY_CLEAR_GRACE_MS] — the same frame's advisor pass
                // usually republishes via showAdvice, which cancels the grace. Only a genuine
                // cut with no fresh guidance lets the timer clear the rectangle (T40).
                fun requestGracefulClear() {
                    alignmentTracker.reset()
                    _alignmentConfirmed.value = false
                    if (overlayClearGrace?.isActive != true) {
                        overlayClearGrace = sessionScope.launch(Dispatchers.Main.immediate) {
                            delay(OVERLAY_CLEAR_GRACE_MS)
                            if (isCurrentSession()) clearVisibleAdvice()
                        }
                    }
                }

                // Runs as a session child on Main. Only independent sheets survive
                // the source callback; the borrowed source bitmap is never read later.
                suspend fun compareCandidates(sheet: Bitmap, validationSheet: Bitmap, candidates: List<CompositionCandidate>,
                    ticket: CompositionResultGate.Ticket, frameCurrent: () -> Boolean, sceneCurrent: () -> Boolean) {
                    var started: Long? = null
                    var firstRanking: CropRanking? = null
                    var validationRanking: CropRanking? = null
                    try {
                        val engine = runtimeReady.await()
                        if (!isCurrentSession()) return
                        if (engine == null || rankingDisabled) {
                            _state.update { it.copy(statusText = "图像构图建议已就绪，模型择优暂不可用") }
                            return
                        }
                        delay((nextModelAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                        if (!isCurrentSession()) return
                        if (!frameCurrent() || !sceneCurrent() ||
                            !resultGate.isFreshForInference(ticket, SystemClock.elapsedRealtime())) {
                            _state.update { it.copy(lastOutcome = CompositionOutcome.SCENE_CHANGED,
                                lastInferenceMs = null, modelSelectionMs = null,
                                statusText = "画面已变化，可重新获取构图建议") }
                            return
                        }
                        val runStarted = SystemClock.elapsedRealtime()
                        started = runStarted
                        lastRankedScene = sceneCurrent
                        // Both passes share one deadline; a consistency check cannot
                        // double the request budget or retry indefinitely.
                        val budgetMs = 30_000L
                        _state.update { it.copy(inferenceStartedAtMs = runStarted, inferenceBudgetMs = budgetMs,
                            statusText = "模型正在比较构图，当前建议可先用") }
                        val completed = withTimeoutOrNull(budgetMs) {
                            inferenceMutex.withLock {
                                firstRanking = engine.rankCandidates(sheet)
                                // An abstention cannot pass, so avoid a second image pass.
                                validationRanking = if (firstRanking?.selectedIndex != null) engine.rankCandidates(validationSheet) else null
                                CompletedRanking(firstRanking, validationRanking)
                            }
                        }
                        val now = SystemClock.elapsedRealtime()
                        val elapsed = now - runStarted
                        if (!isCurrentSession()) return
                        val decision = selectionGate.evaluate(completed?.first?.selectedIndex,
                            completed?.validation?.selectedIndex, candidates.all { it.supportsModelComparison })
                        val selected = decision.canonicalIndex?.let(candidates::getOrNull)
                        val fresh = resultGate.accepts(ticket, now, frameCurrent()) && sceneCurrent()
                        val outcome = when {
                            completed == null -> CompositionOutcome.TIMED_OUT
                            !fresh -> CompositionOutcome.SCENE_CHANGED
                            decision.verdict == CandidateSelectionGate.Verdict.INCONSISTENT -> CompositionOutcome.UNSTABLE_SELECTION
                            selected == null -> CompositionOutcome.NO_RESULT
                            else -> CompositionOutcome.RETURNED
                        }
                        val next = if (completed == null) cadence.recordTimeout(elapsed)
                            else cadence.recordCompleted(elapsed, selected != null)
                        nextModelAtMs = now + next.cooldownMs
                        // Process-lifetime counters for the dev panel. A stale-but-finished job
                        // (SCENE_CHANGED) really consumed one forward, so it counts as abstained.
                        InferenceStats.recordRanking(
                            outcome = when (outcome) {
                                CompositionOutcome.RETURNED -> InferenceStats.RankOutcome.ADOPTED
                                CompositionOutcome.TIMED_OUT -> InferenceStats.RankOutcome.TIMED_OUT
                                CompositionOutcome.FAILED -> InferenceStats.RankOutcome.FAILED
                                CompositionOutcome.NO_RESULT,
                                CompositionOutcome.UNSTABLE_SELECTION,
                                CompositionOutcome.SCENE_CHANGED -> InferenceStats.RankOutcome.ABSTAINED
                            },
                            elapsedMs = elapsed,
                            visionMs = firstRanking?.visionMs,
                            embeddingsMs = firstRanking?.embeddingsMs,
                            decisionMs = firstRanking?.decisionMs,
                            promptTokens = firstRanking?.promptTokens,
                            nowMs = now,
                        )
                        _state.update { it.copy(lastInferenceMs = elapsed.takeIf { fresh }, modelSelectionMs = elapsed.takeIf { fresh },
                            lastOutcome = outcome, inferenceStartedAtMs = null,
                            inferenceHistoryMs = (it.inferenceHistoryMs + elapsed).takeLast(10),
                            // A timeout carries no pass breakdown; keep the previous real one visible.
                            lastVisionMs = firstRanking?.visionMs?.takeIf { ms -> ms >= 0L } ?: it.lastVisionMs,
                            lastEmbeddingsMs = firstRanking?.embeddingsMs?.takeIf { ms -> ms >= 0L } ?: it.lastEmbeddingsMs,
                            lastDecisionMs = firstRanking?.decisionMs?.takeIf { ms -> ms >= 0L } ?: it.lastDecisionMs,
                            lastPromptTokens = firstRanking?.promptTokens?.takeIf { tokens -> tokens >= 0 } ?: it.lastPromptTokens,
                            sampleIntervalMs = next.cooldownMs, inferenceBudgetMs = budgetMs,
                            statusText = when (outcome) {
                                CompositionOutcome.RETURNED -> "AI 已从候选中选择构图"
                                CompositionOutcome.NO_RESULT -> "模型未作出明确选择，保留图像构图建议"
                                CompositionOutcome.UNSTABLE_SELECTION -> "AI 比较未通过一致性检查，保留画面分析建议"
                                CompositionOutcome.TIMED_OUT -> "模型比较超时，保留图像构图建议"
                                CompositionOutcome.SCENE_CHANGED -> "画面已变化，当前显示最新构图建议"
                                CompositionOutcome.FAILED -> "模型比较失败，保留图像构图建议"
                            }) }
                        Log.i("StreetSnapComposition", "model_outcome=$outcome total_ms=$elapsed " +
                            "first_position=${firstRanking?.selectedIndex} first_canonical=${firstRanking?.selectedIndex} " +
                            "validation_position=${validationRanking?.selectedIndex} " +
                            "validation_canonical=${selectionGate.canonicalForValidation(validationRanking?.selectedIndex)} " +
                            "first_support=${firstRanking?.support} validation_support=${validationRanking?.support} " +
                            "first_margin=${firstRanking?.margin} validation_margin=${validationRanking?.margin} " +
                            "cooldown_ms=${next.cooldownMs} budget_ms=$budgetMs")
                        if (outcome == CompositionOutcome.RETURNED && selected != null) {
                            selectedScene = sceneCurrent
                            selectedCandidate = selected
                            visibleScene = sceneCurrent
                            showAdvice(selected, AdviceOrigin.MODEL_SELECTION)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (isCurrentSession()) {
                            rankingDisabled = true
                            val elapsed = started?.let { SystemClock.elapsedRealtime() - it }
                            InferenceStats.recordRanking(
                                InferenceStats.RankOutcome.FAILED, elapsed,
                                firstRanking?.visionMs, firstRanking?.embeddingsMs,
                                firstRanking?.decisionMs, firstRanking?.promptTokens,
                                SystemClock.elapsedRealtime())
                            _state.update { it.copy(lastOutcome = CompositionOutcome.FAILED,
                                lastInferenceMs = elapsed, modelSelectionMs = elapsed,
                                inferenceHistoryMs = if (elapsed != null)
                                    (it.inferenceHistoryMs + elapsed).takeLast(10) else it.inferenceHistoryMs,
                                statusText = "模型比较暂不可用，保留图像构图建议") }
                            Log.w("StreetSnapComposition", "model_outcome=FAILED elapsed_ms=$elapsed", error)
                        }
                    } finally {
                        sheet.recycle()
                        validationSheet.recycle()
                        if (isCurrentSession()) {
                            _state.update { it.copy(inferenceStartedAtMs = null, isRequestRunning = false) }
                            Log.i("StreetSnapComposition", "request_end model_outcome=${_state.value.lastOutcome}")
                        }
                    }
                }

                // PERF-1: build the native ONNX sessions off the camera critical path. Asset
                // import, the store lookup and the three ALL_OPT session constructions run on
                // IO, launched before ImageAnalysis is bound, so graph optimization overlaps
                // CameraX binding and the first analysed frame instead of serialising behind
                // them. The session still owns the published [runtime] and closes it exactly
                // once in finally; a runtime built for a stale generation closes itself.
                suspend fun ensureRuntimePublished(model: D1ModelEntry) {
                    try {
                        val installed = withContext(Dispatchers.IO) {
                            if (!modelPreferences.getBoolean("bundled-disabled-${model.id}", false)) {
                                store.installBundledD1(model) ?: store.installedD1(model)
                            } else {
                                store.installedD1(model)
                            }
                        }
                        if (!isCurrentSession()) return
                        if (_state.value.install !in listOf(ModelInstallStatus.PREPARING, ModelInstallStatus.DOWNLOADING,
                                ModelInstallStatus.REMOVING, ModelInstallStatus.AVAILABLE)) {
                            _state.update { it.copy(install = if (installed == null) ModelInstallStatus.ABSENT else ModelInstallStatus.INSTALLED,
                                bytes = installed?.plan?.totalBytes ?: it.bytes) }
                        }
                        if (installed == null) {
                            rankingDisabled = true
                            runtimeReady.complete(null)
                            _state.update { it.copy(statusText = "图像构图建议可用，可安装模型进行择优") }
                            return
                        }
                        val builtAt = SystemClock.elapsedRealtime()
                        val engine = createSessionResource(isCurrent = ::isCurrentSession) {
                            D1OmniRuntime(installed)
                        }?.also { runtime = it } ?: return
                        val readyMs = SystemClock.elapsedRealtime() - builtAt
                        Log.i("StreetSnapComposition",
                            "model_runtime_ready_ms=$readyMs model=${model.id}")
                        runtimeReady.complete(engine)
                        _state.update { it.copy(runtimeReadyMs = readyMs, runtimeLabel = engine.runtimeLabel) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (isCurrentSession()) {
                            rankingDisabled = true
                            runtimeReady.complete(null)
                            _state.update { it.copy(statusText = "本地模型暂不可用，图像构图建议仍可使用") }
                            Log.w("StreetSnapComposition", "model_load_failed", error)
                        }
                    }
                }

                try {
                    val model = requireNotNull(D1ModelCatalog.find(config.id))
                    val analysisWanted = config.foreground && (config.enabled || config.lookAdvice)
                    // Start warming native sessions before the frame source is bound. An
                    // image-only restart (after model removal) has no model to load.
                    if (config.enabled && config.foreground) {
                        if (imageOnly) {
                            rankingDisabled = true
                            runtimeReady.complete(null)
                        } else {
                            runtimeJob = sessionScope.launch(Dispatchers.Main.immediate) { ensureRuntimePublished(model) }
                        }
                    }
                    if (analysisWanted) {
                        // Rule advice runs in single-digit milliseconds, so the cadence is
                        // set by the frame clocks, not by compute: ~4 advice refreshes/s.
                        // Model ranking stays on its own 3-15 s cadence gate below and is
                        // unaffected by this interval. Look advice keeps the same cheap
                        // frames flowing while composition itself is switched off.
                        frames = CompositionFrameSource(minimumIntervalMs = 250L, maximumEdge = 384,
                            sceneIntervalMs = 250L,
                            shouldCaptureFrame = {
                                isCurrentSession() && (manualRequestPending || config.realtime || config.lookAdvice)
                            },
                            onSceneChanged = { hard ->
                                scope.launch(Dispatchers.Main.immediate) {
                                    // T76: the AF/AE hunt right after a focus tap is not a real
                                    // scene change — keep the rectangle, arrow and look card.
                                    if (!isCurrentSession() || focusGate.isDisturbing()) return@launch
                                    // Hard invalidation (camera rebind/rotation/model switch) always
                                    // invalidates shown advice. A mere content change only clears a
                                    // LIVE guide: realtime repaints on the next frame within the grace
                                    // window, while a one-shot request must survive ordinary motion
                                    // (handheld shake, AE, a moving subject) — otherwise the requested
                                    // rectangle vanished a few seconds after tapping 构图 (T118).
                                    if (hard || config.realtime) requestGracefulClear()
                                    sceneChangeListener?.invoke()
                                }
                            }, onError = { error ->
                                scope.launch(Dispatchers.Main.immediate) {
                                    if (isCurrentSession()) {
                                        manualRequestPending = false
                                        _state.update { it.copy(statusText = error.message ?: "无法分析当前画面",
                                            isRequestRunning = modelJob?.isActive == true) }
                                    }
                                }
                            }, onFrame = frameCallback@{ frame ->
                                val ticket = resultGate.ticket(frame.receivedAtElapsedRealtimeMs) ?: return@frameCallback
                                // First member = manual composition request; second = look-only
                                // frame (readings only: no composition overlay, no model ranking).
                                val frameMode = withContext(Dispatchers.Main.immediate) {
                                    if (!isCurrentSession() || !frame.isCurrent ||
                                        !resultGate.isFreshForInference(ticket, SystemClock.elapsedRealtime())) return@withContext null
                                    // Age-out applies to the live guide only: a realtime stream
                                    // repaints within the grace window, whereas a one-shot request
                                    // must outlast look-only frames arriving after it (T118).
                                    if (config.realtime && visibleScene?.invoke() == false &&
                                        !focusGate.isDisturbing())
                                        requestGracefulClear()
                                    val requested = manualRequestPending
                                    if (!requested && !config.realtime && !config.lookAdvice) return@withContext null
                                    if (requested) {
                                        manualRequestPending = false
                                        requestFrameTimeout?.cancel()
                                    }
                                    requested to (!requested && !config.realtime)
                                } ?: return@frameCallback
                                val manual = frameMode.first
                                val lookOnly = frameMode.second
                                try {
                                    val started = SystemClock.elapsedRealtime()
                                    val bitmap = frame.bitmap
                                    val pixels = IntArray(bitmap.width * bitmap.height)
                                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                    // Same frame, same pixels: a single <=96px statistics pass.
                                    val reading = SceneReadings.read(bitmap.width, bitmap.height, pixels)
                                    if (lookOnly) {
                                        withContext(Dispatchers.Main.immediate) {
                                            if (isCurrentSession()) _sceneReading.value = reading
                                        }
                                        return@frameCallback
                                    }
                                    val advice = FastCompositionAdvisor.suggest(bitmap.width, bitmap.height, pixels)
                                    val candidates = advice.candidates
                                    val first = candidates.firstOrNull() ?: error("当前画面无法生成构图建议")
                                    val fastMs = SystemClock.elapsedRealtime() - started
                                    val frameCurrent = { frame.isCurrent }
                                    val sceneCurrent = { frame.isSceneCurrent }
                                    val shouldRank = withContext(Dispatchers.Main.immediate) {
                                        if (!isCurrentSession()) return@withContext false
                                        if (!resultGate.accepts(ticket, SystemClock.elapsedRealtime(), frame.isCurrent) || !sceneCurrent()) {
                                            if (manual) _state.update { it.copy(isRequestRunning = false) }
                                            return@withContext false
                                        }
                                        val retainedSelection = selectedCandidate?.takeIf {
                                            !manual && selectedScene?.invoke() == true
                                        }
                                        if (retainedSelection != null) {
                                            visibleScene = selectedScene
                                            showAdvice(retainedSelection, AdviceOrigin.MODEL_SELECTION)
                                        } else {
                                            selectedScene = null
                                            selectedCandidate = null
                                            visibleScene = sceneCurrent
                                            showAdvice(first, AdviceOrigin.IMAGE_ANALYSIS, advice.subjectLooksDistant)
                                        }
                                        _sceneReading.value = reading
                                        _state.update { it.copy(status = AssistStatus.READY, fastAdviceMs = fastMs) }
                                        Log.i("StreetSnapComposition", "fast_ms=$fastMs manual=$manual candidates=" +
                                            candidates.joinToString(";") { "${it.crop.left},${it.crop.top},${it.crop.right},${it.crop.bottom}" })
                                        val eligible = !rankingDisabled && candidates.size == 3 && candidates.all { it.supportsModelComparison } &&
                                            modelJob?.isActive != true &&
                                            (manual || (SystemClock.elapsedRealtime() >= nextModelAtMs && lastRankedScene?.invoke() != true))
                                        if (eligible) _state.update { it.copy(isRequestRunning = true) }
                                        else if (manual) {
                                            _state.update { it.copy(isRequestRunning = false) }
                                            Log.i("StreetSnapComposition", "request_end model_outcome=FAST_ONLY")
                                        }
                                        eligible
                                    }
                                    if (!shouldRank) return@frameCallback
                                    // Create both permutations from the same live frame before
                                    // handing off; neither pass can borrow the source bitmap.
                                    val sheet = CandidateContactSheet.create(bitmap, candidates)
                                    var validationSheet: Bitmap? = null
                                    var transferred = false
                                    try {
                                        val reorderedSheet = CandidateContactSheet.create(bitmap,
                                            selectionGate.validationOrder.map(candidates::get))
                                        validationSheet = reorderedSheet
                                        withContext(Dispatchers.Main.immediate) {
                                            if (!isCurrentSession() || !frame.isCurrent) {
                                                if (isCurrentSession()) _state.update { it.copy(isRequestRunning = false) }
                                                return@withContext
                                            }
                                            modelJob = sessionScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                                                compareCandidates(sheet, reorderedSheet, candidates, ticket, frameCurrent, sceneCurrent)
                                            }
                                            transferred = true
                                        }
                                    } finally {
                                        if (!transferred) {
                                            sheet.recycle()
                                            validationSheet?.recycle()
                                        }
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) {
                                    withContext(Dispatchers.Main.immediate) {
                                        if (isCurrentSession()) {
                                            _state.update { it.copy(statusText = error.message ?: "暂时无法更新构图建议",
                                                isRequestRunning = modelJob?.isActive == true) }
                                            Log.w("StreetSnapComposition", "fast_advice_failed", error)
                                        }
                                    }
                                }
                            })
                        // Publish input before copying assets or creating native sessions.
                        _source.value = frames
                        if (config.enabled) {
                            _state.update { it.copy(status = AssistStatus.READY, statusText = "点击构图获取画面建议") }
                            // Show the first photographic suggestion automatically, so enabling the
                            // feature always produces a visible frame without hunting for the button.
                            requestAdvice()
                        }
                    }
                    if (config.foreground && !config.enabled) {
                        // Look-only session: no model install or composition overlay; park here
                        // so the frame source stays bound and scene readings keep flowing.
                        rankingDisabled = true
                        runtimeReady.complete(null)
                        awaitCancellation()
                    }
                    // enabled + foreground: runtimeJob has already warmed and published the
                    // runtime off the critical path. A non-foreground session binds neither
                    // frames nor a model and ends here instead of parking.
                    if (!config.foreground) return@withLock
                    awaitCancellation()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) {
                    if (isCurrentSession()) _state.update { it.copy(status = AssistStatus.FAILED,
                        statusText = error.message?.take(180) ?: "构图辅助无法启动",
                        inferenceStartedAtMs = null, isRequestRunning = false) }
                } finally {
                    if (_source.value === frames) _source.value = null
                    retireSource(frames)
                    runtimeReady.complete(null)
                    withContext(NonCancellable) {
                        modelJob?.cancelAndJoin()
                        // Join before closing: either the engine was published to [runtime]
                        // (closed below) or a stale/cancelled build closed it itself.
                        runtimeJob?.cancelAndJoin()
                        withContext(Dispatchers.IO) {
                            inferenceMutex.withLock { runCatching { runtime?.close() } }
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        closed = true
        sessionGeneration.incrementAndGet()
        installJob?.cancel()
        sessionJob?.cancel()
        requestFrameTimeout?.cancel()
        val oldSource = _source.value
        _source.value = null
        retireSource(oldSource)
        invalidateView()
    }
}
