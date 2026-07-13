package com.ryu.sonyremote.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureWorkspaceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun concurrentWritesAreNormalizedUniqueAndCleanable() = runBlocking {
        val workspace = CaptureWorkspace(temporaryFolder.root)
        val normalized = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val padded = normalized + byteArrayOf(0, 0, 0)

        val items = List(12) {
            async(Dispatchers.Default) { workspace.writeJpeg(padded) }
        }.awaitAll()

        assertEquals(items.size, items.map { it.name }.toSet().size)
        items.forEach { assertArrayEquals(normalized, workspace.readBytes(it)) }
        val workspaceDirectory = File(temporaryFolder.root, "computational-sources")
        assertFalse(workspaceDirectory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })

        workspace.associate("content://gallery/final/1", items.take(3))
        assertEquals(items.take(3).map { it.name }, workspace.relatedItems("content://gallery/final/1").map { it.name })
        assertTrue(workspace.relatedItems("content://gallery/final/missing").isEmpty())

        assertTrue(workspace.delete(items.first()))
        assertFalse(workspace.delete(items.first()))
        workspace.clear()
        assertTrue(workspaceDirectory.listFiles().orEmpty().isEmpty())
    }
}
