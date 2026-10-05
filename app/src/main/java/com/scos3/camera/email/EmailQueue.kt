package com.scos3.camera.email

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable local queue of images waiting to be emailed.
 *
 * Persisted as JSON in app-internal storage (not MediaStore), so it survives
 * process death. Capture is never blocked by email: a photo is enqueued right
 * after MediaStore save and the queue is drained in the background; if the
 * network is unavailable the item simply stays queued and is retried later.
 */
class EmailQueue(context: Context) {

    private val file = File(context.applicationContext.filesDir, "email_queue/pending.json")

    data class PendingItem(
        val uri: String,
        val displayName: String,
        val timeMillis: Long,
    )

    @Synchronized
    fun enqueue(uri: String, displayName: String) {
        val items = snapshot()
        if (items.any { it.uri == uri }) return
        items.add(PendingItem(uri, displayName, System.currentTimeMillis()))
        write(items)
    }

    @Synchronized
    fun remove(uri: String) {
        val items = snapshot()
        val before = items.size
        items.removeAll { it.uri == uri }
        if (items.size != before) write(items)
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    @Synchronized
    fun snapshot(): MutableList<PendingItem> {
        if (!file.exists()) return mutableListOf()
        return runCatching {
            val array = JSONArray(file.readText())
            val out = mutableListOf<PendingItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                out += PendingItem(
                    uri = obj.getString("uri"),
                    displayName = obj.getString("name"),
                    timeMillis = obj.optLong("time", 0L),
                )
            }
            out
        }.getOrElse { mutableListOf() }
    }

    @Synchronized
    fun count(): Int = snapshot().size

    private fun write(items: List<PendingItem>) {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().apply {
                put("uri", it.uri)
                put("name", it.displayName)
                put("time", it.timeMillis)
            })
        }
        file.writeText(array.toString())
    }
}
