package com.borasarang.droidrelay.relay

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * jobs.json 영구 저장 (T-107)
 * - 상태 변화마다 저장(서비스에서 디바운스 호출 권장)
 * - 복원 규칙:
 *   · RUNNING/QUEUED → QUEUED (.part 존재 시 이어받기, 없으면 처음부터)
 *   · DONE → doneFile 실존 시에만 유지, 없으면 제거
 *   · PAUSED/FAILED/CANCELED → 그대로 유지
 */
class JobsPersistence(
    context: Context,
) {
    private val TAG = "Persist"
    private val workDir = File(context.getExternalFilesDir(null), "downloads")
    private val file = File(context.getExternalFilesDir(null), "jobs.json")

    private fun doneFileOf(job: Job): File = File(workDir, job.filename)

    @Synchronized
    fun save(jobs: List<Job>) {
        try {
            val arr = JSONArray()
            jobs.forEach { j ->
                arr.put(JSONObject().apply {
                    put("id", j.id)
                    put("url", j.url)
                    put("filename", j.filename)
                    put("state", j.state.name)
                    put("downloadedBytes", j.downloadedBytes)
                    put("totalBytes", j.totalBytes)
                    put("order", j.order)
                    if (j.startedAt > 0) put("startedAt", j.startedAt)
                    if (j.finishedAt > 0) put("finishedAt", j.finishedAt)
                    if (j.expectedSha256 != null) put("expectedSha256", j.expectedSha256)
                    if (j.verified) put("verified", true)
                    if (j.type != "http") put("type", j.type)
                })
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file) || tmp.copyTo(file, overwrite = true).let { tmp.delete() }
            DebugLogger.d(TAG, "이력 저장 ${jobs.size}건")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "이력 저장 실패(무시 가능, E-AND-DOWN-2003)", e)
        }
    }

    @Synchronized
    fun load(): List<Job> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val state = runCatching { JobState.valueOf(o.getString("state")) }
                    .getOrDefault(JobState.QUEUED)
                val job = Job(
                    id = o.getString("id"),
                    url = o.getString("url"),
                    filename = o.getString("filename"),
                    state = state,
                    downloadedBytes = o.optLong("downloadedBytes", 0L),
                    totalBytes = o.optLong("totalBytes", -1L),
                    order = o.optInt("order", 0),
                    startedAt = o.optLong("startedAt", 0L),
                    finishedAt = o.optLong("finishedAt", 0L),
                    expectedSha256 = if (o.has("expectedSha256")) o.getString("expectedSha256") else null,
                    verified = o.optBoolean("verified", false),
                    type = o.optString("type", "http"),
                )
                when (job.state) {
                    JobState.RUNNING, JobState.QUEUED ->
                        job.copy(state = JobState.QUEUED, progress = 0f)
                    JobState.DONE ->
                        if (doneFileOf(job).exists()) job.copy(progress = 1f) else null
                    else -> job
                }
            }
        }.getOrElse {
            DebugLogger.e(TAG, "이력 복원 실패 — 초기화 진행 (E-AND-DOWN-2003)", it)
            emptyList()
        }.also {
            if (it.isNotEmpty()) DebugLogger.i(TAG, "이력 복원 ${it.size}건")
        }
    }
}
