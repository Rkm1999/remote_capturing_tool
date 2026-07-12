package com.ryu.sonyremote.processing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CubeLutTest {
    @Test
    fun parsesStandard3dCubeFile() {
        val cube = CubeLut.parse(
            """
            TITLE "Identity"
            LUT_3D_SIZE 2
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
            """.trimIndent(),
        )

        assertEquals(2, cube.size)
        val output = cube.sample(0.25f, 0.5f, 0.75f)
        assertEquals(0.25f, output[0], 0.001f)
        assertEquals(0.5f, output[1], 0.001f)
        assertEquals(0.75f, output[2], 0.001f)
    }

    @Test
    fun rejectsIncompleteCubeFile() {
        assertThrows(IllegalArgumentException::class.java) {
            CubeLut.parse("LUT_3D_SIZE 2\n0 0 0")
        }
    }
    @Test
    fun zeroIntensityPreservesOriginalAndFullIntensityUsesLut() {
        val pixel = 0xff336699.toInt()
        val lut = CubeLut.forPreset(LutPreset.Mono)

        assertEquals(pixel, lut.mapArgb(pixel, 0f))
        assertEquals(lut.mapArgb(pixel), lut.mapArgb(pixel, 1f))
    }
    @Test
    fun neutralLutPreservesInterpolatedRgb() {
        val output = CubeLut.forPreset(LutPreset.Neutral).sample(0.13f, 0.57f, 0.91f)

        assertArrayEquals(floatArrayOf(0.13f, 0.57f, 0.91f), output, 0.0001f)
    }

    @Test
    fun monoLutUsesColorLuminance() {
        val output = CubeLut.forPreset(LutPreset.Mono).sample(1f, 0f, 0f)

        assertEquals(output[0], output[1], 0.0001f)
        assertEquals(output[1], output[2], 0.0001f)
        assertEquals(0.2126f, output[0], 0.0001f)
    }

    @Test
    fun presetMappingPreservesAlphaAndClampsChannels() {
        val output = CubeLut.forPreset(LutPreset.Cinema).mapArgb(0x7fffc080)

        assertEquals(0x7f, output ushr 24)
        assertTrue(output and 0x00ffffff in 0..0x00ffffff)
    }
}
