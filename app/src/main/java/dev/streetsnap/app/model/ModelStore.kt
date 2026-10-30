package dev.streetsnap.app.model

import android.content.Context
import android.content.res.AssetManager
import android.os.StatFs
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用内唯一的权重获取模块（单一职责）：d1 多文件包与 pose 单文件都经此落盘，
 * 其他任何类都不得直接发起权重 HTTP 请求。
 *
 * 两类权重共用同一条管线：固定摘要校验 → 多源有序故障转移 → HTTP Range 断点续传
 * → 原子改名 → 就绪标记。下载源优先级统一在 [ModelSources]（魔搭国内 CDN 第一）。
 *
 * 落盘布局保持长期兼容（应用同签名覆盖安装 filesDir 不丢，老版本已下的模型零重下）：
 * - d1：filesDir/edge-models/&lt;id&gt;-&lt;revision&gt;/（install.json 为就绪标记）
 * - pose：filesDir/pose-models/&lt;id&gt;/model.task
 *
 * 构造函数不发起任何下载。
 */
class ModelStore(context: Context) {
    private val d1Root = File(context.filesDir, "edge-models")
    private val poseRoot = File(context.filesDir, "pose-models")
    private val assets: AssetManager = context.assets

    // ================= d1 多文件包 =================

    /**
     * 仅取元数据，绝不下权重。发布包把固定文件清单（数 KB）作为 APK asset 内置，
     * 因此离线也能返回，且 HuggingFace 不可达不影响；在线 API 只是未内置清单时的兜底。
     */
    suspend fun prepareD1Plan(model: D1ModelEntry): ModelDownloadPlan = withContext(Dispatchers.IO) {
        require(D1ModelCatalog.models.contains(model)) { "未知模型来源" }
        bundledD1Plan(model)?.let { return@withContext it }
        fetchD1Plan(model)
    }

    private suspend fun fetchD1Plan(model: D1ModelEntry): ModelDownloadPlan {
        // 仅在 APK 未内置清单时走到这里，源为 HuggingFace 系目录 API（魔搭目录 API
        // 结构不同，且发布包恒有内置清单，无需在此维护第二套解析）。
        val bases = listOf("https://hf-mirror.com", "https://huggingface.co")
        var lastError: Exception? = null
        for (base in bases) {
            try {
                val info = JSONObject(readSmall("$base/api/models/${model.repository}"))
                val revision = info.getString("sha")
                require(revision.matches(Regex("[a-f0-9]{40}"))) { "模型版本号无效" }
                require(revision == model.revision) { "模型仓库版本已变化，请更新应用内目录" }
                val tree = JSONArray(readSmall(
                    "$base/api/models/${model.repository}/tree/$revision?recursive=true&limit=1000"))
                return parseD1Tree(model, revision, tree)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw (lastError ?: IOException("无法读取模型目录"))
    }

    private fun parseD1Tree(model: D1ModelEntry, revision: String, tree: JSONArray): ModelDownloadPlan {
        val all = buildList {
            for (i in 0 until tree.length()) {
                val item = tree.getJSONObject(i)
                if (item.optString("type") != "file") continue
                val path = item.getString("path")
                if (!safePath(path)) continue
                val lfs = item.optJSONObject("lfs")
                val digest = lfs?.optString("oid")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                val fallback = item.optString("oid").takeIf { it.matches(Regex("[a-f0-9]{40}")) }
                    ?: item.optString("blobId").takeIf { it.matches(Regex("[a-f0-9]{40}")) }
                if (digest == null && fallback == null) continue
                add(WeightFile(path, item.getLong("size"), digest ?: requireNotNull(fallback), digest != null))
            }
        }
        // 唯一在目录中的排序模型是 Liquid d1-omni 决策模型：fp16 SigLIP2 视觉塔、
        // Q4 block-gather embedding 与 4-bit MatMulNBits 决策图。官方 8-bit 决策图
        // 无法在 ORT 1.22 上运行（节点缺 accuracy_level=4，ORT 走进只支持 4-bit 的
        // 解包路径）；q4 是受支持路径。
        val graphs = listOf(
            "onnx/vision_encoder_fp16.onnx",
            "onnx/embed_tokens_quantized.onnx",
            "onnx/decision_q4.onnx",
        )
        // LFM Open License 要求许可证文本随权重一同分发。
        val required = listOf("config.json", "tokenizer.json", "tokenizer_config.json",
            "preprocessor_config.json", "LICENSE")
        require(graphs.all { expected -> all.any { it.path == expected } }) { "模型 ONNX 图不完整" }
        val (vision, embedding, decoder) = graphs
        require(required.all { expected -> all.any { it.path == expected } }) { "模型配套配置不完整" }
        val optional = listOf("processor_config.json", "generation_config.json", "LICENSE", "LICENSE.txt")
        val files = all.filter { file -> file.path in required || file.path in optional || graphs.any {
            file.path == it || file.path.startsWith("${it}_data") || file.path == "$it.data"
        } }
        require(files.all { it.bytes in 1..MAX_FILE_BYTES }) { "模型文件大小超出支持范围" }
        require(files.sumOf { it.bytes } in 1..MAX_TOTAL_BYTES) { "模型包过大" }
        return ModelDownloadPlan(model, revision, files, vision, embedding, decoder)
    }

    /** 仅在响应用户显式下载动作时调用。 */
    suspend fun downloadD1(
        plan: ModelDownloadPlan,
        onProgress: (ModelDownloadProgress) -> Unit = {},
    ): InstalledD1Model = withContext(Dispatchers.IO) {
        validateD1Plan(plan)
        d1Root.mkdirs()
        checkSpace(d1Root, plan.totalBytes)
        val folder = File(d1Root, "${plan.model.id}-${plan.revision}").apply { mkdirs() }
        var complete = 0L
        for (spec in plan.files) {
            currentCoroutineContext().ensureActive()
            val destination = File(folder, spec.path)
            if (destination.isFile && verify(destination, spec)) {
                complete += spec.bytes
                onProgress(ModelDownloadProgress(complete, plan.totalBytes, spec.path))
                continue
            }
            destination.parentFile?.mkdirs()
            fetchFile(ModelSources.d1(plan.model.repository, plan.revision, spec.path), spec, destination) { copied ->
                onProgress(ModelDownloadProgress(complete + copied, plan.totalBytes, spec.path))
            }
            complete += spec.bytes
            onProgress(ModelDownloadProgress(complete, plan.totalBytes, spec.path))
        }
        // 完整清单是就绪标记；中断的安装永远不会被对外提供。
        val manifest = File(folder, "install.json.part")
        manifest.writeText(encodeD1Plan(plan).toString(), Charsets.UTF_8)
        require(manifest.renameTo(File(folder, "install.json"))) { "无法完成模型安装" }
        InstalledD1Model(plan, folder)
    }

    /**
     * 导入构建期有意 staged 进 assets 的模型。
     *
     * assets 被复制进私有模型目录；同一 revision 的不同图变体各占目录，已装模型不受
     * 影响。复制前先校验清单，每个文件逐字节哈希。仅有清单而无权重的 asset（发布包
     * 内置的固定清单）不算内置模型：返回 null，正常下载与已有安装继续可用。
     * 图配置相同的有效安装直接复用。
     */
    suspend fun installBundledD1(model: D1ModelEntry): InstalledD1Model? = withContext(Dispatchers.IO) {
        require(model in D1ModelCatalog.models) { "未知模型来源" }
        val bundledPlan = bundledD1Plan(model) ?: return@withContext null
        val assetRoot = "edge-models/${model.id}-${model.revision}"
        // 发布包只内置清单（权重是显式下载），权重 asset 不存在时安静让位于
        // 正常下载 / 已有安装。
        if (!bundledPlan.files.all { assetExists("$assetRoot/${it.path}") }) return@withContext null
        installedD1Models(model).firstOrNull { sameVariant(it.plan, bundledPlan) }
            ?.let { return@withContext it }
        val manifestPath = bundledD1ManifestPath(model)
        val manifestText = readAssetSmall(requireNotNull(manifestPath))

        d1Root.mkdirs()
        checkSpace(d1Root, bundledPlan.totalBytes)
        val folderName = bundledFolderName(bundledPlan)
        val folder = File(d1Root, folderName)
        val staging = File(d1Root, "$folderName.asset-part")
        if (staging.exists()) require(staging.deleteRecursively()) { "无法清理未完成的内置模型" }
        staging.mkdirs()
        try {
            var copiedTotal = 0L
            for (spec in bundledPlan.files) {
                currentCoroutineContext().ensureActive()
                val assetPath = "$assetRoot/${spec.path}"
                require(assetExists(assetPath)) { "内置模型缺少文件：${spec.path}" }
                val destination = File(staging, spec.path)
                destination.parentFile?.mkdirs()
                val partial = File(destination.path + ".part")
                var copied = 0L
                assets.open(assetPath).use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= spec.bytes) { "内置模型文件超出声明大小：${spec.path}" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(copied == spec.bytes && verify(partial, spec)) { "内置模型校验失败：${spec.path}" }
                require(partial.renameTo(destination)) { "无法保存内置模型文件：${spec.path}" }
                copiedTotal += copied
            }
            require(copiedTotal == bundledPlan.totalBytes) { "内置模型大小与清单不一致" }
            val manifest = File(staging, "install.json.part")
            manifest.writeText(manifestText, Charsets.UTF_8)
            require(manifest.renameTo(File(staging, "install.json"))) { "无法保存内置模型清单" }
            // 绝不替换另一套有效图变体（含历史 Q4 目录）；只替换本变体路径上不完整的数据。
            val existing = installedD1Model(folder, model)
            if (existing != null) {
                require(sameVariant(existing.plan, bundledPlan)) { "内置模型目录已被其他有效版本占用" }
                require(staging.deleteRecursively()) { "无法清理内置模型临时文件" }
                return@withContext existing
            }
            if (folder.exists()) require(folder.deleteRecursively()) { "无法替换未完成的内置模型" }
            require(staging.renameTo(folder)) { "无法完成内置模型安装" }
            InstalledD1Model(bundledPlan, folder)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun installedD1(model: D1ModelEntry): InstalledD1Model? {
        val models = installedD1Models(model)
        val bundled = bundledD1Plan(model)
        // APK 升级可能在 pinned revision 不变时改了量化方案：优先本 APK 的图；
        // 普通 APK 仍可使用此前导入的模型。
        return bundled?.let { plan -> models.firstOrNull { sameVariant(it.plan, plan) } }
            ?: models.firstOrNull()
    }

    private fun installedD1Models(model: D1ModelEntry): List<InstalledD1Model> =
        d1Root.listFiles()?.asSequence()
            ?.filter { it.isDirectory && it.name.startsWith(model.id + "-") &&
                !it.name.endsWith(".asset-part") }
            ?.sortedBy { it.name }
            ?.mapNotNull { installedD1Model(it, model) }
            ?.toList().orEmpty()

    private fun installedD1Model(folder: File, model: D1ModelEntry): InstalledD1Model? = runCatching {
        val manifest = File(folder, "install.json")
        require(manifest.length() in 1..MAX_METADATA_BYTES.toLong())
        val plan = decodeD1Plan(JSONObject(manifest.readText(Charsets.UTF_8)))
        validateD1Plan(plan)
        require(plan.model == model)
        require(plan.files.all { File(folder, it.path).isFile && File(folder, it.path).length() == it.bytes })
        InstalledD1Model(plan, folder)
    }.getOrNull()

    /**
     * 清单 asset 位置，按序：完整权重构建 stage 在 edge-models/&lt;id&gt;-&lt;rev&gt;/install.json
     * （权重文件同在，但永不入源码仓）；普通发布包只放 edge-model-manifests/&lt;id&gt;-&lt;rev&gt;.json，
     * 使 prepareD1Plan 无需联网，APK 与 git 都不含权重。
     */
    private fun bundledD1ManifestPath(model: D1ModelEntry): String? = listOf(
        "edge-models/${model.id}-${model.revision}/install.json",
        "edge-model-manifests/${model.id}-${model.revision}.json",
    ).firstOrNull { assetExists(it) }

    private fun bundledD1Plan(model: D1ModelEntry): ModelDownloadPlan? {
        val manifestPath = bundledD1ManifestPath(model) ?: return null
        val plan = try {
            decodeD1Plan(JSONObject(readAssetSmall(manifestPath)))
        } catch (error: Exception) {
            throw IllegalArgumentException("内置模型安装清单无效", error)
        }
        validateD1Plan(plan)
        require(plan.model == model && plan.revision == model.revision) { "内置模型版本与应用目录不一致" }
        require(plan.totalBytes > 0) { "内置模型为空" }
        return plan
    }

    private fun sameVariant(first: ModelDownloadPlan, second: ModelDownloadPlan): Boolean =
        first.model == second.model && first.revision == second.revision &&
            first.visionFile == second.visionFile && first.embeddingFile == second.embeddingFile &&
            first.decoderFile == second.decoderFile

    private fun bundledFolderName(plan: ModelDownloadPlan): String {
        val key = listOf(plan.model.id, plan.revision, plan.visionFile, plan.embeddingFile, plan.decoderFile)
            .joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "${plan.model.id}-${plan.revision}-bundle-${digest.take(24)}"
    }

    /** 在卸载该模型的推理会话之后调用。 */
    suspend fun removeD1(model: D1ModelEntry) = withContext(Dispatchers.IO) {
        require(model in D1ModelCatalog.models)
        d1Root.listFiles()?.filter { it.isDirectory && it.name.startsWith(model.id + "-") }?.forEach {
            require(it.deleteRecursively()) { "无法删除模型文件" }
        }
    }

    // ================= pose 单文件 =================

    fun poseFile(entry: PoseModelEntry): File = File(File(poseRoot, entry.id), "model.task")

    /** 大小与 SHA-256 都匹配的完整文件；否则 null。 */
    fun installedPose(entry: PoseModelEntry): File? = runCatching {
        val file = poseFile(entry)
        if (!file.isFile || file.length() != entry.bytes) return null
        if (!verify(file, entry.weight)) return null
        file
    }.getOrNull()

    /**
     * 导入构建期有意 staged 进 assets 的 pose 权重（完整权重构建）：
     * assets/pose-models/&lt;id&gt;/model.task。普通发布包没有该 asset，安静返回 null，
     * 正常下载与已有安装继续可用。已存在的有效文件（大小 + SHA-256 匹配）直接复用；
     * 复制经 .asset-part 暂存并逐字节哈希，通过后才原子改名。
     */
    suspend fun installBundledPose(entry: PoseModelEntry): File? = withContext(Dispatchers.IO) {
        require(PoseModelCatalog.models.contains(entry)) { "未知姿势模型" }
        val assetPath = "pose-models/${entry.id}/model.task"
        if (!assetExists(assetPath)) return@withContext null
        installedPose(entry)?.let { return@withContext it }
        poseRoot.mkdirs()
        checkSpace(poseRoot, entry.bytes)
        val target = poseFile(entry)
        target.parentFile?.mkdirs()
        val partial = java.io.File(target.path + ".asset-part")
        var copied = 0L
        try {
            assets.open(assetPath).use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= entry.bytes) { "内置姿势模型超出声明大小" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(copied == entry.bytes && verify(partial, entry.weight)) { "内置姿势模型校验失败" }
            target.delete()
            require(partial.renameTo(target)) { "无法保存内置姿势模型" }
            target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    /**
     * 仅用户显式触发。按序尝试 [ModelSources] 的每个匿名源，经 HTTP Range 从 .part
     * 偏移续传；大小/哈希不符则丢弃 .part 换下一个源。
     */
    suspend fun downloadPose(
        entry: PoseModelEntry,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        require(PoseModelCatalog.models.contains(entry)) { "未知姿势模型" }
        poseRoot.mkdirs()
        checkSpace(poseRoot, entry.bytes)
        val target = poseFile(entry)
        target.parentFile?.mkdirs()
        fetchFile(entry.downloadUrls, entry.weight, target) { copied ->
            onProgress(copied, entry.bytes)
        }
        target
    }

    suspend fun removePose(entry: PoseModelEntry) = withContext(Dispatchers.IO) {
        File(poseRoot, entry.id).deleteRecursively()
    }

    // ================= 共用下载引擎 =================

    /**
     * 全应用唯一的文件搬运实现：按序尝试每个匿名源并支持 Range 断点续传，网络失败时
     * 保留 .part 供下一源（或下次重试）续传；大小/哈希不符说明盘上字节不属于本文件，
     * 丢弃后从头重试。
     */
    private suspend fun fetchFile(
        sources: List<String>,
        spec: WeightFile,
        destination: File,
        onProgress: (Long) -> Unit,
    ) {
        val partial = File(destination.path + ".part")
        var lastError: Exception? = null
        for ((index, address) in sources.withIndex()) {
            currentCoroutineContext().ensureActive()
            val resume = if (partial.isFile) partial.length().coerceAtMost(spec.bytes) else 0L
            val connection = connection(address)
            try {
                if (resume in 1 until spec.bytes) {
                    connection.setRequestProperty("Range", "bytes=$resume-")
                }
                val startAt: Long
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> startAt = 0L
                    HttpURLConnection.HTTP_PARTIAL -> {
                        val range = connection.getHeaderField("Content-Range").orEmpty().lowercase()
                        require(range.startsWith("bytes $resume-")) { "下载源不支持从断点继续" }
                        startAt = resume
                    }
                    else -> throw IOException("下载失败（$code）")
                }
                java.io.FileOutputStream(partial, startAt > 0).buffered().use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = startAt
                        var lastProgress = startAt
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= spec.bytes) { "模型响应超出声明大小" }
                            output.write(buffer, 0, count)
                            if (copied - lastProgress >= 512 * 1024 || copied == spec.bytes) {
                                onProgress(copied)
                                lastProgress = copied
                            }
                        }
                        require(copied == spec.bytes) { "模型下载不完整" }
                    }
                }
                require(verify(partial, spec)) { "模型校验失败，请重新下载" }
                destination.delete()
                require(partial.renameTo(destination)) { "无法保存模型文件" }
                return
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                // 完整但错误/超长的 .part 不能在下一源续传。
                if (partial.isFile && partial.length() >= spec.bytes && index < sources.lastIndex) {
                    partial.delete()
                }
            } finally {
                connection.disconnect()
            }
        }
        throw (lastError ?: IOException("所有下载源均不可用"))
    }

    private fun checkSpace(root: File, bytes: Long) {
        require(StatFs(root.absolutePath).availableBytes > bytes + 64L * 1024 * 1024) { "可用空间不足" }
    }

    private suspend fun readSmall(address: String): String {
        val connection = connection(address)
        return try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "无法读取模型目录（${connection.responseCode}）" }
            require(connection.contentLengthLong <= MAX_METADATA_BYTES) { "模型目录过大" }
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= MAX_METADATA_BYTES) { "模型目录过大" }
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }

    private fun assetExists(path: String): Boolean = runCatching {
        assets.open(path).use { }
        true
    }.getOrDefault(false)

    private fun readAssetSmall(path: String): String {
        assets.open(path).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_METADATA_BYTES) { "内置模型清单过大" }
                out.write(buffer, 0, count)
            }
            return out.toString("UTF-8")
        }
    }

    private fun connection(address: String): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "street-snap/model-download")
            setRequestProperty("Accept-Encoding", "identity")
        }

    /**
     * 大小 + 摘要双重校验；git blob SHA-1 需带 blob 长度前缀。非挂起：
     * 已安装探测（[installedPose]）可能在普通上下文调用；逐文件循环的取消点在调用方。
     */
    private fun verify(file: File, spec: WeightFile): Boolean {
        if (file.length() != spec.bytes) return false
        val digest = MessageDigest.getInstance(if (spec.isSha256) "SHA-256" else "SHA-1")
        if (!spec.isSha256) digest.update("blob ${spec.bytes}\u0000".toByteArray(Charsets.UTF_8))
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == spec.digest
    }

    private fun validateD1Plan(plan: ModelDownloadPlan) {
        require(plan.model in D1ModelCatalog.models && plan.revision.matches(Regex("[a-f0-9]{40}")))
        require(plan.files.isNotEmpty() && plan.files.size <= 32 && plan.files.map { it.path }.distinct().size == plan.files.size)
        require(plan.files.all { safePath(it.path) && it.bytes in 1..MAX_FILE_BYTES &&
            it.digest.matches(Regex(if (it.isSha256) "[a-f0-9]{64}" else "[a-f0-9]{40}")) })
        require(plan.totalBytes <= MAX_TOTAL_BYTES)
        require(listOf(plan.visionFile, plan.embeddingFile, plan.decoderFile).all { graph -> plan.files.any { it.path == graph } })
    }

    private fun encodeD1Plan(plan: ModelDownloadPlan) = JSONObject().apply {
        put("model", plan.model.id); put("revision", plan.revision)
        put("vision", plan.visionFile); put("embedding", plan.embeddingFile); put("decoder", plan.decoderFile)
        put("files", JSONArray().apply { plan.files.forEach { file -> put(JSONObject().apply {
            put("path", file.path); put("bytes", file.bytes); put("digest", file.digest); put("sha256", file.isSha256)
        }) } })
    }

    private fun decodeD1Plan(json: JSONObject): ModelDownloadPlan {
        val files = json.getJSONArray("files")
        val graphs = json.optJSONObject("graphs")
        fun graph(name: String): String {
            val direct = json.optString(name).takeIf { it.isNotBlank() }
            return direct ?: graphs?.optString(name)?.takeIf { it.isNotBlank() }
                ?: error("模型安装清单缺少 $name 图")
        }
        return ModelDownloadPlan(
            requireNotNull(D1ModelCatalog.find(json.getString("model"))), json.getString("revision"),
            (0 until files.length()).map { index -> files.getJSONObject(index).let {
                val digest = it.optString("digest").takeIf { value -> value.isNotBlank() }
                    ?: it.optString("sha256").takeIf { value -> value.isNotBlank() }
                    ?: error("模型安装清单缺少文件摘要")
                WeightFile(it.getString("path"), it.getLong("bytes"), digest,
                    it.optBoolean("sha256", digest.matches(Regex("[a-f0-9]{64}"))))
            } }, graph("vision"), graph("embedding"), graph("decoder"),
        )
    }

    companion object {
        private const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        private const val MAX_FILE_BYTES = 768L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 1200L * 1024 * 1024
        private fun safePath(path: String): Boolean = path.length <= 160 &&
            path.matches(Regex("[A-Za-z0-9_./-]+")) && !path.startsWith('/') &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }
}
