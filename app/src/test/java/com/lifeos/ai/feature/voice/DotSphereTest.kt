package com.lifeos.ai.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class DotSphereTest {

    @Test fun `fibonacci lattice puts every dot on the unit sphere, evenly balanced`() {
        val count = 1400
        val points = fibonacciSphere(count)
        assertEquals(count * 3, points.size)
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        for (i in 0 until count) {
            val x = points[i * 3]
            val y = points[i * 3 + 1]
            val z = points[i * 3 + 2]
            assertEquals(1.0, sqrt((x * x + y * y + z * z).toDouble()), 1e-4)
            sx += x; sy += y; sz += z
        }
        // An even spread has its centre of mass at the origin.
        assertTrue(abs(sx / count) < 0.01 && abs(sy / count) < 0.01 && abs(sz / count) < 0.01)
    }

    @Test fun `touching the orb scatters dots that then spring back to rest`() {
        val sphere = DotSphere(count = 400)
        sphere.project(500f, 500f, 200f)
        sphere.push(500f, 500f, 0f, 0f, radius = 150f, strength = 800f)
        repeat(6) { sphere.step(1 / 60f, OrbMode.IDLE, 0f, 0, 1f) }
        assertTrue("dots should be displaced", sphere.maxOffset() > 1f)

        repeat(60 * 5) { sphere.step(1 / 60f, OrbMode.IDLE, 0f, 0, 1f) }
        assertFalse(sphere.isScattering)
        assertEquals(0f, sphere.maxOffset(), 0f)
    }

    @Test fun `listening energy follows the microphone level`() {
        val sphere = DotSphere(count = 50)
        repeat(30) { sphere.step(1 / 60f, OrbMode.LISTENING, 1f, 0, 1f) }
        val loud = sphere.energy
        repeat(120) { sphere.step(1 / 60f, OrbMode.LISTENING, 0f, 0, 1f) }
        assertTrue(loud > 0.8f)
        assertTrue(sphere.energy < 0.15f)
    }
}
