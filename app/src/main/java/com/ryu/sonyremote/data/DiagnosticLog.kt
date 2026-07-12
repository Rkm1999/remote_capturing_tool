package com.ryu.sonyremote.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object DiagnosticLog {
    private const val MAX_ENTRIES = 500
    private val entries = ArrayDeque<String>()
    private val lock = Any()

    fun record(event: String, details: Map<String, String> = emptyMap()) {
        val line = buildJsonObject {
            put("timestamp", Instant.now().toString())
            put("event", event)
            details.forEach { (key, value) -> put(key, value.take(500)) }
        }.toString()
        synchronized(lock) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun createShareIntent(context: Context): Intent {
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(directory, "sony-remote-diagnostics.jsonl")
        val snapshot = synchronized(lock) { entries.joinToString(separator = "\n", postfix = "\n") }
        file.writeText(snapshot, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

