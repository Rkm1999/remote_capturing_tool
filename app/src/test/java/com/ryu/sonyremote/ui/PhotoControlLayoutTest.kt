package com.ryu.sonyremote.ui

import com.ryu.sonyremote.model.CameraSetting
import com.ryu.sonyremote.model.CameraSettingId
import com.ryu.sonyremote.model.CameraSettingOption
import com.ryu.sonyremote.model.CameraCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhotoControlLayoutTest {
    @Test
    fun calculatesLiveNdExposureFromShutterAndFrames() {
        assertEquals("625ms", liveNdExposureLabel("1/8", 4))
        assertEquals("10s", liveNdExposureLabel("2", 4))
        assertEquals(null, liveNdExposureLabel("BULB", 8))
    }
    @Test
    fun mapsKnownExposureModesAndPreservesUnknownLabels() {
        assertEquals("P", exposureModeShortLabel("Program Auto"))
        assertEquals("A", exposureModeShortLabel("Aperture Priority"))
        assertEquals("S", exposureModeShortLabel("Shutter Priority"))
        assertEquals("M", exposureModeShortLabel("Manual Exposure"))
        assertEquals("Movie Auto", exposureModeShortLabel("Movie Auto"))
    }

    @Test
    fun quickControlsArePrioritizedAndSecondaryControlsRemainAvailable() {
        val settings = listOf(
            setting(CameraSettingId.ContShootingMode, writable = true),
            setting(CameraSettingId.IsoSpeedRate, writable = false),
            setting(CameraSettingId.ExposureMode, writable = false),
            setting(CameraSettingId.FNumber, writable = true),
        )

        val (quick, secondary) = prioritizedPhotoSettings(settings)

        assertEquals(
            listOf(CameraSettingId.ExposureMode, CameraSettingId.FNumber, CameraSettingId.IsoSpeedRate),
            quick.map(CameraSetting::id),
        )
        assertEquals(listOf(CameraSettingId.ContShootingMode), secondary.map(CameraSetting::id))
        assertFalse(quick.first().isWritable)
    }

    @Test
    fun routesPriorityDragByExposureMode() {
        val aperture = CameraSetting(
            CameraSettingId.FNumber,
            "4.0",
            listOf(CameraSettingOption("f/2.8", "2.8"), CameraSettingOption("f/4", "4.0")),
        )
        val shutter = CameraSetting(
            CameraSettingId.ShutterSpeed,
            "1/60",
            listOf(CameraSettingOption("1/60", "1/60"), CameraSettingOption("1/125", "1/125")),
        )
        fun capabilities(mode: String) = CameraCapabilities(settings = listOf(
            CameraSetting(CameraSettingId.ExposureMode, mode, listOf(CameraSettingOption(mode, mode)), false),
            aperture,
            shutter,
        ).associateBy { it.id })

        assertEquals("2.8", exposurePrioritySetting(capabilities("Aperture Priority"), -1)?.second)
        assertEquals("1/125", exposurePrioritySetting(capabilities("Shutter Priority"), 1)?.second)
        assertEquals(CameraSettingId.FNumber, exposurePrioritySetting(capabilities("Manual Exposure"), -1)?.first?.id)
        assertEquals(null, exposurePrioritySetting(capabilities("Program Auto"), 1))
    }

    @Test
    fun stopsFeedbackZoomWhenTargetIsCrossed() {
        assertFalse(zoomTargetReached(48, 50, "in"))
        assertEquals(true, zoomTargetReached(49, 50, "in"))
        assertFalse(zoomTargetReached(52, 50, "out"))
        assertEquals(true, zoomTargetReached(51, 50, "out"))
    }

    @Test
    fun zoomTargetSnapsNearRangeTransitions() {
        assertEquals(50, snapZoomTarget(47, boxCount = 2))
        assertEquals(50, snapZoomTarget(53, boxCount = 2))
        assertEquals(40, snapZoomTarget(40, boxCount = 2))
        assertEquals(33, snapZoomTarget(31, boxCount = 3))
        assertEquals(66, snapZoomTarget(68, boxCount = 3))
    }

    @Test
    fun removesStillCaptureTypeAndSettingsDuplicatedBesideShutter() {
        fun capabilities(mode: String) = CameraCapabilities(settings = listOf(
            setting(CameraSettingId.ShootMode, writable = true),
            CameraSetting(CameraSettingId.ExposureMode, mode, emptyList(), false),
            setting(CameraSettingId.FNumber, writable = true),
            setting(CameraSettingId.ShutterSpeed, writable = true),
            setting(CameraSettingId.IsoSpeedRate, writable = true),
            setting(CameraSettingId.ContShootingMode, writable = true),
        ).associateBy { it.id })

        assertEquals(
            listOf(CameraSettingId.ShutterSpeed, CameraSettingId.IsoSpeedRate, CameraSettingId.ContShootingMode),
            photoSettingsAfterShutterControls(capabilities("Aperture Priority")).map { it.id },
        )
        assertEquals(
            listOf(CameraSettingId.FNumber, CameraSettingId.IsoSpeedRate, CameraSettingId.ContShootingMode),
            photoSettingsAfterShutterControls(capabilities("Shutter Priority")).map { it.id },
        )
    }

    private fun setting(id: CameraSettingId, writable: Boolean) = CameraSetting(
        id = id,
        currentWireValue = "value",
        options = emptyList(),
        isWritable = writable,
    )
}
