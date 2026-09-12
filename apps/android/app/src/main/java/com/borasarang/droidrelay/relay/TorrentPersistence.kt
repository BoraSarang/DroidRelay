package com.borasarang.droidrelay.relay

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class TorrentPersistence(private val context: Context) {

    private val file: File
        get() = File(context.filesDir, "torrents.json")

    fun save(torrents: List<TorrentJob>) {
        try {
            val arr = JSONArray()
            torrents.forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("infoHash", t.infoHash)
                    put("name", t.name)
                    put("magnet", t.magnet ?: JSONObject.NULL)
                    put("state", t.state.name)
                    put("progress", t.progress.toDouble())
                    put("totalSize", t.totalSize)
                    put("downloadedSize", t.downloadedSize)
                    put("order", t.order)
                    put("uploadLimit", t.uploadLimit)
                    put("downloadLimit", t.downloadLimit)
                    put("savePath", t.savePath)
                    put("startedAt", t.startedAt)
                    put("finishedAt", t.finishedAt)
                    put("files", JSONArray().apply {
                        t.files.forEach { f ->
                            put(JSONObject().apply {
                                put("index", f.index)
                                put("path", f.path)
                                put("size", f.size)
                                put("progress", f.progress.toDouble())
                                put("selected", f.selected)
                            })
                        }
                    })
                })
            }
            file.writeText(arr.toString())
            DebugLogger.d("TorrentPersist", "저장 완료 ${torrents.size}건")
        } catch (e: Exception) {
            DebugLogger.e("TorrentPersist", "저장 실패", e)
        }
    }

    fun load(): List<TorrentJob> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<TorrentJob>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(TorrentJob(
                    id = o.getString("id"),
                    infoHash = o.getString("infoHash"),
                    name = o.getString("name"),
                    magnet = o.optString("magnet", null),
                    state = runCatching { TorrentState.valueOf(o.getString("state")) }
                        .getOrDefault(TorrentState.QUEUED),
                    progress = o.optDouble("progress", 0.0).toFloat(),
                    totalSize = o.optLong("totalSize", 0L),
                    downloadedSize = o.optLong("downloadedSize", 0L),
                    order = o.optInt("order", 0),
                    uploadLimit = o.optLong("uploadLimit", 0L),
                    downloadLimit = o.optLong("downloadLimit", 0L),
                    savePath = o.optString("savePath", ""),
                    startedAt = o.optLong("startedAt", 0L),
                    finishedAt = o.optLong("finishedAt", 0L),
                    files = runCatching {
                        val filesArr = o.optJSONArray("files") ?: JSONArray()
                        (0 until filesArr.length()).map { fi ->
                            val fo = filesArr.getJSONObject(fi)
                            TorrentFile(
                                index = fo.getInt("index"),
                                path = fo.getString("path"),
                                size = fo.getLong("size"),
                                progress = fo.optDouble("progress", 0.0).toFloat(),
                                selected = fo.optBoolean("selected", true),
                            )
                        }
                    }.getOrDefault(emptyList()),
                ))
            }
            DebugLogger.d("TorrentPersist", "로드 완료 ${list.size}건")
            list
        } catch (e: Exception) {
            val bak = PersistenceGuard.backupCorrupt(file)
            DebugLogger.e("TorrentPersist", "로드 실패 — 백업 ${bak?.name ?: "없음"} 후 초기화 (E-AND-DOWN-2003)", e)
            emptyList()
        }
    }
}
