import groovy.json.JsonOutput
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val isCiBuild = providers.gradleProperty("ciBuild").map { it.toBoolean() }.getOrElse(false)
// Optional local-only model staging. The property is intentionally absent from normal
// builds; when supplied, the model directory is copied into ignored app assets so the
// Android plugin packages it in the APK without putting weights in source control.
val edgeModelDir = providers.gradleProperty("edgeModelDir").orNull?.let { file(it) }
// Resolve an explicitly selected manifest relative to the repository root, while
// its weight paths still refer to edgeModelDir (which may be outside the repo).
val explicitEdgeModelManifest = providers.gradleProperty("edgeModelManifest").orNull?.let { rootProject.file(it) }
require(explicitEdgeModelManifest == null || edgeModelDir != null) { "edgeModelManifest requires edgeModelDir" }
val edgeModelManifest = edgeModelDir?.let { source ->
    require(source.isDirectory) { "edgeModelDir must be a model directory: $source" }
    explicitEdgeModelManifest?.also {
        require(it.isFile) { "edgeModelManifest must be an existing file: $it" }
    } ?: source.resolve("install.json").takeIf { it.isFile }
        ?: source.resolve("street-snap-manifest.json").takeIf { it.isFile }
        ?: error("edgeModelDir must contain install.json or street-snap-manifest.json: $source")
}
val edgeModelManifestData = edgeModelManifest?.let { manifest ->
    require(manifest.length() in 1..2_000_000L) { "edge model manifest is empty or too large" }
    JsonSlurper().parseText(manifest.readText()) as? Map<*, *> ?: error("edge model manifest must be a JSON object")
}
val edgeModelAssetName = edgeModelManifestData?.let { data ->
    val id = data["model"] as? String
    val revision = data["revision"] as? String
    require(id != null && id.matches(Regex("[A-Za-z0-9_-]+")) &&
        revision != null && revision.matches(Regex("[a-f0-9]{40}"))) {
        "edge model manifest must declare model and a 40-character revision"
    }
    "$id-$revision"
}
fun validEdgeModelPath(path: String): Boolean = path.matches(Regex("[A-Za-z0-9_./-]+")) &&
    !path.startsWith("/") && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
val edgeModelFileRecords = edgeModelManifestData?.let { data ->
    val files = data["files"] as? List<*> ?: error("edge model manifest must declare files")
    require(files.isNotEmpty() && files.size <= 32) { "edge model manifest file count is invalid" }
    files.map { value ->
        val record = value as? Map<*, *> ?: error("invalid edge model file record")
        val path = record["path"] as? String ?: error("manifest file missing path")
        val bytes = (record["bytes"] as? Number)?.toLong() ?: error("manifest file missing size: $path")
        val digest = record["digest"] as? String ?: record["sha256"] as? String
            ?: error("manifest file missing digest: $path")
        val sha256 = record["sha256"] as? Boolean ?: (digest.length == 64)
        require(validEdgeModelPath(path) && bytes > 0 && digest.matches(Regex(if (sha256) "[a-f0-9]{64}" else "[a-f0-9]{40}"))) {
            "invalid edge model file record: $path"
        }
        require(requireNotNull(edgeModelDir).resolve(path).isFile) { "edgeModelDir is missing $path" }
        mapOf("path" to path, "bytes" to bytes, "digest" to digest, "sha256" to sha256)
    }.also { records ->
        require(records.map { it["path"] }.distinct().size == records.size) { "duplicate edge model file paths" }
    }
}
val edgeModelFilePaths = edgeModelFileRecords?.map { it.getValue("path") as String }
val edgeModelCanonicalManifest = edgeModelManifestData?.let { data ->
    // Accept both verifier graphs + sha256 strings and runtime direct graph names
    // + digest/sha256 flags. The schema, rather than the filename, selects the form.
    val graphs = data["graphs"] as? Map<*, *>
    fun graph(name: String): String {
        val path = data[name] as? String ?: graphs?.get(name) as? String
            ?: error("edge model manifest missing $name graph")
        require(path in requireNotNull(edgeModelFilePaths)) { "manifest $name graph must appear in files" }
        return path
    }
    JsonOutput.toJson(mapOf(
        "model" to data["model"], "revision" to data["revision"],
        "vision" to graph("vision"), "embedding" to graph("embedding"), "decoder" to graph("decoder"),
        "files" to requireNotNull(edgeModelFileRecords),
    ))
}
val stageEdgeModelAssets = edgeModelDir?.let { source ->
    val destination = layout.projectDirectory.dir("src/main/assets/edge-models/$edgeModelAssetName").asFile
    tasks.register<Sync>("stageEdgeModelAssets") {
        from(source)
        into(destination)
        // Hugging Face caches are not runtime inputs and can be much larger than the
        // selected graphs. Keep only the manifest and model files in APK assets.
        exclude(".cache/**", "**/.cache/**", "street-snap-manifest.json", "install.json")
        include(*requireNotNull(edgeModelFilePaths).toTypedArray())
        inputs.file(requireNotNull(edgeModelManifest))
        inputs.property("canonicalManifest", requireNotNull(edgeModelCanonicalManifest))
        outputs.file(destination.resolve("install.json"))
        doLast {
            destination.resolve("install.json").writeText(requireNotNull(edgeModelCanonicalManifest))
        }
    }
}
if (stageEdgeModelAssets != null) {
    tasks.named("preBuild").configure { dependsOn(stageEdgeModelAssets) }
} else {
    // A previous local model build may have left ignored assets in the source tree.
    // Clear them for ordinary Preview/CI builds so the default APK never inherits
    // a developer's private model weights.
    val clearStagedEdgeModelAssets = tasks.register<Delete>("clearStagedEdgeModelAssets") {
        delete(layout.projectDirectory.dir("src/main/assets/edge-models"))
    }
    tasks.named("preBuild").configure { dependsOn(clearStagedEdgeModelAssets) }
}
// Optional local-only pose weight staging, same contract as edgeModelDir: the
// property is absent from normal builds, the .task is copied into ignored app
// assets (pose-models/<id>/model.task, the layout ModelStore.installBundledPose
// imports from), and ordinary builds clear any leftover staged file.
val poseModelFile = providers.gradleProperty("poseModelFile").orNull?.let { rootProject.file(it) }
    ?.also { source ->
        require(source.isFile) { "poseModelFile must be an existing .task file: $source" }
        // Pinned against PoseModelCatalog.lite.bytes (5.8MB fp16 lite tier).
        require(source.length() == 5_777_746L) { "pose model size mismatch: ${source.length()}" }
    }
if (poseModelFile != null) {
    val stagePoseModelAsset = tasks.register<Copy>("stagePoseModelAsset") {
        from(poseModelFile)
        into(layout.projectDirectory.dir("src/main/assets/pose-models/pose_landmarker_lite"))
        rename { "model.task" }
    }
    tasks.named("preBuild").configure { dependsOn(stagePoseModelAsset) }
} else {
    val clearStagedPoseModelAssets = tasks.register<Delete>("clearStagedPoseModelAssets") {
        delete(layout.projectDirectory.dir("src/main/assets/pose-models"))
    }
    tasks.named("preBuild").configure { dependsOn(clearStagedPoseModelAssets) }
}
val distributionSigning = listOf(
    "ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
val hasDistributionSigning = distributionSigning.values.all { it != null }
check(hasDistributionSigning || distributionSigning.values.all { it == null }) {
    "Release signing requires ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD."
}

android {
    namespace = "dev.streetsnap.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.streetsnap.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0-dev"
        resValue("string", "app_name", "途帧")
        // T72: MediaPipe Tasks Vision ships native libraries for these ABIs; 32-bit ARM
        // is dropped (minSdk 31 devices are arm64). x86_64 keeps emulator smoke runs alive.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    if (hasDistributionSigning) {
        signingConfigs.create("distribution") {
            storeFile = file(requireNotNull(distributionSigning["ANDROID_KEYSTORE_PATH"]))
            storePassword = distributionSigning["ANDROID_KEYSTORE_PASSWORD"]
            keyAlias = distributionSigning["ANDROID_KEY_ALIAS"]
            keyPassword = distributionSigning["ANDROID_KEY_PASSWORD"]
        }
    }

    buildTypes {
        debug {
            if (isCiBuild) {
                applicationIdSuffix = ".ci"
                resValue("string", "app_name", "途帧 CI")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasDistributionSigning) signingConfig = signingConfigs.getByName("distribution")
        }
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            if (isCiBuild) {
                applicationIdSuffix = ".ci"
                resValue("string", "app_name", "途帧 CI")
            }
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        resValues = true
    }
    androidResources {
        // Weight binaries are already entropy-coded; store them uncompressed so asset
        // import streams bytes straight out of the APK instead of inflating ~440MB.
        noCompress += listOf("onnx", "onnx_data", "task")
    }
}

dependencies {
    implementation(project(":camera"))
    implementation(project(":color"))
    // Small ARM64-only ONNX Runtime slice. The app never downloads this runtime;
    // the optional VLM weights are fetched only after an explicit phone action.
    implementation(files("libs/onnxruntime-android-1.22.0-arm64.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.camera:camera-view:1.5.1")
    implementation("androidx.camera:camera-core:1.5.1")
    // T72 on-device 33-point pose landmarker (Apache-2.0, Google Maven). Weights are not
    // packaged: the lite .task bundle is downloaded only after explicit user action.
    implementation("com.google.mediapipe:tasks-vision:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
