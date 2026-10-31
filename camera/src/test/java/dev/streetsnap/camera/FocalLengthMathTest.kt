package dev.streetsnap.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocalLengthMathTest {
    @Test fun calculatesEquivalentFromFourByThreeActiveSensor() {
        assertEquals(21.6333076528, FocalLengthMath.equivalentMm(5.0, 8.0, 6.0)!!, 0.000001)
    }

    @Test fun cropsSensorToOutputAspectBeforeCalculatingEquivalent() {
        // A 36×24 sensor becomes 32×24 for a 4:3 output; its diagonal is 40mm.
        assertEquals(54.08326913196, FocalLengthMath.equivalentMm(50.0, 36.0, 24.0)!!, 0.000001)
        assertEquals(
            FocalLengthMath.equivalentMm(50.0, 36.0, 24.0),
            FocalLengthMath.equivalentMm(50.0, 24.0, 36.0),
        )
    }

    @Test fun unknownMetadataNeverPretendsToSupport28mm() {
        assertNull(FocalLengthMath.zoomRatio(28, null, 10f))
        assertEquals(listOf(FocalLength.NATIVE), FocalLengthMath.availableOptions(null, 10f))
    }

    @Test fun cropCannotProvideWiderViewThanNativeLens() {
        assertNull(FocalLengthMath.zoomRatio(28, 30.0, 10f))
        // T121: the catalog gained 24/40/75mm crops; a 30mm-native device with 10x zoom
        // supports every longer step from 35mm up.
        assertEquals(listOf(FocalLength.MM35, FocalLength.MM40, FocalLength.MM50, FocalLength.MM75),
            FocalLengthMath.availableOptions(30.0, 10f))
    }

    @Test fun zoomBoundAndNativeEqualityAreHandled() {
        assertEquals(1f, FocalLengthMath.zoomRatio(28, 28.0, 1f)!!, 0f)
        assertEquals(2f, FocalLengthMath.zoomRatio(50, 25.0, 2f)!!, 0f)
        assertNull(FocalLengthMath.zoomRatio(50, 25.0, 1.99f))
    }

    @Test fun rejectsInvalidDeviceMetadata() {
        assertNull(FocalLengthMath.equivalentMm(0.0, 8.0, 6.0))
        assertNull(FocalLengthMath.equivalentMm(5.0, Double.NaN, 6.0))
        assertNull(FocalLengthMath.equivalentMm(5.0, 8.0, -6.0))
        assertNull(FocalLengthMath.equivalentMm(5.0, 8.0, 6.0, Double.POSITIVE_INFINITY))
        assertNull(FocalLengthMath.zoomRatio(35, Double.POSITIVE_INFINITY, 10f))
    }
}
