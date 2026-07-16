package com.ryu.scunetdenoiser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class NpuSupportTest {
    @Test
    public void mapsOfficialQualcommMobileSocsToHtpArchitectures() {
        assertEquals("V69", NpuSupport.qualcommHtpArchitecture("SM8450"));
        assertEquals("V69", NpuSupport.qualcommHtpArchitecture("SM8475"));
        assertEquals("V73", NpuSupport.qualcommHtpArchitecture("SM8550"));
        assertEquals("V75", NpuSupport.qualcommHtpArchitecture("SM8650"));
        assertEquals("V79", NpuSupport.qualcommHtpArchitecture("SM8750"));
        assertEquals("V81", NpuSupport.qualcommHtpArchitecture("SM8850"));
    }

    @Test
    public void normalizesSocModelCase() {
        assertEquals("V75", NpuSupport.qualcommHtpArchitecture("sm8650"));
    }

    @Test
    public void rejectsUnpackagedQualcommRuntime() {
        assertNull(NpuSupport.qualcommHtpArchitecture("SM8350"));
        assertNull(NpuSupport.qualcommHtpArchitecture(null));
    }
}
