package dev.streetsnap.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.streetsnap.camera.FocalLength
import dev.streetsnap.color.ColorPreset
import dev.streetsnap.color.FilmRegistry

/**
 * The functional camera body. T121: bodies used to be nameplate mascots only; a body now
 * owns the focal lengths it may show, the colour looks its real hardware offers, and
 * whether film rolls exist for it at all. Focal lengths are 35mm-equivalent; the camera
 * module turns them into digital crops of whatever phone lens is active.
 *
 * Real-hardware notes (crop-factor math, verified against maker specs):
 *  - RICOH_GR: APS-C (1.52×). GR III lens is 18.3mm = 28mm equiv; the GR IIIx is 26.1mm =
 *    40mm equiv and the GR III offers the same 40mm frame as an in-camera crop. The app
 *    exposes the two as 28 / 40. GR bodies have NO film-simulation menu — only the
 *    Positive / Negative / B&W image controls.
 *  - FUJIFILM_X100VI: APS-C, fixed 23mm = 35mm equiv; digital teleconverter ×1.4 = 50mm.
 *    Colour comes from film simulations, which are the Fuji-only presets.
 *  - SIGMA_FP: full-frame L-mount interchangeable — the focal list is intentionally free.
 *  - PANASONIC_LX100: M4/3 zoom, 10.9–34mm = 24–75mm equiv.
 *  - NIKON_F3: 35mm film SLR, interchangeable lens — free focals, colour comes from the
 *    loaded roll; the 50mm normal lens is the default.
 *  - ROLLEI_35: the 1966 pocket classic (easter egg): one fixed frame only — 35mm per
 *    product rule, no other focal choice; colour comes from the loaded roll.
 */
internal enum class CameraModel(
    val brand: String,
    val model: String,
    val spec: String,
    val filmCamera: Boolean,
    val interchangeableLens: Boolean,
    val focalMm: List<Int>,
    val defaultFocalMm: Int,
    val presets: List<ColorPreset>,
    val supportsFilmRolls: Boolean,
    val easterEgg: Boolean = false,
) {
    RICOH_GR("理光", "GR", "APS-C 定焦",
        filmCamera = false, interchangeableLens = false,
        focalMm = listOf(28, 40), defaultFocalMm = 28,
        presets = listOf(ColorPreset.POSITIVE, ColorPreset.NEGATIVE, ColorPreset.MONO),
        supportsFilmRolls = false),
    FUJIFILM_X100VI("富士", "X100VI", "APS-C 定焦",
        filmCamera = false, interchangeableLens = false,
        focalMm = listOf(35, 50), defaultFocalMm = 35,
        presets = listOf(ColorPreset.CLASSIC_CHROME, ColorPreset.CLASSIC_NEGATIVE,
            ColorPreset.NOSTALGIC_NEGATIVE, ColorPreset.ACROS),
        supportsFilmRolls = true),
    SIGMA_FP("适马", "fp", "全画幅 可换镜头",
        filmCamera = false, interchangeableLens = true,
        focalMm = listOf(28, 35, 50), defaultFocalMm = 35,
        presets = listOf(ColorPreset.POWDER_BLUE),
        supportsFilmRolls = true),
    PANASONIC_LX100("松下", "LX100 II", "M4/3 变焦 24–75",
        filmCamera = false, interchangeableLens = false,
        focalMm = listOf(24, 28, 35, 50, 75), defaultFocalMm = 24,
        presets = listOf(ColorPreset.LUMIX_NATURAL, ColorPreset.LUMIX_MONO),
        supportsFilmRolls = true),
    NIKON_F3("尼康", "F3", "35mm 胶片 可换镜头",
        filmCamera = true, interchangeableLens = true,
        focalMm = listOf(28, 35, 50), defaultFocalMm = 50,
        presets = listOf(ColorPreset.ORIGINAL),
        supportsFilmRolls = true),
    ROLLEI_35("禄来", "35", "35mm 胶片 袖珍",
        filmCamera = true, interchangeableLens = false,
        focalMm = listOf(35), defaultFocalMm = 35,
        presets = listOf(ColorPreset.ORIGINAL),
        supportsFilmRolls = true, easterEgg = true);

    val singleFocal: Boolean get() = focalMm.size == 1

    companion object {
        fun fromId(id: String?): CameraModel? = entries.firstOrNull { it.name == id }
    }
}

/**
 * Body catalog plus the synthetic "general" body (id [GENERAL_ID]): brand-neutral原色/晴街
 * looks, imported LUTs and the classic 28/35/50 trio. A null [CameraModel] means general.
 */
internal object BodyCatalog {
    const val GENERAL_ID = "GENERAL"
    val generalPresets = listOf(ColorPreset.ORIGINAL, ColorPreset.SUNNY, ColorPreset.CUSTOM)
    private val generalFocalMm = listOf(28, 35, 50)

    fun resolve(id: String?): CameraModel? = CameraModel.fromId(id)

    fun id(body: CameraModel?): String = body?.name ?: GENERAL_ID

    fun presets(body: CameraModel?): List<ColorPreset> = body?.presets ?: generalPresets

    fun supportsFilmRolls(body: CameraModel?): Boolean = body?.supportsFilmRolls ?: true

    fun defaultPreset(body: CameraModel?): ColorPreset = body?.presets?.firstOrNull() ?: ColorPreset.ORIGINAL

    fun focalLengths(body: CameraModel?): List<FocalLength> =
        (body?.focalMm ?: generalFocalMm).mapNotNull(FocalLength::fromMm)

    fun defaultFocalLength(body: CameraModel?): FocalLength =
        FocalLength.fromMm(body?.defaultFocalMm ?: 28) ?: FocalLength.MM28

    /**
     * Reverse mapping used when upgrading pre-T121 settings: which body owns this look?
     * The general family (原色/晴街/CUSTOM) wins first — ORIGINAL also appears on the film
     * bodies as their "lens native colour" choice, but pre-T121 ORIGINAL was the neutral
     * default and must not migrate onto a Nikon.
     */
    fun bodyForPresetName(presetName: String): CameraModel? {
        if (generalPresets.any { it.name == presetName }) return null
        return CameraModel.entries.firstOrNull { body -> body.presets.any { it.name == presetName } }
    }

    fun allowsPreset(body: CameraModel?, preset: ColorPreset): Boolean =
        presets(body).any { it == preset }
}

/** Result of forcing a look/focal choice back onto a body that does not offer it. */
internal data class BodyCoercion(
    val preset: String,
    val filmId: String?,
    val focalLength: Int,
)

/**
 * The single brand-consistency rule: after a body change (or an old-settings migration),
 * the active preset must be one the body actually offers, a film roll can stay only on a
 * body that has a film section, and the focal must be one of the body's focal lengths.
 * [lutLoaded] guards CUSTOM, which is selectable in the general body only with an import.
 */
internal fun coerceLookForBody(
    body: CameraModel?,
    presetName: String,
    filmId: String?,
    focalLength: Int,
    lutLoaded: Boolean,
): BodyCoercion {
    val allowed = BodyCatalog.presets(body)
    val preset = runCatching { ColorPreset.valueOf(presetName) }.getOrNull()
        ?.takeIf { it != ColorPreset.CUSTOM || lutLoaded }
        ?.takeIf { it in allowed }
        ?: BodyCatalog.defaultPreset(body)
    val keptFilm = filmId?.takeIf { FilmRegistry.find(it) != null && BodyCatalog.supportsFilmRolls(body) }
    val keptFocal = focalLength.takeIf { it in (body?.focalMm ?: listOf(28, 35, 50)) }
        ?: (body?.defaultFocalMm ?: 28)
    return BodyCoercion(preset.name, keptFilm, keptFocal)
}

/**
 * Pick a body strip first, then that body's own looks. T121: the strip USED to be a
 * cross-brand browser that let a GR select Fuji simulations and vice versa — it now
 * switches the active body, which in turn switches the legal look list, focal set and
 * the film section. Browsing is selecting, like changing cameras.
 */
@Composable
internal fun CameraLookPicker(
    settings: AppSettings,
    onBody: (CameraModel?) -> Unit,
    onPreset: (ColorPreset) -> Unit,
) {
    val body = BodyCatalog.resolve(settings.cameraBody)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val tiles = listOf<CameraModel?>(null) + CameraModel.entries
        tiles.forEach { tileBody ->
            val chosen = BodyCatalog.id(tileBody) == BodyCatalog.id(body)
            Column(Modifier.width(85.dp).clip(RoundedCornerShape(10.dp))
                .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else Color.Transparent)
                .border(1.dp, if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = .7f)
                    else Color.Transparent, RoundedCornerShape(10.dp))
                .clickable(role = Role.Button) { onBody(tileBody) }
                .semantics {
                    selected = chosen
                    contentDescription = "相机机型：${tileBody?.let { "${it.brand} ${it.model}" } ?: "通用"}"
                }
                .padding(horizontal = 4.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (tileBody != null) CameraArtwork(tileBody, Modifier.size(76.dp, 48.dp))
                else NeutralLookArtwork(Modifier.size(76.dp, 48.dp))
                Text(tileBody?.brand ?: "通用", fontSize = 11.sp,
                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(tileBody?.model ?: "LUT", fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically) {
        BodyCatalog.presets(body).filter { it != ColorPreset.CUSTOM || settings.lutName != null }.forEach { preset ->
            val chosen = settings.filmId == null && settings.preset == preset.name
            TextButton(onClick = { onPreset(preset) }, modifier = Modifier.heightIn(min = 48.dp)
                .semantics { selected = chosen; contentDescription = "影调：${preset.displayName}" }) {
                Text(preset.displayName, fontSize = 12.sp,
                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun NeutralLookArtwork(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val radius = size.minDimension * .29f
        drawCircle(color.copy(alpha = .8f), radius, center, style = Stroke(1.5.dp.toPx()))
        drawCircle(color.copy(alpha = .4f), radius * .66f, center, style = Stroke(1.dp.toPx()))
        drawCircle(color.copy(alpha = .5f), radius * .19f, center)
    }
}

/**
 * Functional body picker (the old decorative "取景器相机" mascot dialog). Choosing a tile
 * switches body immediately; nameplate visibility, capture orientation and diagnostics
 * stay here.
 */
@Composable
internal fun BodyPicker(
    selectedBodyId: String,
    nameplateHidden: Boolean,
    orientation: String,
    onSelectBody: (String) -> Unit,
    onNameplateHidden: (Boolean) -> Unit,
    onOrientation: (String) -> Unit,
    onDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("相机机型") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val choices: List<Pair<String?, @Composable (Modifier) -> Unit>> = buildList {
                    add(BodyCatalog.GENERAL_ID to { modifier -> NeutralLookArtwork(modifier) })
                    CameraModel.entries.forEach { body ->
                        add(body.name to { modifier -> CameraArtwork(body, modifier) })
                    }
                }
                val labels: Map<String?, String> = buildMap {
                    put(BodyCatalog.GENERAL_ID, "通用 · LUT")
                    CameraModel.entries.forEach { put(it.name, "${it.brand} · ${it.model}") }
                }
                choices.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (id, artwork) ->
                            val body = BodyCatalog.resolve(id)
                            val chosen = selectedBodyId == id
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .border(1.dp, if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) { id?.let { onSelectBody(it) }; onDismiss() }
                                .semantics { selected = chosen; contentDescription = "相机机型：${labels[id]}" }
                                .padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                artwork(Modifier.fillMaxWidth().height(62.dp))
                                Text(labels[id]!!, fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        body?.easterEgg == true -> "彩蛋 · ${body.spec}"
                                        body != null -> body.spec
                                        else -> "原色 / 晴街 / 自定义 LUT"
                                    },
                                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Text("机型决定可选焦段、影调与胶片：理光 GR 只有正片/负片/黑白且没有胶片页；禄来 35 只有 35mm 一档，色彩由胶卷决定。",
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("取景方向", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    listOf("AUTO" to "自动", "LANDSCAPE" to "横持", "PORTRAIT" to "竖持").forEach { (id, label) ->
                        TextButton(onClick = { onOrientation(id) }, modifier = Modifier.heightIn(min = 48.dp)
                            .semantics { selected = orientation == id; contentDescription = "取景方向：$label" }) {
                            Text(label, color = if (orientation == id) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = { onDiagnostics(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("设备诊断", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = { onNameplateHidden(!nameplateHidden) },
                modifier = Modifier.semantics { contentDescription = "显示或隐藏顶部机型铭牌" }) {
                Text(if (nameplateHidden) "显示铭牌" else "✓ 显示铭牌",
                    color = if (!nameplateHidden) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
}
