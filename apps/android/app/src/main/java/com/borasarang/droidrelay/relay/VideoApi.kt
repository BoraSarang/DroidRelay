package com.borasarang.droidrelay.relay

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 비디오 분석/생성 오케스트레이션 — 웹 API(RelayServer)와 앱 Compose UI가 공용으로 사용.
 * 실패는 사용자 노출용 VideoException(code, msg)으로 래핑된다.
 */
object VideoApi {
    private const val TAG = "VideoApi"

    /** 분석 — 스트림(m3u8/mpd)/동영상(mp4) 전용. 실패 시 VideoException. */
    suspend fun analyze(url: String): JSONObject = withContext(Dispatchers.IO) {
        DebugLogger.i(TAG, "[FEATURE] 분석 시작 url=${url.take(90)}")
        val d = StreamDetector.analyze(url)
        JSONObject().apply {
            put("kind", d.kind)
            put("title", d.title)
            put("streamUrl", d.url)
            put("direct", d.isDirect)
            put("segmentsTotal", d.segmentsTotal)
            val q = org.json.JSONArray().apply {
                d.qualities.forEach { qu ->
                    put(JSONObject().apply {
                        put("label", qu.label)
                        put("url", qu.url)
                        put("protocol", qu.protocol)
                    })
                }
            }
            put("qualities", q)
        }
    }

    /** 비디오 잡 생성 + 즉시 FFmpeg 시작. 실패 시 VideoException. */
    suspend fun create(
        context: Context,
        url: String,
        streamUrl: String?,
        filename: String?,
    ): Job = withContext(Dispatchers.IO) {
        val vm = RelayApp.getVideo(context)
        val found = StreamDetector.analyze(url)
        val stream = streamUrl?.ifBlank { found.url } ?: found.url
        // 분석 대상과 다운로드 대상이 같으면 재fetch 없이 계측값 재사용(403 사이트는 분석 단계에서 이미 즉시 실패)
        val m = if (stream == found.url) {
            StreamDetector.ManifestResult(emptyList(), found.segmentsTotal, found.durationMs)
        } else {
            StreamDetector.parseManifest(stream)
        }
        val segments = m.segments
        val durationMs = m.durationMs
        val outName = VideoDownloadManager.safeFilename(filename, "mp4")
        val argv = listOf(
            "-i", stream, "-c", "copy", "-movflags", "+faststart",
            File(vm.workDir, outName).absolutePath,
        )
        val job = vm.createAndStart(url, outName, argv, segments, durationMs)
        DebugLogger.i(TAG, "[FEATURE] 비디오 잡 생성 id=${job.id} '${job.filename}' seg=$segments dur=${durationMs}ms")
        job
    }
}