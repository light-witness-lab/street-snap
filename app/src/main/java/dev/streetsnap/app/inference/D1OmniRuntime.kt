package dev.streetsnap.app.inference

import dev.streetsnap.app.model.InstalledD1Model

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Liquid d1-omni-600M through the pinned ONNX image stack on CPU:
 * fp16 SigLIP2 vision tower, Q4 block-gather embeddings and the Q4 MatMulNBits
 * decision head. (The official 8-bit decision graph is unusable on ORT 1.22:
 * its nodes lack accuracy_level=4, so ORT takes the unpacked path that only
 * supports 4-bit weights.)
 *
 * One sampled contact sheet -> one vision pass, one embedding pass and one bidirectional
 * decision pass. There is no token-generation loop: the answer is a softmax over three
 * option markers. Mirrors SmolVlmRuntime's worker/cancel/close contract.
 */
class D1OmniRuntime(private val installed: InstalledD1Model) : CandidateRanker {
    private val environment: OrtEnvironment
    private val vision: OrtSession
    private val embeddings: OrtSession
    private val decision: OrtSession
    private val tokenizer: ByteBpeTokenizer
    private val bosTokenId: Int
    private val hiddenSize: Int
    private val imageTextLength: Int
    // Real thread budget/EP actually in use, surfaced verbatim to dev diagnostics.
    private val intraOpThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    private var visionUsesXnnpack = false
    override val runtimeLabel: String by lazy {
        // Keep in sync with libs/onnxruntime-android-*-arm64.aar; there is no NNAPI EP.
        val visionDelegate = if (visionUsesXnnpack) " · XNNPACK 视觉塔" else " · CPU 视觉塔（XNNPACK 回退）"
        "ONNX Runtime CPU · intra-op $intraOpThreads / inter-op 1$visionDelegate"
    }
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val activeOptions = AtomicReference<OrtSession.RunOptions?>()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        // Same rationale as SmolVlmRuntime: keep normal priority so native kernels
        // are not demoted on big.LITTLE phones; camera work stays on its own executor.
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT); runnable.run() }, "street-snap-d1")
    }

    init {
        require(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "本版端侧模型仅支持 ARM64 设备" }
        val config = readConfig("config.json")
        require(config.optString("model_type") == "d1_omni" ||
            config.optJSONArray("architectures")?.toString()?.contains("D1Omni") == true) { "模型架构不受支持" }
        val text = config.getJSONObject("text_config")
        hiddenSize = text.getInt("hidden_size")
        imageTextLength = config.optInt("image_text_length", D1Prompt.IMAGE_TEXT_LENGTH)
        require(hiddenSize in 128..4096 && imageTextLength in 64..16_384)
        bosTokenId = config.getInt("bos_token_id")
        tokenizer = ByteBpeTokenizer(File(installed.directory, "tokenizer.json"))
        // The delimiter/marker contract is fixed by d1's reserved token block;
        // specialId() fails closed on a missing token.
        for (token in listOf("<|mask|>", "<|reserved_7|>", "<|reserved_8|>", "<|reserved_9|>",
                "<|reserved_10|>", "<|reserved_11|>")) {
            tokenizer.specialId(token)
        }
        environment = OrtEnvironment.getEnvironment()
        val opened = mutableListOf<OrtSession>()
        try {
            // Returns the session paired with whether XNNPACK actually built the graph, so the
            // CPU-only retry below is observable rather than silently pretending XNNPACK is on.
            fun open(path: String, useXnnpack: Boolean, threads: Int): Pair<OrtSession, Boolean> =
                try {
                    OrtSession.SessionOptions().use { options ->
                        if (useXnnpack) options.addXnnpack(mapOf("intra_op_num_threads" to intraOpThreads.toString()))
                        options.setIntraOpNumThreads(threads)
                        options.setInterOpNumThreads(1)
                        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        environment.createSession(path, options) to useXnnpack
                    }
                } catch (error: Throwable) {
                    // MatMulNBits/GatherBlockQuantized may not be covered by every vendor
                    // build's XNNPACK path; retry CPU-only so a supported graph still loads.
                    if (useXnnpack) open(path, useXnnpack = false, threads = intraOpThreads) else throw error
                }
            open(File(installed.directory, installed.plan.visionFile).absolutePath,
                useXnnpack = true, threads = 1).also { (session, usedXnnpack) ->
                    vision = session
                    visionUsesXnnpack = usedXnnpack
                    opened += session
                }
            embeddings = open(File(installed.directory, installed.plan.embeddingFile).absolutePath,
                useXnnpack = false, threads = intraOpThreads).also { opened += it.first }.first
            decision = open(File(installed.directory, installed.plan.decoderFile).absolutePath,
                useXnnpack = false, threads = intraOpThreads).also { opened += it.first }.first
            require(vision.inputNames.containsAll(listOf("pixel_values", "pixel_attention_mask", "spatial_shapes"))) {
                "d1 视觉模型接口不兼容"
            }
            require(embeddings.inputNames == setOf("input_ids")) { "d1 嵌入模型接口不兼容" }
            require(decision.inputNames.containsAll(listOf("inputs_embeds", "attention_mask", "prefix_len",
                "marker_pos", "marker_mask", "qtype"))) { "d1 决策模型接口不兼容" }
            require((vision.inputInfo.getValue("pixel_values").info as TensorInfo).type == OnnxJavaType.FLOAT) {
                "d1 需要 FP32 patch 输入"
            }
            require((decision.inputInfo.getValue("inputs_embeds").info as TensorInfo).type == OnnxJavaType.FLOAT) {
                "d1 需要 FP32 嵌入输入"
            }
        } catch (error: Throwable) {
            opened.asReversed().forEach { runCatching { it.close() } }
            worker.shutdown()
            throw error
        }
    }

    /** Busy calls return null. Cancellation terminates ORT; close waits for the native call. */
    override suspend fun rankCandidates(sheet: Bitmap): CropRanking? {
        check(!closed.get()) { "模型已卸载" }
        if (!running.compareAndSet(false, true)) return null
        val pixels = try {
            withContext(Dispatchers.Default) { prepareFrame(sheet) }
        } catch (error: Throwable) {
            running.set(false)
            throw error
        }
        return suspendCancellableCoroutine { continuation ->
            val options = OrtSession.RunOptions()
            activeOptions.set(options)
            continuation.invokeOnCancellation { runCatching { options.setTerminate(true) } }
            try {
                worker.execute {
                    var result: CropRanking? = null
                    var failure: Throwable? = null
                    try {
                        result = synchronized(lock) {
                            check(!closed.get()) { "模型已卸载" }
                            if (continuation.isActive) rank(pixels, options) else null
                        }
                    } catch (error: Throwable) {
                        failure = error
                    } finally {
                        activeOptions.compareAndSet(options, null)
                        try { options.close() }
                        catch (error: Throwable) {
                            val original = failure
                            if (original == null) failure = error
                            else if (original !== error) original.addSuppressed(error)
                        } finally { running.set(false) }
                    }
                    if (continuation.isActive) {
                        val error = failure
                        if (error != null) continuation.resumeWithException(error)
                        else continuation.resume(result)
                    }
                }
            } catch (error: Throwable) {
                activeOptions.compareAndSet(options, null)
                try { options.close() }
                catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
                finally { running.set(false) }
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    /** The contact sheet is 512x512 by construction; defensively center-crop anything else. */
    private fun prepareFrame(source: Bitmap): IntArray {
        require(!source.isRecycled && source.width > 0 && source.height > 0)
        val square: Bitmap
        var created = false
        if (source.width == D1NaFlex.SIDE && source.height == D1NaFlex.SIDE) {
            square = source
        } else {
            val side = minOf(source.width, source.height)
            val left = (source.width - side) / 2
            val top = (source.height - side) / 2
            // Android may return `source` itself when the crop covers an already-square
            // bitmap. Keep track of ownership so the caller's bitmap is never recycled
            // by the temporary-frame cleanup below.
            val cropped = if (source.width == source.height) source
            else Bitmap.createBitmap(source, left, top, side, side)
            square = if (side == D1NaFlex.SIDE) cropped
            else Bitmap.createScaledBitmap(cropped, D1NaFlex.SIDE, D1NaFlex.SIDE, true).also {
                if (cropped !== source) cropped.recycle()
            }
            created = true
        }
        try {
            val argb = IntArray(D1NaFlex.SIDE * D1NaFlex.SIDE)
            square.getPixels(argb, 0, D1NaFlex.SIDE, 0, 0, D1NaFlex.SIDE, D1NaFlex.SIDE)
            return argb
        } finally { if (created) square.recycle() }
    }

    private fun rank(argb: IntArray, options: OrtSession.RunOptions): CropRanking? {
        val startedAt = SystemClock.elapsedRealtime()
        var visionMs = 0L
        var embeddingsMs = 0L
        var decisionMs = 0L
        var ranking: CropRanking? = null
        val temporary = mutableListOf<OnnxTensor>()
        try {
            // 1) Vision: one fixed 512 image -> 256 projected prefix embeddings.
            val patches = D1NaFlex.toPatchPixels(argb)
            val features: FloatArray
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(patches),
                longArrayOf(1, D1NaFlex.MAX_PATCHES.toLong(), D1NaFlex.PATCH_DIM.toLong())).also { temporary += it }.use { pixels ->
                OnnxTensor.createTensor(environment,
                    LongBuffer.wrap(LongArray(D1NaFlex.MAX_PATCHES) { 1L }),
                    longArrayOf(1, D1NaFlex.MAX_PATCHES.toLong())).also { temporary += it }.use { mask ->
                    OnnxTensor.createTensor(environment,
                        LongBuffer.wrap(longArrayOf(D1NaFlex.GRID.toLong(), D1NaFlex.GRID.toLong())),
                        longArrayOf(1, 2)).also { temporary += it }.use { shapes ->
                            vision.run(mapOf("pixel_values" to pixels, "pixel_attention_mask" to mask,
                                "spatial_shapes" to shapes), options).use { output ->
                                val tensor = output.tensor("image_features")
                                val buffer = requireNotNull(tensor.floatBuffer)
                                features = FloatArray(buffer.remaining()).also { buffer.get(it) }
                            }
                        }
                }
            }
            val prefixLength = features.size / hiddenSize
            require(prefixLength == D1NaFlex.PROJECTED_PREFIX) { "d1 视觉前缀长度不匹配：$prefixLength" }
            require(features.all { it.isFinite() }) { "d1 视觉特征无效" }
            visionMs = SystemClock.elapsedRealtime() - startedAt
            log("rank_vision_ms=$visionMs prefix=$prefixLength")

            // 2) Prompt: choice over A/B/C. State stays empty (the image is the whole
            // state, as the image tasks were trained); the criteria-rich v1 wording was
            // rejected by isolated probing on 2026-10-18 and reverted to v0.
            val embeddingsStarted = SystemClock.elapsedRealtime()
            val encoded = D1Prompt.encode(
                encode = { text -> tokenizer.encode(text) },
                idOf = { token -> if (token == "<bos>") bosTokenId else tokenizer.specialId(token) },
                state = D1Prompt.CANDIDATE_STATE,
                instructions = D1Prompt.CANDIDATE_QUESTION,
                options = D1Prompt.CANDIDATE_OPTIONS,
                maxLen = imageTextLength,
            )
            val ids = encoded.ids
            val optionCount = encoded.optionCount
            require(optionCount == 3) { "d1 构图选择必须是三个选项" }

            // 3) Token embeddings for the text sequence.
            val textEmbeddings: FloatArray
            OnnxTensor.createTensor(environment, LongBuffer.wrap(ids),
                longArrayOf(1, ids.size.toLong())).also { temporary += it }.use { tokenTensor ->
                embeddings.run(mapOf("input_ids" to tokenTensor), options).use { output ->
                    val buffer = requireNotNull(output.tensor("inputs_embeds").floatBuffer)
                    textEmbeddings = FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
            }
            require(textEmbeddings.size == ids.size * hiddenSize) { "d1 文本嵌入长度不匹配" }
            embeddingsMs = SystemClock.elapsedRealtime() - embeddingsStarted

            // 4) Prefix first, then text; marker positions stay text-relative (the graph
            // slices off prefix_len internally before gathering the markers).
            val combined = FloatArray(prefixLength * hiddenSize + textEmbeddings.size)
            features.copyInto(combined, 0)
            textEmbeddings.copyInto(combined, prefixLength * hiddenSize)
            val totalLength = prefixLength + ids.size
            val decisionStarted = SystemClock.elapsedRealtime()
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(combined),
                longArrayOf(1, totalLength.toLong(), hiddenSize.toLong())).also { temporary += it }.use { embedTensor ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(totalLength) { 1L }),
                    longArrayOf(1, totalLength.toLong())).also { temporary += it }.use { attention ->
                    OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(prefixLength.toLong())),
                        longArrayOf(1)).also { temporary += it }.use { prefix ->
                        OnnxTensor.createTensor(environment, LongBuffer.wrap(encoded.markerPositions),
                            longArrayOf(1, optionCount.toLong())).also { temporary += it }.use { markerPositions ->
                            OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(optionCount) { 1L }),
                                longArrayOf(1, optionCount.toLong())).also { temporary += it }.use { markerMask ->
                                OnnxTensor.createTensor(environment,
                                    LongBuffer.wrap(longArrayOf(D1Prompt.QTYPE_CHOICE)),
                                    longArrayOf(1)).also { temporary += it }.use { qtype ->
                                    decision.run(mapOf(
                                        "inputs_embeds" to embedTensor,
                                        "attention_mask" to attention,
                                        "prefix_len" to prefix,
                                        "marker_pos" to markerPositions,
                                        "marker_mask" to markerMask,
                                        "qtype" to qtype,
                                    ), setOf("logits"), options).use { output ->
                                        val tensor = output.tensor("logits")
                                        val shape = tensor.info.shape
                                        require(shape.size == 2 && shape[0] == 1L && shape[1] == optionCount.toLong()) {
                                            "d1 决策输出形状不匹配：${shape.toList()}"
                                        }
                                        val logits = FloatArray(optionCount).also {
                                            requireNotNull(tensor.floatBuffer).get(it)
                                        }
                                        // Record the decision stage before constructing the result so
                                        // diagnostics and callers receive the actual latency instead
                                        // of the initial zero value.
                                        decisionMs = SystemClock.elapsedRealtime() - decisionStarted
                                        ranking = D1Decision.toRanking(
                                            logits,
                                            visionMs = visionMs,
                                            embeddingsMs = embeddingsMs,
                                            decisionMs = decisionMs,
                                            promptTokens = ids.size,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ranking?.let { result ->
                log("rank_selected=${result.selectedIndex} support=${result.support} margin=${result.margin} " +
                    "probabilities=${result.probabilities} raw_scores=${result.rawScores} " +
                    "min_margin=${CropRankingScores.MIN_MARGIN} text_tokens=${ids.size} " +
                    "prompt=${D1Prompt.CANDIDATE_PROMPT_VERSION}")
            }
            return ranking
        } finally {
            temporary.asReversed().forEach { it.close() }
            log("rank_total_ms=${SystemClock.elapsedRealtime() - startedAt} vision_ms=$visionMs " +
                "embeddings_ms=$embeddingsMs decision_ms=$decisionMs selected=${ranking?.selectedIndex} " +
                "text_tokens=${ranking?.promptTokens ?: -1} " +
                "prompt=${D1Prompt.CANDIDATE_PROMPT_VERSION}")
        }
    }

    private fun log(message: String) {
        // Scores and timings only, never frame content.
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) Log.d(LOG_TAG, message)
    }

    private fun readConfig(name: String): JSONObject = File(installed.directory, name).let {
        require(it.length() in 1..1_000_000L) { "模型配置文件无效" }
        JSONObject(it.readText(Charsets.UTF_8))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeOptions.get()?.let { runCatching { it.setTerminate(true) } }
        synchronized(lock) {
            decision.close()
            embeddings.close()
            vision.close()
        }
        worker.shutdown()
        // OrtEnvironment is a process-wide singleton, not owned by a model session.
    }

    private fun OrtSession.Result.tensor(name: String): OnnxTensor =
        get(name).orElse(null) as? OnnxTensor ?: error("模型缺少 $name 输出")

    private companion object {
        const val LOG_TAG = "StreetSnapD1"
    }
}
