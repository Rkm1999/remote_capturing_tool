package com.ryu.scunetdenoiser;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DenoiseProcessorTest {
    @Test
    public void highOverlapUsesHalfTileStrideAndBoundaryTiles() {
        assertEquals(414, DenoiseProcessor.tileCount(
            3000, 4000, DenoiseProcessor.OverlapMode.FAST));
        assertEquals(1419, DenoiseProcessor.tileCount(
            3000, 4000, DenoiseProcessor.OverlapMode.HIGH));
    }
}
