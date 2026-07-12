package com.ryu.sonyremote.ui

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedCaptureStateTest {
    @Test
    fun liveViewPlaceholderIsNotReportedAsCapturedPreview() {
        val capture = ImportedCapture(
            postviewRemoteUrl = URI("http://192.168.122.1/original.jpg?size=Original"),
            postviewIsOriginal = true,
            isLiveViewPlaceholder = true,
        )

        assertEquals("Live view", capture.qualityLabel)
        assertEquals(null, capture.previewUri)
        assertEquals(OriginalImportState.Available, capture.originalImportState)
    }

    private val originalUrl = URI("http://192.168.122.1/postview/original.jpg?size=Original")

    @Test
    fun originalIsUnavailableWithoutOriginalUrlOrContentReference() {
        val capture = ImportedCapture(
            postviewRemoteUrl = URI("http://192.168.122.1/postview/preview.jpg?size=2M"),
            postviewIsOriginal = false,
        )

        val request = requestOriginalImport(capture)

        assertEquals(OriginalImportState.NotAvailable, capture.originalImportState)
        assertFalse(request.shouldEnqueue)
        assertSame(capture, request.capture)
    }

    @Test
    fun duplicateOriginalRequestsAreNotEnqueued() {
        val available = ImportedCapture(
            postviewRemoteUrl = originalUrl,
            postviewIsOriginal = true,
        )

        val first = requestOriginalImport(available)
        val duplicate = requestOriginalImport(first.capture)

        assertTrue(first.shouldEnqueue)
        assertEquals(OriginalImportState.Queued, first.capture.originalImportState)
        assertFalse(duplicate.shouldEnqueue)
        assertEquals(first.capture, duplicate.capture)
    }

    @Test
    fun failedOriginalCanRetryWithoutChangingPreviewRecord() {
        val capture = ImportedCapture(
            id = "capture-1",
            postviewRemoteUrl = originalUrl,
            postviewIsOriginal = true,
        )
        val failed = markOriginalImportFailed(markOriginalImportDownloading(requestOriginalImport(capture).capture))

        val retry = requestOriginalImport(failed)

        assertTrue(retry.shouldEnqueue)
        assertEquals("capture-1", retry.capture.id)
        assertEquals(capture.previewUri, retry.capture.previewUri)
        assertEquals(OriginalImportState.Queued, retry.capture.originalImportState)
    }

    @Test
    fun cancellationReturnsToAvailableWhenOriginalSourceRemainsValid() {
        val queued = requestOriginalImport(
            ImportedCapture(
                postviewRemoteUrl = originalUrl,
                postviewIsOriginal = true,
            ),
        ).capture

        val cancelled = cancelOriginalImport(markOriginalImportDownloading(queued))

        assertEquals(OriginalImportState.Available, cancelled.originalImportState)
    }

    @Test
    fun contentsTransferReferenceMakesOriginalAvailableWithoutPostviewUrl() {
        val capture = ImportedCapture(cameraContentId = "storage:memoryCard1/DCIM/100MSDCF/DSC00001.JPG")

        assertEquals(OriginalImportState.Available, capture.originalImportState)
        assertTrue(requestOriginalImport(capture).shouldEnqueue)
    }
}
