package com.ryu.sonyremote.data

import com.ryu.sonyremote.protocol.CameraApiException
import com.ryu.sonyremote.model.CameraCaptureEventKind
import com.ryu.sonyremote.model.RemoteCapture
import java.io.IOException
import java.net.URI
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalShutterCaptureSupportTest {
    @Test
    fun ordinarySingleOriginalIsRetainedForUserRequestedImport() {
        val capture = RemoteCapture(
            kind = CameraCaptureEventKind.Single,
            postviewUri = URI("http://192.168.122.1/postview/photo.jpg?size=Original"),
        )

        assertTrue(shouldRetainSingleOriginalReference(capture, computational = false))
        assertFalse(shouldRetainSingleOriginalReference(capture, computational = true))
    }

    @Test
    fun twoMegapixelSingleEventRemainsAnImmediatePreviewDownload() {
        val capture = RemoteCapture(
            kind = CameraCaptureEventKind.Single,
            postviewUri = URI("http://192.168.122.1/postview/photo.jpg?size=2M"),
        )

        assertFalse(shouldRetainSingleOriginalReference(capture, computational = false))
    }
    @Test
    fun recentCaptureUrisDeduplicatesWithinBoundedWindow() {
        val recent = RecentCaptureUris(capacity = 2)
        val first = URI("http://192.168.122.1/postview/DSC00001.JPG?size=Origin")
        val second = URI("http://192.168.122.1/postview/DSC00002.JPG?size=Origin")
        val third = URI("http://192.168.122.1/postview/DSC00003.JPG?size=Origin")

        assertTrue(recent.add(first))
        assertTrue(recent.contains(first))
        assertFalse(recent.add(first))
        assertTrue(recent.add(second))
        assertTrue(recent.add(third))
        assertFalse(recent.contains(first))
        assertTrue(recent.add(first))
        assertFalse(recent.add(third))
    }

    @Test
    fun detectsOriginalPostviewFromStructuredSizeParameter() {
        assertTrue(
            URI("http://192.168.122.1/postview/image.jpg?foo=1&size=Origin")
                .requestsOriginalPostview(),
        )
        assertTrue(
            URI("http://192.168.122.1/postview/image.jpg?SIZE=original")
                .requestsOriginalPostview(),
        )
        assertFalse(
            URI("http://192.168.122.1/postview/image.jpg?description=size=Origin")
                .requestsOriginalPostview(),
        )
        assertFalse(
            URI("http://192.168.122.1/postview/image.jpg?size=2M")
                .requestsOriginalPostview(),
        )
    }

    @Test
    fun continuousPhotoImportDownloadsReportedPostviewImmediately() {
        val thumbnail = URI("http://192.168.122.1/thumbnail/1.jpg")
        val postview = URI("http://192.168.122.1/postview/1.jpg?size=Original")
        val capture = RemoteCapture(CameraCaptureEventKind.Continuous, postview, thumbnail)

        val selected = selectRemoteCaptureDownload(capture, computational = false)

        assertEquals(postview, selected.uri)
        assertFalse(selected.previewFirst)
    }

    @Test
    fun computationalCaptureUsesPostviewInsteadOfThumbnail() {
        val thumbnail = URI("http://192.168.122.1/thumbnail/1.jpg")
        val postview = URI("http://192.168.122.1/postview/1.jpg?size=2M")
        val capture = RemoteCapture(CameraCaptureEventKind.Continuous, postview, thumbnail)

        val selected = selectRemoteCaptureDownload(capture, computational = true)

        assertEquals(postview, selected.uri)
        assertFalse(selected.previewFirst)
    }

    @Test
    fun classifiesIdleLongPollTimeoutsAsNormal() {
        assertTrue(SocketTimeoutException("idle poll").isNormalEventPollingTimeout())
        assertTrue(CameraApiException(2, "Timeout").isNormalEventPollingTimeout())
        assertFalse(CameraApiException(40402, "Already polling").isNormalEventPollingTimeout())
        assertFalse(IOException("connection reset").isNormalEventPollingTimeout())
    }
}
