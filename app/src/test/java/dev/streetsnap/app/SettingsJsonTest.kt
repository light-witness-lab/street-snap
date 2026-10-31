package dev.streetsnap.app

import dev.streetsnap.app.model.D1ModelCatalog
import dev.streetsnap.color.ColorPreset
import dev.streetsnap.color.FilmRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsJsonTest {
    @Test fun compositionUpgradeDefaultsToManualAndRealtimeSurvivesRestart() {
        val legacy = SettingsJson.decode("""{"version":4,"compositionEnabled":true,"preset":"MONO"}""")
        assertTrue(legacy.compositionEnabled)
        assertFalse(legacy.compositionRealtime)
        assertEquals("MONO", legacy.preset)
        val realtime = legacy.copy(compositionRealtime = true)
        assertEquals(realtime, SettingsJson.decode(SettingsJson.encode(realtime)))
    }

    @Test fun upgradeKeepsTheOldLookAndDoesNotApplyItsEditsToOtherLooks() {
        val old = """{"version":1,"preset":"SUNNY","intensity":0.6,"grain":0.3,"vignette":0.2,"focalLength":35,"iso":400,"exposureTimeNs":8000000}"""
        val loaded = SettingsJson.decode(old)
        assertEquals(LookSettings(intensity = .6f, grain = .3f, vignette = .2f), loaded.activeLook)
        assertEquals(LookSettings(), loaded.copy(preset = "POSITIVE").activeLook)
        assertEquals(35, loaded.focalLength)
        assertEquals(400, loaded.iso)
        assertEquals(8_000_000L, loaded.exposureTimeNs)
        assertEquals(loaded, SettingsJson.decode(SettingsJson.encode(loaded)))
    }

    @Test fun switchingLooksAndRestartingPreservesTheirIndependentEdits() {
        // T121: looks live on the body that owns them — POSITIVE on GR, POWDER_BLUE on fp.
        var settings = AppSettings(cameraBody = "RICOH_GR", preset = "POSITIVE")
            .updateLook { it.copy(saturation = .5f, sharpness = -.25f) }
        settings = settings.copy(cameraBody = "SIGMA_FP", preset = "POWDER_BLUE")
            .updateLook { it.copy(contrast = -.5f, hue = .25f) }
        val loaded = SettingsJson.decode(SettingsJson.encode(settings))
        assertEquals(-.5f, loaded.activeLook.contrast, 0f)
        assertEquals(.5f, loaded.copy(preset = "POSITIVE").activeLook.saturation, 0f)
        assertEquals(-.25f, loaded.copy(preset = "POSITIVE").activeLook.sharpness, 0f)
        assertEquals(LookSettings(), loaded.copy(preset = "NEGATIVE").activeLook)
    }

    @Test fun resetOnlyChangesTheSelectedLook() {
        val settings = AppSettings(preset = "NEGATIVE", whiteBalance = "CLOUDY", whiteBalanceAmber = 3, hdfEnabled = true)
            .updateLook { it.copy(shadows = .5f) }
            .copy(preset = "MONO").updateLook { it.copy(contrast = .75f) }
            .resetLook()
        assertEquals(LookSettings(), settings.activeLook)
        assertEquals(.5f, settings.copy(preset = "NEGATIVE").activeLook.shadows, 0f)
        assertEquals("CLOUDY", settings.whiteBalance)
        assertEquals(3, settings.whiteBalanceAmber)
        assertTrue(settings.hdfEnabled)
    }

    @Test fun allNewParametersAndCustomLutSurviveRoundTripAndReachRenderer() {
        val look = LookSettings(.75f, .2f, .4f, -.5f, .25f, .5f, -.25f, -.75f, .5f, .75f, -.25f)
        val settings = AppSettings(
            cameraBody = BodyCatalog.GENERAL_ID,
            preset = "CUSTOM", lutName = "My street.cube", looks = mapOf("CUSTOM" to look),
            hdfEnabled = true, hdfStrength = .6f, whiteBalance = "INCANDESCENT",
            whiteBalanceAmber = -4, whiteBalanceGreen = 7,
        )
        val loaded = SettingsJson.decode(SettingsJson.encode(settings))
        assertEquals(settings, loaded)
        val recipe = loaded.colorRecipe()
        assertEquals(ColorPreset.CUSTOM, recipe.preset)
        assertEquals(look.clarity, recipe.clarity, 0f)
        assertEquals(look.highlights, recipe.highlights, 0f)
        assertEquals(look.sharpness, recipe.sharpness, 0f)
        assertTrue(recipe.hdfEnabled)
        assertEquals(-4, recipe.whiteBalanceAmber)
        assertEquals(7, recipe.whiteBalanceGreen)
    }

    @Test fun outOfRangeAndNonFiniteStoredValuesCannotReachShader() {
        val json = """{"version":2,"preset":"POWDER_BLUE","hdfStrength":-3,"whiteBalance":"UNKNOWN","whiteBalanceAmber":99,"whiteBalanceGreen":-99,"looks":{"POWDER_BLUE":{"saturation":12,"hue":-12,"sharpness":"NaN","grain":"Infinity","highlights":0.25},"INVALID":{"contrast":1}}}"""
        val settings = SettingsJson.decode(json)
        assertEquals(1f, settings.activeLook.saturation, 0f)
        assertEquals(-1f, settings.activeLook.hue, 0f)
        assertEquals(0f, settings.activeLook.sharpness, 0f)
        assertEquals(0f, settings.activeLook.grain, 0f)
        assertEquals(0f, settings.hdfStrength, 0f)
        assertEquals("AUTO", settings.whiteBalance)
        assertEquals(9, settings.whiteBalanceAmber)
        assertEquals(-9, settings.whiteBalanceGreen)
        assertEquals(setOf("POWDER_BLUE"), settings.looks.keys)
        settings.colorRecipe() // Validation must succeed for repaired settings.
    }

    @Test fun hdrDefaultsOffSurvivesRoundTripAndRecoversFromLegacyOrBadJson() {
        assertFalse(AppSettings().hdrEnabled)
        assertEquals(.6f, AppSettings().hdrStrength, 0f)
        val enabled = AppSettings(hdrEnabled = true, hdrStrength = .85f)
        assertEquals(enabled, SettingsJson.decode(SettingsJson.encode(enabled)))
        // Legacy document without HDR fields: look stays off.
        val legacy = SettingsJson.decode("""{"version":2,"preset":"POSITIVE"}""")
        assertFalse(legacy.hdrEnabled)
        assertEquals(.6f, legacy.hdrStrength, 0f)
        // Out-of-range strength is clamped; malformed documents recover to defaults.
        val repaired = SettingsJson.decode("""{"hdrEnabled":true,"hdrStrength":5}""")
        assertTrue(repaired.hdrEnabled)
        assertEquals(1f, repaired.hdrStrength, 0f)
        assertEquals(AppSettings(), SettingsJson.decode("{broken"))
        assertTrue(enabled.colorRecipe().hdrEnabled)
    }

    @Test fun malformedDocumentsRecoverAndUnrelatedValidFieldsSurviveBadLookEntries() {
        assertEquals(AppSettings(), SettingsJson.decode("{broken"))
        assertEquals(AppSettings(), SettingsJson.decode(null))
        val settings = SettingsJson.decode("""{"version":2,"cameraBody":"GENERAL","preset":"ORIGINAL","focalLength":50,"looks":{"MONO":false,"POSITIVE":{"contrast":0.5}}}""")
        assertEquals(50, settings.focalLength)
        assertEquals(LookSettings(), settings.activeLook)
        assertEquals(.5f, settings.copy(preset = "POSITIVE").activeLook.contrast, 0f)
    }

    @Test fun selectingFilmUsesItsAuthoredRecipeWithoutOverwritingCameraControls() {
        for (film in FilmRegistry.entries) {
            val settings = AppSettings(
                cameraBody = BodyCatalog.GENERAL_ID,
                preset = "CUSTOM", filmId = film.id, lutName = "Street.cube",
                whiteBalance = "DAYLIGHT", whiteBalanceAmber = 2, whiteBalanceGreen = -1,
                hdfEnabled = true, hdfStrength = .55f,
            )
            assertEquals(
                film.defaults.copy(hdfEnabled = true, hdfStrength = .55f, whiteBalanceAmber = 2, whiteBalanceGreen = -1),
                settings.colorRecipe(),
            )
            assertEquals("CUSTOM", settings.preset)
            assertEquals("Street.cube", settings.lutName)
            assertEquals(settings, SettingsJson.decode(SettingsJson.encode(settings)))
        }
    }

    @Test fun twoFilmsSharingALutKeepTheirOwnEditsAndRestoreThePreviousPreset() {
        val day = FilmRegistry.find("DAY_01")!!
        val slowDay = FilmRegistry.find("NEG_01")!!
        assertEquals(day.basePreset, slowDay.basePreset)
        // SUNNY and the film rolls live on the brand-neutral general body (T121).
        val original = AppSettings(cameraBody = BodyCatalog.GENERAL_ID, preset = day.basePreset.name)
            .updateLook { it.copy(grain = .9f, contrast = -.8f) }
        val settings = original.copy(filmId = day.id)
            .updateLook { it.copy(grain = .6f, saturation = -.4f) }
            .copy(filmId = slowDay.id)
            .updateLook { it.copy(grain = .1f, saturation = .2f) }

        val loaded = SettingsJson.decode(SettingsJson.encode(settings))
        assertEquals(settings, loaded)
        assertEquals(.1f, loaded.activeLook.grain, 0f)
        assertEquals(.2f, loaded.activeLook.saturation, 0f)
        assertEquals(.6f, loaded.copy(filmId = day.id).activeLook.grain, 0f)
        assertEquals(-.4f, loaded.copy(filmId = day.id).activeLook.saturation, 0f)
        assertEquals(original.activeLook, loaded.copy(filmId = null).activeLook)
        assertEquals(original.colorRecipe(), loaded.copy(filmId = null).colorRecipe())
        assertEquals(setOf("SUNNY", "FILM:DAY_01", "FILM:NEG_01"), loaded.looks.keys)
    }

    @Test fun resettingFilmRestoresItsRecipeAndPreservesOtherLooksAndCameraPreferences() {
        val film = FilmRegistry.find("DAY_01")!!
        // T121: the fp body offers POWDER_BLUE and has a film section, so the whole
        // combination survives a v5 round trip without coercion.
        val settings = AppSettings(cameraBody = "SIGMA_FP", preset = "POWDER_BLUE",
                captureOrientation = "LANDSCAPE")
            .updateLook { it.copy(contrast = -.4f) }
            .copy(filmId = "PORTRAIT_01").updateLook { it.copy(saturation = .3f) }
            .copy(filmId = film.id).updateLook { it.copy(grain = 0f, sharpness = 1f) }
            .resetLook()
        assertEquals(film.defaults, settings.colorRecipe())
        assertEquals(-.4f, settings.copy(filmId = null).activeLook.contrast, 0f)
        assertEquals(.3f, settings.copy(filmId = "PORTRAIT_01").activeLook.saturation, 0f)
        assertEquals("SIGMA_FP", settings.cameraBody)
        assertEquals("LANDSCAPE", settings.captureOrientation)
        assertEquals(settings, SettingsJson.decode(SettingsJson.encode(settings)))
    }

    @Test fun unknownFilmFallsBackToSavedPresetAndIgnoresUnknownLookKeys() {
        val settings = SettingsJson.decode("""{"version":3,"preset":"MONO","filmId":"REMOVED","filmFrame":"UNKNOWN","dateStamp":"UNKNOWN","looks":{"MONO":{"contrast":0.5},"FILM:REMOVED":{"grain":0.9},"FILM:DAY_01":{"grain":0.4}}}""")
        assertNull(settings.filmId)
        assertEquals(ColorPreset.MONO, settings.colorRecipe().preset)
        assertEquals(.5f, settings.activeLook.contrast, 0f)
        assertEquals(setOf("MONO", "FILM:DAY_01"), settings.looks.keys)
    }

    @Test fun partialAndInvalidFilmEditsRecoverFromItsDefaults() {
        val defaults = LookSettings.fromRecipe(FilmRegistry.find("NIGHT_01")!!.defaults)
        val settings = SettingsJson.decode("""{"version":3,"preset":"ORIGINAL","filmId":"NIGHT_01","looks":{"FILM:NIGHT_01":{"grain":"NaN","sharpness":99,"saturation":-99,"brightness":0.1}}}""")
        assertEquals(defaults.copy(sharpness = 1f, saturation = -1f, brightness = .1f), settings.activeLook)
        settings.colorRecipe()
    }

    @Test fun upgradingVersionThreeDropsDecorationAndPreservesCaptureAndFilmEdits() {
        val legacy = """{"version":3,"filmFrame":"WHITE","dateStamp":"DATE_TIME","preset":"NEGATIVE","filmId":"NIGHT_01","iso":640,"exposureTimeNs":16000000,"focalLength":50,"whiteBalance":"DAYLIGHT","looks":{"FILM:NIGHT_01":{"grain":0.6,"saturation":-0.3}}}"""
        val loaded = SettingsJson.decode(legacy)
        // T121: NEGATIVE belongs to the GR, which has no film section — a pre-T121 roll
        // survives by migrating the document to the brand-neutral general body.
        assertEquals(BodyCatalog.GENERAL_ID, loaded.cameraBody)
        assertEquals("NIGHT_01", loaded.filmId)
        assertEquals(.6f, loaded.activeLook.grain, 0f)
        assertEquals(-.3f, loaded.activeLook.saturation, 0f)
        assertEquals(640, loaded.iso)
        assertEquals(16_000_000L, loaded.exposureTimeNs)
        assertEquals(50, loaded.focalLength)
        assertEquals("DAYLIGHT", loaded.whiteBalance)
        assertFalse(loaded.nameplateHidden)
        assertEquals("AUTO", loaded.captureOrientation)

        val saved = JSONObject(SettingsJson.encode(loaded))
        assertEquals(5, saved.getInt("version"))
        assertFalse(saved.has("filmFrame"))
        assertFalse(saved.has("dateStamp"))
        assertFalse(saved.has("mascotCamera"))
        assertEquals(loaded, SettingsJson.decode(saved.toString()))
    }

    @Test fun everyBodyChoiceAndCaptureDirectionSurviveRestart() {
        // T121: each body is tested with a look it actually offers and its own default
        // focal — anything else would be coerced on decode rather than round-tripped.
        val bodies = listOf(
            Triple(BodyCatalog.GENERAL_ID, "SUNNY", 28),
            Triple("RICOH_GR", "POSITIVE", 28),
            Triple("FUJIFILM_X100VI", "CLASSIC_CHROME", 35),
            Triple("SIGMA_FP", "POWDER_BLUE", 35),
            Triple("PANASONIC_LX100", "LUMIX_NATURAL", 24),
            Triple("NIKON_F3", "ORIGINAL", 50),
            Triple("ROLLEI_35", "ORIGINAL", 35),
        )
        for ((body, preset, focal) in bodies) for (orientation in listOf("AUTO", "LANDSCAPE", "PORTRAIT")) {
            for (hidden in listOf(false, true)) {
                val settings = AppSettings(cameraBody = body, preset = preset, focalLength = focal,
                    nameplateHidden = hidden, captureOrientation = orientation, whiteBalanceAmber = 3)
                assertEquals(settings, SettingsJson.decode(SettingsJson.encode(settings)))
            }
        }
    }

    @Test fun legacyMascotPinsMigrateToFunctionalBodiesAndHiddenToNameplateFlag() {
        // A legacy pinned mascot becomes the functional body...
        val pinned = SettingsJson.decode("""{"version":4,"mascotCamera":"FUJIFILM_X100VI","preset":"CLASSIC_CHROME"}""")
        assertEquals("FUJIFILM_X100VI", pinned.cameraBody)
        assertFalse(pinned.nameplateHidden)
        // ...the legacy HIDDEN string flips nameplateHidden on while the body follows the look...
        val hidden = SettingsJson.decode("""{"version":4,"mascotCamera":"HIDDEN","preset":"POSITIVE"}""")
        assertTrue(hidden.nameplateHidden)
        assertEquals("RICOH_GR", hidden.cameraBody)
        // ...a missing or null field leaves the nameplate shown and derives the body normally.
        val legacy = SettingsJson.decode("""{"version":2,"preset":"SUNNY"}""")
        assertFalse(legacy.nameplateHidden)
        assertEquals(BodyCatalog.GENERAL_ID, legacy.cameraBody)
        assertEquals("AUTO", legacy.captureOrientation)
        val follow = SettingsJson.decode("""{"version":4,"mascotCamera":null,"captureOrientation":"PORTRAIT"}""")
        assertFalse(follow.nameplateHidden)
        assertEquals(BodyCatalog.GENERAL_ID, follow.cameraBody)
        assertEquals("PORTRAIT", follow.captureOrientation)
        assertEquals(follow, SettingsJson.decode(SettingsJson.encode(follow)))
    }

    @Test fun unknownCameraPreferencesRecoverWithoutDiscardingTheSelectedLook() {
        val settings = SettingsJson.decode("""{"version":5,"cameraBody":"REMOVED_CAMERA","captureOrientation":"SIDEWAYS","preset":"POSITIVE","looks":{"POSITIVE":{"contrast":0.5}}}""")
        // An unknown explicit body id recovers to the body that owns the saved look (GR),
        // never to a blank general body that would silently swap the user's colours.
        assertEquals("RICOH_GR", settings.cameraBody)
        assertEquals("AUTO", settings.captureOrientation)
        assertEquals("POSITIVE", settings.preset)
        assertEquals(.5f, settings.activeLook.contrast, 0f)
        // Encode normalises unknown ids to GENERAL; a default POSITIVE look then coerces
        // to the general body's ORIGINAL.
        val encoded = SettingsJson.decode(SettingsJson.encode(AppSettings(cameraBody = "UNKNOWN", captureOrientation = "UNKNOWN")))
        assertEquals(BodyCatalog.GENERAL_ID, encoded.cameraBody)
        assertEquals("ORIGINAL", encoded.preset)
        assertEquals("AUTO", encoded.captureOrientation)
    }

    @Test fun quickShotModeIsOnByDefaultAndSurvivesRestartAndLegacyUpgrade() {
        assertTrue(AppSettings().quickShotMode)
        // Legacy documents without the field opt INTO quick mode (the new-user default)...
        assertTrue(SettingsJson.decode("""{"version":4,"preset":"MONO"}""").quickShotMode)
        // ...but an explicit pro-mode choice survives round trips.
        val pro = AppSettings(quickShotMode = false, compositionEnabled = true, compositionRealtime = true)
        assertFalse(SettingsJson.decode(SettingsJson.encode(pro)).quickShotMode)
        assertEquals(pro, SettingsJson.decode(SettingsJson.encode(pro)))
    }

    @Test fun bodyCoercionStripsCrossBrandLooksAndFilmsAndForcesLegalFocals() {
        // GR with a Fuji simulation + loaded roll + 35mm: everything illegal resets to GR.
        val gr = SettingsJson.decode(
            """{"version":5,"cameraBody":"RICOH_GR","preset":"CLASSIC_CHROME","filmId":"NIGHT_01","focalLength":35}""")
        assertEquals("POSITIVE", gr.preset)
        assertNull(gr.filmId)
        assertEquals(28, gr.focalLength)
        // GR keeps its own two frames...
        assertEquals(40, SettingsJson.decode(
            """{"version":5,"cameraBody":"RICOH_GR","preset":"NEGATIVE","focalLength":40}""").focalLength)
        // ...Fuji keeps simulations, rolls and its 35/50 frames...
        val fuji = SettingsJson.decode(
            """{"version":5,"cameraBody":"FUJIFILM_X100VI","preset":"ACROS","filmId":"NIGHT_01","focalLength":50}""")
        assertEquals("ACROS", fuji.preset)
        assertEquals("NIGHT_01", fuji.filmId)
        assertEquals(50, fuji.focalLength)
        assertEquals(35, SettingsJson.decode(
            """{"version":5,"cameraBody":"FUJIFILM_X100VI","preset":"CLASSIC_CHROME","focalLength":28}""").focalLength)
        // ...the Rollei 35 easter egg has exactly one frame: 35mm.
        val rollei = SettingsJson.decode(
            """{"version":5,"cameraBody":"ROLLEI_35","preset":"ORIGINAL","filmId":"NIGHT_01","focalLength":50}""")
        assertEquals(35, rollei.focalLength)
        assertEquals("NIGHT_01", rollei.filmId)
        // LX100 spans the documented 24–75 zoom; fp keeps a free 28/35/50 set.
        assertEquals(75, SettingsJson.decode(
            """{"version":5,"cameraBody":"PANASONIC_LX100","preset":"LUMIX_NATURAL","focalLength":75}""").focalLength)
        assertEquals(24, SettingsJson.decode(
            """{"version":5,"cameraBody":"PANASONIC_LX100","preset":"LUMIX_MONO","focalLength":40}""").focalLength)
        assertEquals(50, SettingsJson.decode(
            """{"version":5,"cameraBody":"SIGMA_FP","preset":"POWDER_BLUE","focalLength":50}""").focalLength)
    }

    @Test fun viewfinderOsdDefaultsOffAndEveryModeSurvivesRestart() {
        // Pure viewfinder by default; documents written before T20 simply lack the field.
        assertEquals(ViewfinderOsd.OFF, AppSettings().viewfinderOsd)
        assertEquals(ViewfinderOsd.OFF,
            SettingsJson.decode("""{"version":4,"preset":"MONO"}""").viewfinderOsd)
        for (mode in listOf(ViewfinderOsd.OFF, ViewfinderOsd.HISTOGRAM, ViewfinderOsd.PARAMS)) {
            val settings = AppSettings(viewfinderOsd = mode)
            assertEquals(settings, SettingsJson.decode(SettingsJson.encode(settings)))
        }
        // Corrupt/unknown values never leave a phantom overlay on screen.
        assertEquals(ViewfinderOsd.OFF,
            SettingsJson.decode("""{"version":4,"viewfinderOsd":"LEVELS"}""").viewfinderOsd)
        // The corner button toggles the small histogram; the exposure readout moved to the
        // always-on shoulder strip, so the in-picture cycle no longer has a PARAMS step.
        var mode: String? = null
        val sequence = List(3) { mode = ViewfinderOsd.next(mode); mode }
        assertEquals(listOf(ViewfinderOsd.HISTOGRAM, ViewfinderOsd.OFF, ViewfinderOsd.HISTOGRAM), sequence)
        // Legacy installs may still have PARAMS persisted: it decodes and draws as histogram.
        assertEquals(ViewfinderOsd.PARAMS,
            SettingsJson.decode("""{"version":4,"viewfinderOsd":"PARAMS"}""").viewfinderOsd)
        assertTrue(ViewfinderOsd.showsHistogram(ViewfinderOsd.PARAMS))
        assertFalse(ViewfinderOsd.showsHistogram(ViewfinderOsd.OFF))
    }

    @Test fun lookAdviceDefaultsOnAndDismissMemorySurvivesRoundTrip() {
        // Fresh installs get suggestions; legacy documents without the field default to on.
        assertTrue(AppSettings().lookAdviceEnabled)
        assertTrue(AppSettings().exposureAdviceEnabled)
        assertEquals(0, AppSettings().lookAdviceDismissStreak)
        assertTrue(AppSettings().lookDismissedAt.isEmpty())
        val legacy = SettingsJson.decode("""{"version":4,"preset":"MONO"}""")
        assertTrue(legacy.lookAdviceEnabled)
        assertTrue(legacy.exposureAdviceEnabled)
        val at = 1_700_000_000_000L
        val saved = AppSettings(
            lookAdviceEnabled = false,
            exposureAdviceEnabled = false,
            lookAdviceDismissStreak = 2,
            lookDismissedAt = mapOf("POSITIVE" to at, "FILM:MONO_01" to at + 1L),
        )
        val loaded = SettingsJson.decode(SettingsJson.encode(saved))
        assertEquals(saved, loaded)
        assertFalse(loaded.lookAdviceEnabled)
        assertFalse(loaded.exposureAdviceEnabled)
        assertEquals(2, loaded.lookAdviceDismissStreak)
        assertEquals(at, loaded.lookDismissedAt["POSITIVE"])
        // Bad streak values are clamped back into the valid 0..3 range.
        val repaired = SettingsJson.decode(
            """{"version":4,"lookAdviceEnabled":false,"lookAdviceDismissStreak":99,"lookDismissedAt":{"X":0,"VALID":123}}""")
        assertEquals(3, repaired.lookAdviceDismissStreak)
        assertEquals(setOf("VALID"), repaired.lookDismissedAt.keys)
    }

    @Test fun freshInstallSelectsTheRecommendedD1RankerAndUnknownIdsFallBack() {
        assertEquals("d1-omni-600m", D1ModelCatalog.DEFAULT_MODEL_ID)
        assertEquals(D1ModelCatalog.DEFAULT_MODEL_ID, AppSettings().compositionModel)
        // A document without the field (upgrades), a removed/unknown id, and a retired
        // SmolVLM selection all recover to the shipped default (d1-omni-600m).
        assertEquals(D1ModelCatalog.DEFAULT_MODEL_ID,
            SettingsJson.decode("""{"version":4,"preset":"MONO"}""").compositionModel)
        assertEquals(D1ModelCatalog.DEFAULT_MODEL_ID,
            SettingsJson.decode("""{"version":4,"compositionModel":"removed-model"}""").compositionModel)
        assertEquals(D1ModelCatalog.DEFAULT_MODEL_ID,
            SettingsJson.decode("""{"version":4,"compositionModel":"smolvlm-256m"}""").compositionModel)
        assertEquals(D1ModelCatalog.DEFAULT_MODEL_ID,
            SettingsJson.decode("""{"version":4,"compositionModel":"smolvlm-500m"}""").compositionModel)
        // The shipped selection is preserved rather than migrated.
        assertEquals("d1-omni-600m",
            SettingsJson.decode("""{"version":4,"compositionModel":"d1-omni-600m"}""").compositionModel)
    }
}
