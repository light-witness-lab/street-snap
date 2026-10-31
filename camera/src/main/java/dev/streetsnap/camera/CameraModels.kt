package dev.streetsnap.camera

import java.io.File

/** Equivalent focal lengths are crops of the selected rear camera, never optical-lens claims. */
enum class FocalLength(val equivalentMm: Int?) {
    NATIVE(null), MM24(24), MM28(28), MM35(35), MM40(40), MM50(50), MM75(75);

    val label: String get() = equivalentMm?.let { "${it}mm" } ?: "原生视角"

    companion object {
        fun fromMm(mm: Int): FocalLength? = entries.firstOrNull { it.equivalentMm == mm }
    }
}

/** Both exposure values mean M; time alone means supported S; neither means P. Focus is in diopters. */
data class CaptureSettings(
    val focalLength: FocalLength = FocalLength.MM28,
    val exposureCompensationIndex: Int = 0,
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDistanceDiopters: Float? = null,
    val autoExposureLocked: Boolean = false,
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AUTO,
) {
    val exposureMode: ExposureMode get() = ExposurePolicy.mode(iso, exposureTimeNs)
}

/**
 * The camera's ACTUAL metered exposure for the latest preview frame, read from a real
 * Camera2 CaptureResult — never a fabricated AUTO stand-in. In P/S the ISO and shutter are
 * chosen by the device AE and change live; in M they mirror the manual request. Aperture and
 * focal length are reported only when the result/characteristics actually carry them.
 */
data class LiveExposureReading(
    val iso: Int?,
    val exposureTimeNs: Long?,
    val aperture: Float? = null,
    val focalLengthMm: Float? = null,
    val frameTimestampNs: Long = 0L,
)

/**
 * T77: the HAL's face-statistics observation for the latest frame (sensor statistics, not
 * app-side detection): how many faces the 3A library reported and the largest face in
 * portrait-preview normalized coordinates. [count] 0 with null [primary] means "no face".
 */
data class FaceObservation(
    val count: Int,
    val primary: NormalizedBoundingBox?,
    val frameTimestampNs: Long,
)

data class CameraState(
    val isBound: Boolean = false,
    val isReady: Boolean = false,
    val isCapturing: Boolean = false,
    val isFocusing: Boolean = false,
    val focusResult: Boolean? = null,
    val isFocusLocked: Boolean = false,
    val capabilities: CameraCapabilities? = null,
    val settings: CaptureSettings = CaptureSettings(),
    val liveExposure: LiveExposureReading? = null,
    val liveFaces: FaceObservation? = null,
    val zoomRatio: Float = 1f,
    val error: String? = null,
    val compositionAnalysisActive: Boolean = false,
    val compositionAnalysisError: String? = null,
)

data class CameraCapabilities(
    val cameraId: String,
    val fixedPhysicalCameraId: String?,
    val device: String,
    val sensorOrientationDegrees: Int,
    val nativeEquivalentFocalLengthMm: Double?,
    val focalLengthOptions: List<FocalLength>,
    val maxZoomRatio: Float,
    val exposureCompensationMin: Int,
    val exposureCompensationMax: Int,
    val exposureCompensationStepEv: Double,
    val supportsManualExposure: Boolean,
    val isoMin: Int?,
    val isoMax: Int?,
    val exposureTimeMinNs: Long?,
    val exposureTimeMaxNs: Long?,
    val supportsManualFocus: Boolean,
    val minimumFocusDistanceDiopters: Float?,
    val focusDistanceCalibration: String,
    /** Device capability only; CameraX owns the active AF mode and tap-focus trigger. */
    val autoFocusMode: Int? = null,
    val supportsAutoExposureLock: Boolean,
    val supportsFocusMetering: Boolean,
    val notes: List<String>,
    val whiteBalanceModes: List<WhiteBalanceMode> = listOf(WhiteBalanceMode.AUTO),
    val supportsShutterPriority: Boolean = false,
    val fixedAperture: Float? = null,
    /**
     * T77: strongest Camera2 face statistics mode the HAL offers (2 = FULL with eye/mouth
     * landmarks, 1 = SIMPLE rectangles, null = none) and its maximum reportable face count.
     * When non-null the controller enables the mode and the HAL's own 3A drives face AF/AE;
     * the app only displays the rectangles the HAL reports.
     */
    val faceDetectMode: Int? = null,
    val maxFaceCount: Int = 0,
) {
    val supportsFaceDetection: Boolean get() = faceDetectMode != null && maxFaceCount > 0
    val exposureModes: List<ExposureMode> get() = buildList {
        add(ExposureMode.PROGRAM)
        if (supportsShutterPriority) add(ExposureMode.SHUTTER_PRIORITY)
        if (supportsManualExposure) add(ExposureMode.MANUAL)
    }
    fun toJson(): String = CameraCapabilityJson.encode(this)
}

/**
 * CameraX writes an EXIF-oriented, viewport-cropped JPEG into application cache only.
 * The app must process it, publish the finished result and delete this file in a finally block.
 * Read JPEG EXIF orientation before processing: the sensor pixels can still be rotated/mirrored.
 */
data class CaptureResult(
    val jpegFile: File,
    val settings: CaptureSettings,
    val capabilities: CameraCapabilities,
    val requestedAtElapsedRealtimeMs: Long,
    val completedAtElapsedRealtimeMs: Long,
)
