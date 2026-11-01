package dev.streetsnap.app.pose

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.streetsnap.app.model.PoseModelCatalog
import dev.streetsnap.app.model.ModelStore
import dev.streetsnap.camera.PoseFrameSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class PoseModelStatus { CHECKING, ABSENT, DOWNLOADING, INSTALLED, FAILED }

internal data class PoseUiState(
    val enabled: Boolean = false,
    /** Live pose detection running (model installed and feature in foreground). */
    val live: Boolean = false,
    /** Feature on, model absent: the figure browser works without any detection. */
    val demo: Boolean = false,
    val modelStatus: PoseModelStatus = PoseModelStatus.CHECKING,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = PoseModelCatalog.lite.bytes,
    val installError: String? = null,
    val currentPoseId: String = PoseCatalog.poses.first().id,
    val guide: PoseGuide? = null,
)

/**
 * T72 session orchestration: optional MediaPipe source + pluggable model lifecycle +
 * the pure-Kotlin coach, mirroring CompositionAssistant's degradation contract:
 * no model → demo mode (target figures browsable, no detection); model error →
 * demo mode; feature off/background → camera stream released.
 */
internal class PoseAssistant(
    context: Context,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val store = ModelStore(appContext)
    private val model = PoseModelCatalog.lite
    // 完整权重构建会在 assets 内预置 .task：探测时自动导入，与 CompositionAssistant 的
    // d1 bundled 通道同构；用户显式删除后该开关置位，不再自动导回。
    private val modelPreferences = appContext.getSharedPreferences("pose-models", Context.MODE_PRIVATE)
    private val coach = PoseCoach()
    // T108: landmark jitter filter; guarded by coachLock together with the coach state.
    private val smoother = PoseSmoother()
    private var lastPersonAtMs = 0L
    private val coachLock = Any()

    private val _source = MutableStateFlow<PoseFrameSource?>(null)
    val source = _source.asStateFlow()

    private val _state = MutableStateFlow(PoseUiState())
    val state = _state.asStateFlow()

    private data class Configuration(val enabled: Boolean, val foreground: Boolean)
    private var configuration: Configuration? = null
    private var detector: MediaPipePoseSource? = null
    private var restartJob: Job? = null
    private var installJob: Job? = null
    // Serialises reconfigure/start/stop so the init check and the first settings emission
    // cannot each build their own graph concurrently.
    private val reconfigureLock = Mutex()
    private var closed = false

    init {
        scope.launch {
            val status = checkInstalled()
            _state.update { it.copy(
                modelStatus = if (status != null) PoseModelStatus.INSTALLED else PoseModelStatus.ABSENT) }
            reconfigure()
        }
    }

    fun configure(enabled: Boolean, foreground: Boolean) {
        if (closed) return
        val next = Configuration(enabled, foreground)
        if (next == configuration) return
        configuration = next
        _state.update { it.copy(enabled = enabled) }
        reconfigure()
    }

    private fun reconfigure() {
        restartJob?.cancel()
        restartJob = scope.launch {
            reconfigureLock.withLock {
                if (closed) return@withLock
                val config = configuration
                val active = config?.enabled == true && config.foreground
                val installedFile = checkInstalled()
                if (closed) return@withLock
                val liveReady = if (active && installedFile != null) {
                    startLive(installedFile)
                } else {
                    stopLive()
                    false
                }
                _state.update {
                    it.copy(
                        live = active && liveReady,
                        demo = active && !liveReady,
                        currentPoseId = synchronized(coachLock) { coach.current().id },
                        guide = if (active && liveReady) it.guide else null,
                    )
                }
            }
        }
    }

    private suspend fun checkInstalled() = withContext(Dispatchers.IO) {
        if (!modelPreferences.getBoolean("bundled-disabled-${model.id}", false)) {
            store.installBundledPose(model) ?: store.installedPose(model)
        } else {
            store.installedPose(model)
        }
    }

    /** Returns true when the live source is bound; false falls back to demo mode. */
    private suspend fun startLive(modelFile: java.io.File): Boolean {
        if (_source.value != null && detector != null) return true
        if (closed) return false
        val newDetector = runCatching {
            MediaPipePoseSource(
                appContext, modelFile,
                onResult = { sample -> acceptSample(sample) },
                onError = { error ->
                    Log.w("StreetSnapPose", "pose inference error: ${error.message}")
                },
            )
        }.getOrElse { error ->
            // Unsupported ABI / corrupted runtime: keep the feature usable in demo mode.
            Log.w("StreetSnapPose", "pose landmarker unavailable", error)
            _state.update {
                it.copy(installError = "实时识别在此设备上不可用，已切换演示模式")
            }
            return false
        }
        if (closed) {
            newDetector.close()
            return false
        }
        detector = newDetector
        synchronized(coachLock) {
            coach.reset()
            smoother.reset()
            lastPersonAtMs = 0L
        }
        val newSource = PoseFrameSource(
            onError = { error -> Log.w("StreetSnapPose", error) },
        ) { bitmap -> newDetector.detect(bitmap) }
        _source.value = newSource
        return true
    }

    private fun acceptSample(raw: PoseSample?) {
        val now = SystemClock.elapsedRealtime()
        val guide = synchronized(coachLock) {
            val present = raw != null &&
                raw.points.count { it.isVisible } >= PERSON_MIN_VISIBLE_POINTS
            val smoothed = if (present) {
                lastPersonAtMs = now
                smoother.accept(raw!!)
            } else {
                // One empty/near-empty MediaPipe result (a dropped frame, a brief full-body
                // occlusion) must not flicker the skeleton or reset the ready streak: hold
                // the last guide for a short grace window; past it, clear and restart clean.
                if (lastPersonAtMs != 0L && now - lastPersonAtMs in 1..EMPTY_RESULT_GRACE_MS) {
                    return
                }
                smoother.reset()
                lastPersonAtMs = 0L
                null
            }
            coach.observe(smoothed, now)
        }
        _state.update { state ->
            if (!state.live) state else state.copy(
                guide = guide,
                currentPoseId = guide.definitionId,
                demo = false,
            )
        }
    }

    private fun stopLive() {
        val oldSource = _source.value
        val oldDetector = detector
        _source.value = null
        detector = null
        oldDetector?.let { value ->
            scope.launch(NonCancellable) { withContext(Dispatchers.IO) { value.close() } }
        }
        if (oldSource != null) {
            // Let CameraX observe the null source and unbind ImageAnalysis before closing its executor.
            scope.launch(NonCancellable) {
                kotlinx.coroutines.delay(300)
                withContext(Dispatchers.IO) { oldSource.close() }
            }
        }
        synchronized(coachLock) {
            coach.reset()
            smoother.reset()
            lastPersonAtMs = 0L
        }
        _state.update { it.copy(guide = null) }
    }

    fun selectPose(id: String) {
        val definition = PoseCatalog.poses.firstOrNull { it.id == id } ?: return
        synchronized(coachLock) { coach.select(definition) }
        _state.update { it.copy(currentPoseId = id, guide = null) }
    }

    fun nextPose() {
        val definition = synchronized(coachLock) { coach.next() }
        _state.update { it.copy(currentPoseId = definition.id, guide = null) }
    }

    /** Explicit in-app download action only. */
    fun install() {
        if (closed) return
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            _state.update { it.copy(modelStatus = PoseModelStatus.DOWNLOADING, installError = null,
                downloadedBytes = 0L) }
            try {
                val file = store.downloadPose(model) { downloaded, total ->
                    _state.update { it.copy(downloadedBytes = downloaded, totalBytes = total) }
                }
                modelPreferences.edit().remove("bundled-disabled-${model.id}").apply()
                _state.update { it.copy(modelStatus = PoseModelStatus.INSTALLED, installError = null) }
                reconfigure()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update {
                    it.copy(modelStatus = PoseModelStatus.FAILED,
                        installError = "姿势模型下载失败：${error.message ?: "请重试"}")
                }
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
        installJob = null
        scope.launch {
            val installed = checkInstalled()
            _state.update {
                it.copy(
                    modelStatus = if (installed != null) PoseModelStatus.INSTALLED else PoseModelStatus.ABSENT,
                    installError = null,
                )
            }
        }
    }

    fun removeModel() {
        installJob?.cancel()
        scope.launch {
            withContext(Dispatchers.IO) {
                modelPreferences.edit().putBoolean("bundled-disabled-${model.id}", true).apply()
                store.removePose(model)
            }
            _state.update { it.copy(modelStatus = PoseModelStatus.ABSENT) }
            // Reconfigure observes the missing file: stops live and falls back to demo.
            reconfigure()
        }
    }

    override fun close() {
        closed = true
        restartJob?.cancel()
        installJob?.cancel()
        runCatching { detector?.close() }
        _source.value?.close()
        _source.value = null
    }

    companion object {
        // Torso plus roughly head/upper limbs present; below this an "occupied" MediaPipe
        // result is treated as an empty detection instead of a person standing in frame.
        private const val PERSON_MIN_VISIBLE_POINTS = 8
        // ~2–3 pose results; matches PoseSmoother's per-joint occlusion hold.
        private const val EMPTY_RESULT_GRACE_MS = 350L
    }
}
