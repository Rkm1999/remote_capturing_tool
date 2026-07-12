package com.ryu.sonyremote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ContinuousBatchResultTest {
    @Test
    fun completedCountIncludesFailedDownloads() {
        val result = ContinuousBatchResult(
            reportedUrlCount = 3,
            importedCaptures = emptyList(),
            failedDownloadCount = 2,
        )

        assertEquals(2, result.completedDownloadCount)
        assertEquals(0, result.importedCaptures.size)
    }

    @Test
    fun reportedCountMayIncludeDuplicateUrlsThatWereNotQueuedAgain() {
        val result = ContinuousBatchResult(
            reportedUrlCount = 4,
            importedCaptures = emptyList(),
            failedDownloadCount = 0,
        )

        assertEquals(0, result.completedDownloadCount)
    }

    @Test
    fun rejectsMoreDownloadOutcomesThanReportedUrls() {
        try {
            ContinuousBatchResult(
                reportedUrlCount = 1,
                importedCaptures = emptyList(),
                failedDownloadCount = 2,
            )
            fail("Expected invalid outcome counts to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
