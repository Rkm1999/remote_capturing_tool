package com.ryu.sonyremote.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Persistent private storage for computational source frames and their final-image relationships. */
class CaptureWorkspace(filesDir: File) {
    private val directory = File(filesDir, DIRECTORY_NAME)
    private val mutex = Mutex()

    class Item internal constructor(val name: String)

    suspend fun writeJpeg(jpeg: ByteArray): Item = lockedIo {
        val normalized = JpegValidator.normalize(jpeg)
        ensureDirectory()

        val item = Item("capture_${UUID.randomUUID()}.jpg")
        val destination = resolve(item)
        val temporary = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(normalized)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
            item
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    suspend fun readBytes(item: Item): ByteArray = lockedIo {
        val file = resolve(item)
        require(file.isFile) { "Capture workspace item does not exist" }
        file.readBytes()
    }

    suspend fun delete(item: Item): Boolean = lockedIo {
        val file = resolve(item)
        if (!file.exists()) return@lockedIo false
        if (!file.delete()) throw IOException("Could not delete capture workspace item")
        true
    }

    suspend fun associate(finalKey: String, items: List<Item>) = lockedIo {
        ensureDirectory()
        items.forEach { require(resolve(it).isFile) { "Capture source item does not exist" } }
        val destination = manifest(finalKey)
        val temporary = File.createTempFile(MANIFEST_TEMP_PREFIX, TEMP_SUFFIX, directory)
        try {
            temporary.writeText(items.joinToString("\n", transform = Item::name))
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    suspend fun relatedItems(finalKey: String): List<Item> = lockedIo {
        val manifest = manifest(finalKey)
        if (!manifest.isFile) return@lockedIo emptyList()
        manifest.readLines().filter(ITEM_NAME::matches).map(::Item).filter { resolve(it).isFile }
    }

    suspend fun clear() = lockedIo {
        if (!directory.exists()) return@lockedIo
        if (!directory.isDirectory) throw IOException("Capture workspace path is not a directory")
        val files = directory.listFiles() ?: throw IOException("Could not list capture workspace")
        files.forEach { file ->
            if (!file.deleteRecursively()) {
                throw IOException("Could not clear capture workspace")
            }
        }
    }

    private suspend fun <T> lockedIo(block: () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { block() }
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create capture workspace")
        }
        if (!directory.isDirectory) throw IOException("Capture workspace path is not a directory")
    }

    private fun resolve(item: Item): File {
        require(ITEM_NAME.matches(item.name)) { "Invalid capture workspace item" }
        return File(directory, item.name)
    }

    private fun manifest(finalKey: String): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(finalKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.sources")
    }

    private companion object {
        const val DIRECTORY_NAME = "computational-sources"
        const val TEMP_PREFIX = ".capture-"
        const val MANIFEST_TEMP_PREFIX = ".sources-"
        const val TEMP_SUFFIX = ".tmp"
        val ITEM_NAME = Regex("capture_[0-9a-fA-F-]{36}\\.jpg")
    }
}
