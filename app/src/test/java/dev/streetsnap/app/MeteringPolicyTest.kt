package dev.streetsnap.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteringPolicyTest {
    @Test fun defaultQuickShotDoesNotBindAnIdleAnalysisStream() {
        assertFalse(AppSettings().needsLumaAnalysis)
    }

    @Test fun exposureAdviceOnlyNeedsFramesOutsideQuickShot() {
        assertFalse(AppSettings(quickShotMode = true, exposureAdviceEnabled = false).needsLumaAnalysis)
        assertFalse(AppSettings(quickShotMode = true, exposureAdviceEnabled = true).needsLumaAnalysis)
        assertFalse(AppSettings(quickShotMode = false, exposureAdviceEnabled = false).needsLumaAnalysis)
        assertTrue(AppSettings(quickShotMode = false, exposureAdviceEnabled = true).needsLumaAnalysis)
    }

    @Test fun explicitHistogramKeepsFramesInEitherModeIncludingLegacyOsd() {
        for (osd in listOf(ViewfinderOsd.HISTOGRAM, ViewfinderOsd.PARAMS)) {
            for (quickShot in listOf(false, true)) {
                for (advice in listOf(false, true)) {
                    val settings = AppSettings(viewfinderOsd = osd, quickShotMode = quickShot,
                        exposureAdviceEnabled = advice)
                    assertTrue("$osd, quickShot=$quickShot, exposureAdvice=$advice", settings.needsLumaAnalysis)
                }
            }
        }
    }

    @Test fun quickShotToggleReleasesFramesWhenHistogramIsOff() {
        val pro = AppSettings(quickShotMode = false)
        assertTrue(pro.needsLumaAnalysis)
        val quick = pro.copy(quickShotMode = true)
        assertFalse(quick.needsLumaAnalysis)
        assertTrue(quick.copy(viewfinderOsd = ViewfinderOsd.HISTOGRAM).needsLumaAnalysis)
    }
}
