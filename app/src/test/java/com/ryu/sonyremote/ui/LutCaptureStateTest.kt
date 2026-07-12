package com.ryu.sonyremote.ui

import com.ryu.sonyremote.processing.LutPreset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LutCaptureStateTest {
    @Test
    fun neutralPresetAndZeroIntensityAreOriginal() {
        assertTrue(LutCaptureState().isOriginal)
        assertTrue(LutCaptureState(preset = LutPreset.Cinema).withIntensity(LutPreset.Cinema, 0f).isOriginal)
        assertFalse(LutCaptureState(preset = LutPreset.Cinema).withIntensity(LutPreset.Cinema, 0.75f).isOriginal)
    }

    @Test
    fun intensityMustStayNormalized() {
        assertThrows(IllegalArgumentException::class.java) {
            LutCaptureState(presetIntensities = mapOf(LutPreset.Cinema to 1.1f))
        }
    }

    @Test
    fun eachPresetKeepsItsOwnIntensity() {
        val state = LutCaptureState()
            .withIntensity(LutPreset.Cinema, 0.35f)
            .withIntensity(LutPreset.Warm, 0.8f)

        assertEquals(0.35f, state.select(LutPreset.Cinema).intensity)
        assertEquals(0.8f, state.select(LutPreset.Warm).intensity)
    }

    @Test
    fun optionalPresetsCanBeAddedAndRemoved() {
        val added = LutCaptureState().addPreset(LutPreset.Fade).select(LutPreset.Fade)
        assertTrue(LutPreset.Fade in added.visiblePresets)

        val removed = added.removePreset(LutPreset.Fade)
        assertFalse(LutPreset.Fade in removed.visiblePresets)
        assertEquals(LutPreset.Neutral, removed.preset)
        assertTrue(LutPreset.Neutral in removed.removePreset(LutPreset.Neutral).visiblePresets)
    }
}
