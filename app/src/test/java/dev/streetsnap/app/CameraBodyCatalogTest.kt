package dev.streetsnap.app

import dev.streetsnap.camera.FocalLength
import dev.streetsnap.color.ColorPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T121 hard facts the user asked to match the real cameras exactly: bodies own their focal
 * sets and their colour looks; the GR has no film section; the Rollei 35 easter egg has a
 * single 35mm frame.
 */
class CameraBodyCatalogTest {
    @Test fun ricohGrIs28And40WithThreeImageControlsAndNoFilmSection() {
        val gr = CameraModel.RICOH_GR
        assertEquals(listOf(28, 40), gr.focalMm)
        assertEquals(28, gr.defaultFocalMm)
        assertFalse(gr.filmCamera)
        assertFalse(gr.supportsFilmRolls)
        assertEquals(
            listOf(ColorPreset.POSITIVE, ColorPreset.NEGATIVE, ColorPreset.MONO),
            gr.presets,
        )
        assertEquals(
            listOf(FocalLength.MM28, FocalLength.MM40),
            BodyCatalog.focalLengths(gr),
        )
    }

    @Test fun fujiX100viIs35And50AndOnlySimsAreItsLooks() {
        val fuji = CameraModel.FUJIFILM_X100VI
        assertEquals(listOf(35, 50), fuji.focalMm)
        assertEquals(35, fuji.defaultFocalMm)
        assertTrue(fuji.supportsFilmRolls) // film-stock looks coexist with the simulations
        assertTrue(BodyCatalog.allowsPreset(fuji, ColorPreset.CLASSIC_CHROME))
        assertTrue(BodyCatalog.allowsPreset(fuji, ColorPreset.CLASSIC_NEGATIVE))
        assertTrue(BodyCatalog.allowsPreset(fuji, ColorPreset.NOSTALGIC_NEGATIVE))
        assertTrue(BodyCatalog.allowsPreset(fuji, ColorPreset.ACROS))
        assertFalse(BodyCatalog.allowsPreset(fuji, ColorPreset.POSITIVE)) // GR control
        assertFalse(BodyCatalog.allowsPreset(fuji, ColorPreset.LUMIX_NATURAL))
    }

    @Test fun rollei35IsTheSingle35mmFilmEasterEgg() {
        val rollei = CameraModel.ROLLEI_35
        assertTrue(rollei.easterEgg)
        assertTrue(rollei.filmCamera)
        assertEquals(listOf(35), rollei.focalMm)
        assertEquals(35, rollei.defaultFocalMm)
        assertTrue(rollei.singleFocal)
        assertEquals(listOf(FocalLength.MM35), BodyCatalog.focalLengths(rollei))
        assertEquals(FocalLength.MM35, BodyCatalog.defaultFocalLength(rollei))
    }

    @Test fun zoomAndInterchangeableBodiesMatchSpecs() {
        // LX100 II: M4/3 10.9–34mm = 24–75mm equiv, five digital steps.
        assertEquals(listOf(24, 28, 35, 50, 75), CameraModel.PANASONIC_LX100.focalMm)
        assertEquals(24, CameraModel.PANASONIC_LX100.defaultFocalMm)
        // Sigma fp: full-frame L-mount, interchangeable.
        assertTrue(CameraModel.SIGMA_FP.interchangeableLens)
        assertEquals(listOf(28, 35, 50), CameraModel.SIGMA_FP.focalMm)
        assertEquals(35, CameraModel.SIGMA_FP.defaultFocalMm)
        // Nikon F3: 35mm film SLR, interchangeable, 50mm normal lens default.
        assertTrue(CameraModel.NIKON_F3.filmCamera)
        assertTrue(CameraModel.NIKON_F3.interchangeableLens)
        assertEquals(50, CameraModel.NIKON_F3.defaultFocalMm)
    }

    @Test fun generalBodyOwnsNeutralLooksAndClassicTrio() {
        assertNull(BodyCatalog.resolve(BodyCatalog.GENERAL_ID))
        assertEquals(
            listOf(ColorPreset.ORIGINAL, ColorPreset.SUNNY, ColorPreset.CUSTOM),
            BodyCatalog.presets(null),
        )
        assertEquals(
            listOf(FocalLength.MM28, FocalLength.MM35, FocalLength.MM50),
            BodyCatalog.focalLengths(null),
        )
        assertTrue(BodyCatalog.supportsFilmRolls(null))
        // CUSTOM needs an imported LUT; without one it coerces to ORIGINAL.
        assertEquals("ORIGINAL",
            coerceLookForBody(null, "CUSTOM", null, 35, lutLoaded = false).preset)
        assertEquals("CUSTOM",
            coerceLookForBody(null, "CUSTOM", null, 35, lutLoaded = true).preset)
    }

    @Test fun presetOwnershipAndCoercionFollowTheBrandRule() {
        // GR control on a Fuji body falls back to that body's default look.
        val ontoFuji = coerceLookForBody(
            CameraModel.FUJIFILM_X100VI, "POSITIVE", "NIGHT_01", 28, lutLoaded = false)
        assertEquals("CLASSIC_CHROME", ontoFuji.preset)
        assertEquals("NIGHT_01", ontoFuji.filmId) // Fuji has a film section
        assertEquals(35, ontoFuji.focalLength) // 28mm is not a Fuji frame
        // Any cross-brand preset + roll onto the GR resets to GR's own defaults.
        val ontoGr = coerceLookForBody(
            CameraModel.RICOH_GR, "ACROS", "NIGHT_01", 50, lutLoaded = true)
        assertEquals("POSITIVE", ontoGr.preset)
        assertNull(ontoGr.filmId)
        assertEquals(28, ontoGr.focalLength)
        // Rollei keeps its one frame and loaded roll.
        val ontoRollei = coerceLookForBody(
            CameraModel.ROLLEI_35, "POSITIVE", "NIGHT_01", 50, lutLoaded = false)
        assertEquals("ORIGINAL", ontoRollei.preset)
        assertEquals("NIGHT_01", ontoRollei.filmId)
        assertEquals(35, ontoRollei.focalLength)
        // Unknown roll ids never survive even on a film-capable body.
        assertNull(coerceLookForBody(CameraModel.NIKON_F3, "ORIGINAL", "REMOVED", 50, false).filmId)
        // Reverse lookup: each branded preset names exactly one owning body.
        assertEquals(CameraModel.RICOH_GR, BodyCatalog.bodyForPresetName("NEGATIVE"))
        assertEquals(CameraModel.FUJIFILM_X100VI, BodyCatalog.bodyForPresetName("ACROS"))
        assertEquals(CameraModel.SIGMA_FP, BodyCatalog.bodyForPresetName("POWDER_BLUE"))
        assertEquals(CameraModel.PANASONIC_LX100, BodyCatalog.bodyForPresetName("LUMIX_MONO"))
        assertNull(BodyCatalog.bodyForPresetName("ORIGINAL"))
    }

    @Test fun bodyIdsRoundTripThroughTheCatalog() {
        for (body in CameraModel.entries) {
            assertEquals(body, BodyCatalog.resolve(BodyCatalog.id(body)))
        }
        assertEquals(CameraModel.RICOH_GR, CameraModel.fromId("RICOH_GR"))
        assertNull(CameraModel.fromId("HIDDEN"))
        assertNull(CameraModel.fromId("nope"))
    }
}
