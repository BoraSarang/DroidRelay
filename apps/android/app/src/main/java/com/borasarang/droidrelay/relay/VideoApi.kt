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

    fun isYouTube(url: String): Boolean =
        url.contains("youtube.com") || url.contains("youtu.be")

    /** 분석 — 유튜브: yt-dlp 서버 / 스트림: StreamDetector. 실패 시 VideoException. */
    suspend fun analyze(settings: AppSettings, url: String): JSONObject = withContext(Dispatchers.IO) {
        DebugLogger.i(TAG, "[FEATURE] 분석 시작 url=${url.take(90)}")
        if (isYouTube(url)) analyzeYouTube(settings, url) else analyzeStream(url)
    }

    private fun analyzeYouTube(settings: AppSettings, url: String): JSONObject {
        requireYtDlp(settings)
        return try {
            YtDlpClient.analyze(settings.ytdlpServerUrl, settings.ytdlpApiKey, url)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "유튜브 분석 실패: ${e.message}")
            throw VideoException("E-AND-VID-0102", "YouTube 분석 실패: ${e.message}")
        }
    }

    private fun analyzeStream(url: String): JSONObject {
        val d = StreamDetector.analyze(url)
        return JSONObject().apply {
            put("kind", "stream")
            put("title", d.title)
            put("streamUrl", d.url)
            put("direct", d.isDirect)
        }
    }

    /** 비디오 잡 생성 + 즉시 FFmpeg 시작. 실패 시 VideoException. */
    suspend fun create(
        context: Context,
        settings: AppSettings,
        url: String,
        streamUrl: String?,
        formatId: String?,
        filename: String?,
    ): Job = withContext(Dispatchers.IO) {
        val vm = RelayApp.getVideo(context)
        val job = if (isYouTube(url)) {
            requireYtDlp(settings)
            val fmt = formatId?.ifBlank { null } ?: "bestvideo+bestaudio/best"
            val downloadUrls = YtDlpClient.getDownloadUrls(settings.ytdlpServerUrl, settings.ytdlpApiKey, url, fmt)
                .map { YtDlpClient.proxyUrl(settings.ytdlpServerUrl, settings.ytdlpApiKey, it) }
            val outName = VideoDownloadManager.safeFilename(filename, "mp4")
            val argv = buildList {
                add("-y"); add("-nostdin"); add("-hide_banner")
                downloadUrls.forEach { add("-i"); add(it) }
                add("-c"); add("copy"); add("-movflags"); add("+faststart")
                add(File(vm.workDir, outName).absolutePath)
            }
            vm.createAndStart(url, outName, argv)
        } else {
            val found = StreamDetector.analyze(url)
            val stream = streamUrl?.ifBlank { found.url } ?: found.url
            val outName = VideoDownloadManager.safeFilename(filename, "mp4")
            val argv = listOf(
                "-i", stream, "-c", "copy", "-movflags", "+faststart",
                File(vm.workDir, outName).absolutePath,
            )
            vm.createAndStart(url, outName, argv)
        }
        DebugLogger.i(TAG, "[FEATURE] 비디오 잡 생성 id=${job.id} '${job.filename}'")
        job
    }

    private fun requireYtDlp(settings: AppSettings) {
        if (!settings.ytdlpEnabled || settings.ytdlpServerUrl.isBlank()) {
            throw VideoException("E-AND-VID-0101", "YouTube는 yt-dlp 서버가 필요합니다. 설정에서 서버를 구성해 주세요.")
        }
    }
}