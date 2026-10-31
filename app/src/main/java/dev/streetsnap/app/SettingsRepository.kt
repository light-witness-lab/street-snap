package dev.streetsnap.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.streetsnap.app.model.D1ModelCatalog
import dev.streetsnap.camera.WhiteBalanceMode
import dev.streetsnap.color.ColorPreset
import dev.streetsnap.color.ColorRecipe
import dev.streetsnap.color.FilmRegistry
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.recipeStore by preferencesDataStore(name = "street_snap")

/** Each look keeps its own edits; white balance and diffusion remain camera-wide controls. */
data class LookSettings(
    val intensity: Float = 1f,
    val grain: Float = 0f,
    val vignette: Float = 0f,
    val saturation: Float = 0f,
    val hue: Float = 0f,
    val contrast: Float = 0f,
    val brightness: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val sharpness: Float = 0f,
    val clarity: Float = 0f,
) {
    companion object {
        fun fromRecipe(recipe: ColorRecipe) = LookSettings(
            intensity = recipe.intensity, grain = recipe.grain, vignette = recipe.vignette,
            saturation = recipe.saturation, hue = recipe.hue, contrast = recipe.contrast,
            brightness = recipe.brightness, highlights = recipe.highlights, shadows = recipe.shadows,
            sharpness = recipe.sharpness, clarity = recipe.clarity,
        )
    }
}

data class AppSettings(
    val focalLength: Int = 28,
    val exposure: Int = 0,
    val focus: String = "AUTO",
    val focusDiopters: Float = 0f,
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val grid: Boolean = true,
    val level: Boolean = true,
    // Viewfinder OSD cycle: OFF (default, pure viewfinder) / HISTOGRAM / PARAMS (histogram + exposure readout).
    val viewfinderOsd: String = ViewfinderOsd.OFF,
    val preset: String = "POSITIVE",
    val filmId: String? = null,
    // T121: the active, FUNCTIONAL camera body (BodyCatalog id; GENERAL for brand-neutral
    // looks). It drives the legal look list, focal set and film-section visibility.
    val cameraBody: String = "RICOH_GR",
    // T121: the top nameplate can still be hidden for a bigger viewfinder; the body itself
    // stays selectable from the shooting settings / 影调 body strip.
    val nameplateHidden: Boolean = false,
    val captureOrientation: String = CameraPreferences.DEFAULT_ORIENTATION,
    // T121 快拍模式: pose/composition/AI advice fully off (sources unbound), filters + HDF
    // remain. On by default for everyone — pro features live behind the mode toggle.
    val quickShotMode: Boolean = true,
    val compositionEnabled: Boolean = false,
    val compositionRealtime: Boolean = false,
    val compositionModel: String = D1ModelCatalog.DEFAULT_MODEL_ID,
    val looks: Map<String, LookSettings> = emptyMap(),
    val lutName: String? = null,
    val hdfEnabled: Boolean = false,
    val hdfStrength: Float = .4f,
    // Ricoh-style HDF (Highlight Diffusion Filter): pure optical diffusion of near-clipping
    // highlights (tight core + independent broad halo, no tone mapping), off by default.
    // JSON keys keep their legacy "hdr*" names for settings compatibility.
    val hdrEnabled: Boolean = false,
    val hdrStrength: Float = .6f,
    val whiteBalance: String = "AUTO",
    val whiteBalanceAmber: Int = 0,
    val whiteBalanceGreen: Int = 0,
    // Independent switch for the realtime look-suggestion card; on by default (T30).
    val lookAdviceEnabled: Boolean = true,
    // Independent switch for clipped-highlight/shadow exposure hints; on by default (T31).
    val exposureAdviceEnabled: Boolean = true,
    // T34: optional light haptic when framing reaches the suggested crop. Visual green
    // confirmation always shows; haptics are opt-in, and there is deliberately no sound.
    val alignmentHapticEnabled: Boolean = false,
    // T72 pose assistant: stick-figure overlay + pose library. On by default in demo mode
    // so the feature is visible immediately; demo binds no analysis stream and downloads
    // nothing. The MediaPipe weights are fetched only after an explicit install tap.
    val poseAssistantEnabled: Boolean = true,
    // look key → dismiss timestamp (epoch ms); adviser suppresses keys within a 7-day window.
    val lookDismissedAt: Map<String, Long> = emptyMap(),
    // Three consecutive dismisses flip lookAdviceEnabled off; trying/keeping a look resets it.
    val lookAdviceDismissStreak: Int = 0,
) {
    val activeLookKey: String get() = FilmRegistry.find(filmId)?.let { "FILM:${it.id}" } ?: preset
    val activeLook: LookSettings get() = looks[activeLookKey]
        ?: FilmRegistry.find(filmId)?.let { LookSettings.fromRecipe(it.defaults) }
        ?: LookSettings()

    fun updateLook(transform: (LookSettings) -> LookSettings) =
        copy(looks = looks + (activeLookKey to transform(activeLook)))
    fun resetLook() = copy(looks = looks - activeLookKey)

    fun colorRecipe(): ColorRecipe = activeLook.let { look ->
        ColorRecipe(
            preset = FilmRegistry.find(filmId)?.basePreset
                ?: runCatching { ColorPreset.valueOf(preset) }.getOrDefault(ColorPreset.ORIGINAL),
            intensity = look.intensity, grain = look.grain, vignette = look.vignette,
            saturation = look.saturation, hue = look.hue, contrast = look.contrast,
            brightness = look.brightness, highlights = look.highlights, shadows = look.shadows,
            sharpness = look.sharpness, clarity = look.clarity,
            hdfEnabled = hdfEnabled, hdfStrength = hdfStrength,
            hdrEnabled = hdrEnabled, hdrStrength = hdrStrength,
            whiteBalanceAmber = whiteBalanceAmber, whiteBalanceGreen = whiteBalanceGreen,
        )
    }
}

/** Stored IDs are independent of the Compose catalog so loading settings never needs UI code. */
internal object CameraPreferences {
    const val DEFAULT_ORIENTATION = "AUTO"

    /** Legacy pre-T121 value that hid the whole nameplate. */
    const val HIDDEN = "HIDDEN"
    private val bodies = CameraModel.entries.map { it.name }.toSet() + BodyCatalog.GENERAL_ID
    private val orientations = setOf("AUTO", "LANDSCAPE", "PORTRAIT")

    /** A known body id or the synthetic general body; unknown ids recover to general. */
    fun body(id: String?): String = id?.takeIf { it in bodies } ?: BodyCatalog.GENERAL_ID
    fun orientation(id: String?): String = id?.takeIf { it in orientations } ?: DEFAULT_ORIENTATION
}

/**
 * Viewfinder overlay cycle. The live exposure numbers now live on the always-on shoulder
 * strip above the shutter, so the in-picture cycle is just the histogram; PARAMS is kept as
 * a parseable legacy value (installed builds that stored it) and renders exactly like
 * HISTOGRAM. Nothing here is fabricated: every readout value is reported by the camera.
 */
internal object ViewfinderOsd {
    const val OFF = "OFF"
    const val HISTOGRAM = "HISTOGRAM"
    const val PARAMS = "PARAMS"
    private val modes = setOf(OFF, HISTOGRAM, PARAMS)
    fun mode(value: String?): String = value?.takeIf { it in modes } ?: OFF
    /** True whenever the small histogram window should be drawn (legacy PARAMS included). */
    fun showsHistogram(value: String?): Boolean = mode(value) != OFF
    fun next(value: String?): String = if (mode(value) == OFF) HISTOGRAM else OFF
}

/** Keep the old key so installing over v0.1 migrates settings without losing the selected look. */
class SettingsRepository(private val context: Context) {
    private val key = stringPreferencesKey("recipe_v1")
    val settings: Flow<AppSettings> = context.recipeStore.data
        .catch { exception ->
            if (exception is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw exception
        }
        .map { preferences -> SettingsJson.decode(preferences[key]) }

    suspend fun save(settings: AppSettings) {
        context.recipeStore.edit { it[key] = SettingsJson.encode(settings) }
    }
}

internal object SettingsJson {
    fun encode(settings: AppSettings): String = JSONObject().apply {
        put("version", 5)
        put("focalLength", settings.focalLength)
        put("exposure", settings.exposure)
        put("focus", settings.focus)
        put("focusDiopters", settings.focusDiopters.toDouble())
        put("iso", settings.iso ?: JSONObject.NULL)
        put("exposureTimeNs", settings.exposureTimeNs ?: JSONObject.NULL)
        put("grid", settings.grid)
        put("level", settings.level)
        put("viewfinderOsd", ViewfinderOsd.mode(settings.viewfinderOsd))
        put("preset", settings.preset)
        put("filmId", settings.filmId ?: JSONObject.NULL)
        put("cameraBody", CameraPreferences.body(settings.cameraBody))
        put("nameplateHidden", settings.nameplateHidden)
        put("quickShotMode", settings.quickShotMode)
        put("captureOrientation", CameraPreferences.orientation(settings.captureOrientation))
        put("compositionEnabled", settings.compositionEnabled)
        put("compositionRealtime", settings.compositionRealtime)
        put("compositionModel", D1ModelCatalog.find(settings.compositionModel)?.id ?: D1ModelCatalog.DEFAULT_MODEL_ID)
        put("lutName", settings.lutName ?: JSONObject.NULL)
        put("looks", JSONObject().apply {
            settings.looks.forEach { (preset, look) ->
                put(preset, JSONObject().apply {
                    put("intensity", look.intensity.toDouble())
                    put("grain", look.grain.toDouble())
                    put("vignette", look.vignette.toDouble())
                    put("saturation", look.saturation.toDouble())
                    put("hue", look.hue.toDouble())
                    put("contrast", look.contrast.toDouble())
                    put("brightness", look.brightness.toDouble())
                    put("highlights", look.highlights.toDouble())
                    put("shadows", look.shadows.toDouble())
                    put("sharpness", look.sharpness.toDouble())
                    put("clarity", look.clarity.toDouble())
                })
            }
        })
        put("hdfEnabled", settings.hdfEnabled)
        put("hdfStrength", settings.hdfStrength.toDouble())
        put("hdrEnabled", settings.hdrEnabled)
        put("hdrStrength", settings.hdrStrength.toDouble())
        put("whiteBalance", settings.whiteBalance)
        put("whiteBalanceAmber", settings.whiteBalanceAmber)
        put("whiteBalanceGreen", settings.whiteBalanceGreen)
        put("lookAdviceEnabled", settings.lookAdviceEnabled)
        put("exposureAdviceEnabled", settings.exposureAdviceEnabled)
        put("alignmentHapticEnabled", settings.alignmentHapticEnabled)
        put("poseAssistantEnabled", settings.poseAssistantEnabled)
        put("lookDismissedAt", JSONObject().apply {
            settings.lookDismissedAt.forEach { (lookKey, at) -> put(lookKey, at) }
        })
        put("lookAdviceDismissStreak", settings.lookAdviceDismissStreak)
    }.toString()

    fun decode(value: String?): AppSettings {
        if (value == null) return AppSettings()
        return runCatching {
            val json = JSONObject(value)
            // First parse the pre-migration creative choice so the owning body can be derived.
            val legacyPreset = json.optString("preset", "ORIGINAL")
                .takeIf { name -> ColorPreset.entries.any { it.name == name } } ?: "ORIGINAL"
            val legacyMascot = if (!json.has("mascotCamera") || json.isNull("mascotCamera")) null
                else json.optString("mascotCamera")
            val lutName = if (json.isNull("lutName")) null
                else json.optString("lutName").takeIf(String::isNotBlank)?.take(120)
            val rawFilmId = if (json.isNull("filmId")) null
                else json.optString("filmId").takeIf { FilmRegistry.find(it) != null }
            // v5 stores the body explicitly; older docs recover it from a pinned mascot or
            // from the look family they used (general looks land on the general body).
            val body = when {
                json.has("cameraBody") && !json.isNull("cameraBody") -> {
                    val rawBody = json.optString("cameraBody")
                    if (rawBody == BodyCatalog.GENERAL_ID) null
                    else {
                        // An unknown explicit id (renamed/removed body): keep the creative
                        // intent via the body that owns the saved look, never a blank body.
                        BodyCatalog.resolve(rawBody) ?: BodyCatalog.bodyForPresetName(legacyPreset)
                    }
                }
                legacyMascot != null && legacyMascot != CameraPreferences.HIDDEN ->
                    // A deliberately pinned body always wins, even if that now unloads a roll
                    // (a pre-T121 GR pin with a film loaded loses the roll, keeps the GR).
                    BodyCatalog.resolve(legacyMascot) ?: BodyCatalog.bodyForPresetName(legacyPreset)
                else -> {
                    val owner = BodyCatalog.bodyForPresetName(legacyPreset)
                    // Pre-T121 docs chose looks and rolls independently. If a roll is loaded
                    // and the look's owning body has no film section (GR), the roll survives
                    // by migrating to the brand-neutral general body.
                    if (rawFilmId != null && owner != null && !BodyCatalog.supportsFilmRolls(owner)) null
                    else owner
                }
            }
            val nameplateHidden = json.optBoolean("nameplateHidden", legacyMascot == CameraPreferences.HIDDEN)
            val rawFocal = json.optInt("focalLength", 28)
                .takeIf { it in listOf(0, 24, 28, 35, 40, 50, 75) } ?: 28
            // Force the upgraded look/focal back onto what the resolved body actually offers:
            // a GR can no longer keep a Fuji simulation, a film roll unloads on GR, etc.
            val coercion = coerceLookForBody(
                body, legacyPreset, rawFilmId, rawFocal, lutLoaded = !lutName.isNullOrBlank())
            val savedLooks = json.optJSONObject("looks")
            val looks = if (savedLooks != null) {
                val presetLooks = ColorPreset.entries.mapNotNull { choice ->
                    savedLooks.optJSONObject(choice.name)?.let { choice.name to decodeLook(it) }
                }
                val filmLooks = FilmRegistry.entries.mapNotNull { film ->
                    val key = "FILM:${film.id}"
                    savedLooks.optJSONObject(key)?.let {
                        key to decodeLook(it, LookSettings.fromRecipe(film.defaults))
                    }
                }
                (presetLooks + filmLooks).toMap()
            } else if (json.optInt("version", 1) == 1) {
                mapOf(legacyPreset to decodeLook(json))
            } else emptyMap()
            AppSettings(
                focalLength = coercion.focalLength,
                exposure = json.optInt("exposure", 0),
                focus = json.optString("focus", "AUTO").takeIf { it == "MANUAL" } ?: "AUTO",
                focusDiopters = json.amount("focusDiopters", 0f, 0f, 100f),
                iso = if (json.isNull("iso")) null else json.optInt("iso").takeIf { it > 0 },
                exposureTimeNs = if (json.isNull("exposureTimeNs")) null else json.optLong("exposureTimeNs").takeIf { it > 0L },
                grid = json.optBoolean("grid", true),
                level = json.optBoolean("level", true),
                viewfinderOsd = ViewfinderOsd.mode(json.optString("viewfinderOsd").takeIf { it.isNotEmpty() }),
                preset = coercion.preset,
                filmId = coercion.filmId,
                cameraBody = CameraPreferences.body(BodyCatalog.id(body)),
                nameplateHidden = nameplateHidden,
                quickShotMode = json.optBoolean("quickShotMode", true),
                captureOrientation = CameraPreferences.orientation(json.optString("captureOrientation")),
                compositionEnabled = json.optBoolean("compositionEnabled", false),
                compositionRealtime = json.optBoolean("compositionRealtime", false),
                compositionModel = D1ModelCatalog.find(json.optString("compositionModel"))?.id ?: D1ModelCatalog.DEFAULT_MODEL_ID,
                looks = looks,
                lutName = lutName,
                hdfEnabled = json.optBoolean("hdfEnabled", false),
                hdfStrength = json.amount("hdfStrength", .4f),
                hdrEnabled = json.optBoolean("hdrEnabled", false),
                hdrStrength = json.amount("hdrStrength", .6f),
                whiteBalance = json.optString("whiteBalance", "AUTO")
                    .takeIf { name -> WhiteBalanceMode.entries.any { it.name == name } } ?: "AUTO",
                whiteBalanceAmber = json.optInt("whiteBalanceAmber", 0).coerceIn(-9, 9),
                whiteBalanceGreen = json.optInt("whiteBalanceGreen", 0).coerceIn(-9, 9),
                lookAdviceEnabled = json.optBoolean("lookAdviceEnabled", true),
                exposureAdviceEnabled = json.optBoolean("exposureAdviceEnabled", true),
                alignmentHapticEnabled = json.optBoolean("alignmentHapticEnabled", false),
                poseAssistantEnabled = json.optBoolean("poseAssistantEnabled", true),
                lookDismissedAt = json.optJSONObject("lookDismissedAt")?.let { dismissed ->
                    dismissed.keys().asSequence()
                        .mapNotNull { lookKey ->
                            val at = dismissed.optLong(lookKey, 0L)
                            lookKey.takeIf { it.length <= 64 }?.let { key -> if (at > 0L) key to at else null }
                        }
                        .toMap()
                } ?: emptyMap(),
                lookAdviceDismissStreak = json.optInt("lookAdviceDismissStreak", 0).coerceIn(0, 3),
            )
        }.getOrDefault(AppSettings())
    }

    private fun decodeLook(json: JSONObject, defaults: LookSettings = LookSettings()) = LookSettings(
        intensity = json.amount("intensity", defaults.intensity),
        grain = json.amount("grain", defaults.grain), vignette = json.amount("vignette", defaults.vignette),
        saturation = json.amount("saturation", defaults.saturation, -1f), hue = json.amount("hue", defaults.hue, -1f),
        contrast = json.amount("contrast", defaults.contrast, -1f), brightness = json.amount("brightness", defaults.brightness, -1f),
        highlights = json.amount("highlights", defaults.highlights, -1f), shadows = json.amount("shadows", defaults.shadows, -1f),
        sharpness = json.amount("sharpness", defaults.sharpness, -1f), clarity = json.amount("clarity", defaults.clarity, -1f),
    )

    private fun JSONObject.amount(name: String, default: Float, minimum: Float = 0f, maximum: Float = 1f): Float =
        optDouble(name, default.toDouble()).toFloat().takeIf { it.isFinite() }?.coerceIn(minimum, maximum) ?: default
}
