package dev.streetsnap.app

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas

/**
 * Content bounding box of each study inside the shared 180x112 canvas (including the soft
 * lens shadow). The art is fitted to THIS box rather than the full canvas, so a body whose
 * study occupies a wide slice no longer renders as a thin strip with large empty margins —
 * the earlier source of the "squashed camera" look even though the canvas scale was uniform.
 */
private val artworkBoxes = mapOf(
    CameraModel.RICOH_GR to Rect(20f, 16f, 160f, 96f),
    CameraModel.FUJIFILM_X100VI to Rect(16f, 6f, 164f, 103f),
    CameraModel.SIGMA_FP to Rect(30f, 14f, 150f, 100f),
    CameraModel.PANASONIC_LX100 to Rect(16f, 18f, 156f, 104f),
    CameraModel.NIKON_F3 to Rect(10f, 13f, 168f, 103f),
    CameraModel.ROLLEI_35 to Rect(28f, 26f, 152f, 94f),
)

/** Original vector studies of recognisable camera bodies; no downloaded product artwork. */
@Composable
internal fun CameraArtwork(camera: CameraModel, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val box = artworkBoxes.getValue(camera)
        val factor = minOf(size.width / box.width, size.height / box.height)
        withTransform({
            translate((size.width - box.width * factor) / 2f - box.left * factor,
                (size.height - box.height * factor) / 2f - box.top * factor)
            scale(factor, factor, Offset.Zero)
        }) {
            when (camera) {
                CameraModel.RICOH_GR -> ricohGr()
                CameraModel.FUJIFILM_X100VI -> fujiX100()
                CameraModel.SIGMA_FP -> sigmaFp()
                CameraModel.PANASONIC_LX100 -> lumixLx100()
                CameraModel.NIKON_F3 -> nikonF3()
                CameraModel.ROLLEI_35 -> rollei35()
            }
        }
    }
}

private val bodyBlack = Color(0xFF272B2C)
private val bodyEdge = Color(0xFF505659)
private val bodySilver = Color(0xFFC6CBC9)
private val inset = Color(0xFF111516)

private fun DrawScope.panel(x: Float, y: Float, w: Float, h: Float, color: Color, radius: Float = 3f) {
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(radius))
}

private fun DrawScope.body(x: Float, y: Float, w: Float, h: Float, radius: Float = 7f) {
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF414748), bodyBlack, Color(0xFF1A1E1F)), y, y + h),
        Offset(x, y), Size(w, h), CornerRadius(radius))
    drawRoundRect(bodyEdge, Offset(x, y), Size(w, h), CornerRadius(radius), style = Stroke(1f))
}

private fun DrawScope.lettering(label: String, x: Float, y: Float, size: Float, color: Color = bodySilver) {
    drawContext.canvas.nativeCanvas.drawText(label, x, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = size
        this.color = android.graphics.Color.argb((color.alpha * 255).toInt(),
            (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
    })
}

private fun DrawScope.lens(x: Float, y: Float, radius: Float, silver: Boolean = false) {
    val c = Offset(x, y)
    drawCircle(Color.Black.copy(alpha = .4f), radius + 3f, c + Offset(1f, 3f))
    drawCircle(if (silver) bodySilver else bodyEdge, radius, c)
    drawCircle(if (silver) Color(0xFF747D7D) else Color(0xFF171C1E), radius - 3f, c)
    drawCircle(if (silver) Color(0xFFBFC6C4) else Color(0xFF42494A), radius - 6f, c,
        style = Stroke(1.3f))
    drawCircle(inset, radius * .65f, c)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF31535A), Color(0xFF101D29), Color.Black),
        c + Offset(-3f, -3f), radius * .65f), radius * .53f, c)
    drawCircle(Color(0xFF79B5BD).copy(alpha = .6f), radius * .14f, c + Offset(-radius * .19f, -radius * .2f))
    drawCircle(Color(0xFF694C89).copy(alpha = .45f), radius * .08f, c + Offset(radius * .2f, radius * .22f))
    drawCircle(Color(0xFF869494).copy(alpha = .6f), radius * .6f, c, style = Stroke(.75f))
}

/** Top-deck control dial: knurled cylinder poking above the deck line. */
private fun DrawScope.dial(x: Float, topY: Float, r: Float, silver: Boolean = true) {
    val metal = if (silver) Color(0xFFC6CBC9) else Color(0xFF5B6263)
    val dark = if (silver) Color(0xFF7E8786) else Color(0xFF303536)
    drawRoundRect(dark, Offset(x - r, topY + r * .5f), Size(r * 2, r * 1.1f), CornerRadius(1.5f))
    drawCircle(metal, r, Offset(x, topY + r))
    drawCircle(dark, r * .55f, Offset(x, topY + r))
    drawLine(metal, Offset(x, topY + r * .5f), Offset(x, topY + r * 1.5f), 1.2f)
}

/* Ricoh GR III: a compact flat-top rectangle whose real front face is about
 * 1.77:1. The old study drew the body as a 152x52 (~2.9:1) strip, which is the
 * actual source of the "stretched camera" complaint. Redrawn to 140x80 (1.75:1):
 * flat top with only a sliver of the mode dial visible, hot shoe viewer-right,
 * centered fixed lens (an off-center barrel is a documented lens fault), and a
 * grip ridge on the viewer-left (the holder's right hand). */
private fun DrawScope.ricohGr() {
    body(20f, 16f, 140f, 80f, 6f)
    // top-deck seam across the front, with a sliver of the mode dial at viewer-left
    panel(22f, 19f, 136f, 5f, Color(0xFF323839), 2f)
    drawCircle(Color(0xFF171C1D), 4.5f, Offset(40f, 21.5f))
    // hot shoe near center, toward the viewer-right
    panel(112f, 22f, 20f, 3.5f, inset, 1f)
    // small anti-slip grip on the viewer-left
    panel(25f, 27f, 19f, 63f, inset, 4f)
    for (y in 33..85 step 4) drawLine(Color(0xFF3A4141), Offset(29f, y.toFloat()), Offset(40f, y.toFloat()), .8f)
    // centered fixed lens, all black; diameter ~63% of body height like the real thing
    lens(90f, 57f, 25f)
    // AF assist lamp, small dot viewer-right of the lens
    panel(131f, 50f, 5f, 3.5f, inset, 1f)
    lettering("GR", 131f, 86f, 10f)
}

/* Fujifilm X100VI: flat silver top deck (no prism), two control dials,
 * the big square hybrid viewfinder window high on the viewer-right,
 * silver-rimmed lens. Two-tone silver/black is the main thumbnail cue. */
private fun DrawScope.fujiX100() {
    // dials sit on the deck: draw first, body overlaps their lower halves
    dial(46f, 17f, 8f)   // shutter speed dial
    dial(132f, 15f, 9f)  // exposure compensation dial
    body(16f, 34f, 148f, 62f, 6f)
    // silver top band across the whole front
    panel(17f, 35f, 146f, 17f, bodySilver, 5f)
    panel(17f, 50f, 146f, 1.6f, Color(0xFF8C9796))
    // hybrid viewfinder: tall square window viewer-right
    panel(124f, 38f, 26f, 13f, bodyEdge, 2f)
    panel(127f, 40f, 20f, 9f, Color(0xFF1E333C), 1f)
    panel(130f, 41.5f, 6f, 3f, Color(0xFF3E6672), .6f)
    // small AF lamp viewer-left
    panel(24f, 40f, 12f, 8f, inset, 1f)
    lens(90f, 72f, 28f, silver = true) // centered lens with silver front ring
    lettering("X100VI", 26f, 90f, 7f)
}

/* Sigma fp: a hard-edged die-cast block — the only body with no hump, no
 * grip and no front window. A single combined dial/shutter sits over the
 * viewer-left shoulder, twin mic slots straddle the centered L-mount, and
 * the four case screws signal the modular, industrial body. */
private fun DrawScope.sigmaFp() {
    dial(52f, 22f, 8f, silver = false) // combined dial + shutter, holder's right shoulder
    body(30f, 30f, 120f, 68f, 1.5f)    // near right-angled block
    // twin microphone slots symmetric above the mount
    panel(78f, 39f, 9f, 3f, inset, 1f)
    panel(93f, 39f, 9f, 3f, inset, 1f)
    // case screws
    drawCircle(Color(0xFF8A9291), 1.7f, Offset(36f, 36f))
    drawCircle(Color(0xFF8A9291), 1.7f, Offset(144f, 36f))
    drawCircle(Color(0xFF8A9291), 1.7f, Offset(36f, 92f))
    drawCircle(Color(0xFF8A9291), 1.7f, Offset(144f, 92f))
    lens(90f, 68f, 29f)
    drawCircle(bodyEdge, 30.5f, Offset(90f, 68f), style = Stroke(2f)) // L-mount ring
    lettering("fp", 126f, 48f, 11f)
    lettering("SIGMA", 112f, 91f, 6f)
}

/* Panasonic LX100 II: asymmetric stepped top. From the viewer's side the
 * EVF housing raises the right half of the deck (the holder's left
 * shoulder), two sunken dials cluster over the viewer-left shoulder beside
 * the small grip, and the large protruding zoom lens dominates the face.
 * No red dot — that is the Leica D-LUX sibling, not the Lumix. */
private fun DrawScope.lumixLx100() {
    // two dials over the holder's right shoulder = viewer-left
    dial(42f, 26f, 7.5f, silver = false) // shutter speed dial
    dial(71f, 24f, 8f, silver = false)   // exposure compensation dial
    body(16f, 38f, 140f, 60f, 4f)
    // EVF housing raises the viewer-right half of the deck
    panel(88f, 27f, 68f, 12f, Color(0xFF262C2D), 3f)
    // small grip swell on the viewer-left (holder's right hand)
    panel(18f, 44f, 14f, 50f, Color(0xFF1C2021), 4f)
    for (y in 49..89 step 4) drawLine(Color(0xFF394040), Offset(21f, y.toFloat()), Offset(28f, y.toFloat()), .8f)
    // large protruding zoom lens, extra barrel rings read as extended
    lens(86f, 71f, 29f)
    drawCircle(Color(0xFF52595A), 30.2f, Offset(86f, 71f), style = Stroke(2.4f))
    drawCircle(Color(0xFF2C3233), 33f, Offset(86f, 71f), style = Stroke(2f))
    lettering("LUMIX", 114f, 54f, 7f)
    lettering("LX100 II", 116f, 92f, 6f)
}

/* Nikon F3 with the DE-2 eye-level prism: the low, angular central
 * pentaprism towers over every other body and is the whole silhouette.
 * Film advance lever flicks out over the viewer-left shoulder (coaxial
 * with the shutter), the shutter-speed dial rides the viewer-right
 * shoulder, and Giugiaro's red vertical line runs down the concave grip
 * on the viewer-left. Centered lens on the tallest, most squared body. */
private fun DrawScope.nikonF3() {
    // film advance lever flicking out over the viewer-left shoulder
    val lever = Path().apply {
        moveTo(30f, 44f); lineTo(10f, 27f); lineTo(14f, 23f); lineTo(35f, 40f); close()
    }
    drawPath(lever, Color(0xFF3B4142))
    drawPath(lever, bodyEdge, style = Stroke(1f))
    dial(146f, 33f, 7.5f, silver = false) // shutter-speed dial, viewer-right shoulder
    body(12f, 42f, 156f, 56f, 4f)
    // dominant low angular pentaprism
    val prism = Path().apply {
        moveTo(58f, 43f); lineTo(70f, 13f); lineTo(108f, 13f); lineTo(122f, 43f); close()
    }
    drawPath(prism, Color(0xFF2C3234))
    drawPath(prism, bodyEdge, style = Stroke(1f))
    lettering("Nikon", 75f, 31f, 8.5f)
    // concave grip on the viewer-left with the red vertical accent line
    panel(17f, 50f, 27f, 44f, inset, 4f)
    panel(21f, 56f, 2.6f, 32f, Color(0xFFC0271F), .8f)
    drawCircle(Color(0xFF171B1C), 3.4f, Offset(34f, 46f)) // shutter release, viewer-left
    lens(90f, 72f, 28f)
    lettering("F3", 132f, 62f, 9f)
}

/* Rollei 35 (easter egg): the tiny 1966 pocket camera — a near-square silver block
 * with black leatherette, its collapsible lens offset to the viewer-left and the
 * selenium meter window high on the viewer-right. No prism, no grip, no hot shoe. */
private fun DrawScope.rollei35() {
    // silver shell
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFD8DDDB), Color(0xFF9EA6A4), Color(0xFF7F8887))),
        Offset(30f, 30f), Size(120f, 62f), CornerRadius(5f))
    drawRoundRect(bodyEdge, Offset(30f, 30f), Size(120f, 62f), CornerRadius(5f), style = Stroke(1f))
    // black leatherette centre strip
    panel(58f, 38f, 46f, 46f, Color(0xFF171B1C), 3f)
    // selenium meter window, viewer-right
    panel(111f, 34f, 31f, 12f, Color(0xFF203038), 2f)
    panel(113f, 36.5f, 13f, 4.5f, Color(0xFF4E7483), 1f)
    // small viewfinder window beside the meter
    panel(106f, 50f, 12f, 8f, Color(0xFF101D29), 1.5f)
    // offset collapsible lens, viewer-left, silver rim
    lens(61f, 62f, 19f, silver = true)
    drawCircle(Color(0xFF52595A), 20.2f, Offset(61f, 62f), style = Stroke(1.6f))
    lettering("Rollei", 96f, 72f, 7.5f, color = Color(0xFF262B2C))
    lettering("35", 133f, 86f, 8.5f, color = Color(0xFF262B2C))
}
