package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test

class CameraFocusMathTest {
    @Test fun centreStaysCentredForBothLensesAndEveryDisplayRotation() {
        for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            assertArrayEquals(intArrayOf(-120, -120, 120, 120), CameraFocusMath.area(.5f, .5f, rotation, mirror))
        }
    }

    @Test fun offCentreTapUndoesDisplayRotationBeforeFrontLensMirror() {
        // Screen point (-500, 250), after normalizing Camera.Area coordinates.
        // Android's documented forward matrix mirrors sensor X, then rotates clockwise.
        val expected = mapOf(0 to (-500 to 250), 90 to (250 to 500), 180 to (500 to -250), 270 to (-250 to -500))
        for ((rotation, point) in expected) for (mirror in listOf(false, true)) {
            val x = if (mirror) -point.first else point.first
            val y = point.second
            assertArrayEquals("rotation=$rotation mirror=$mirror", intArrayOf(x - 120, y - 120, x + 120, y + 120),
                CameraFocusMath.area(.25f, .625f, rotation, mirror))
        }
    }

    @Test fun edgeAndOutOfBoundsTapsAlwaysProduceValidCameraAreas() {
        for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            for (x in listOf(-.1f, 0f, .5f, 1f, 1.1f)) for (y in listOf(-.1f, 0f, .5f, 1f, 1.1f)) {
                val area = CameraFocusMath.area(x, y, rotation, mirror)
                assertEquals(4, area.size)
                assertTrue(area.all { it in -1000..1000 })
                assertTrue("empty horizontal area", area[0] < area[2])
                assertTrue("empty vertical area", area[1] < area[3])
            }
        }
    }
}
