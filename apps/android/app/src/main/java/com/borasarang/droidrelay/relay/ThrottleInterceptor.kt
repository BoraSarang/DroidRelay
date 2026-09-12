@file:Suppress("DEPRECATION")
package com.borasarang.droidrelay.relay

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Okio
import okio.Source
import okio.buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * 토큰 버킷 기반 다운로드/업로드 쓰로틀 인터셉터
 * - 전역 maxDownloadBps / maxUploadBps (BPS 단위, 0 = 무제한)
 * - 작업별 상한 taskLimit(jobId) (B/s, 0 = 무제한) — v0.25, 다운로드(GET)만
 * - 유효 상한 = 양수 중 최소 (둘 다 0이면 무제한)
 * - AtomicLong으로 동적 변경 즉시 반영
 */

/** 요청 태그 — 작업별 상한 조회용 (헤더가 아니라 서버로 전송되지 않음) */
data class JobTag(val id: String)

class ThrottleInterceptor(
    private val maxDownloadBps: AtomicLong,
    private val maxUploadBps: AtomicLong,
    private val taskLimit: (String) -> Long = { 0L },
) : Interceptor {

    private val downloadBucket = TokenBucket()
    private val uploadBucket = TokenBucket()
    private val taskBuckets = java.util.concurrent.ConcurrentHashMap<String, TokenBucket>()

    companion object {
        /** 유효 상한 — 둘 다 0이면 무제한(0), 아니면 양수 중 최소. 순수 함수. */
        fun effectiveLimit(globalBps: Long, taskBps: Long): Long = when {
            globalBps <= 0 -> taskBps.coerceAtLeast(0)
            taskBps <= 0 -> globalBps.coerceAtLeast(0)
            else -> minOf(globalBps, taskBps)
        }
    }

    /** 작업 종료 시 버킷 정리 (누수 방지) — FAILED/PAUSED는 유지(재개 시 재사용) */
    fun forget(id: String) {
        taskBuckets.remove(id)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val body = response.body
        if (body == null) return response

        val isDownload = request.method == "GET"
        if (!isDownload) {
            if (maxUploadBps.get() <= 0) return response
            val throttledBody = ThrottledResponseBody(body, uploadBucket) { maxUploadBps.get() }
            return response.newBuilder().body(throttledBody).build()
        }

        val jobId = request.tag(JobTag::class.java)?.id
        // 항상 래핑 — 상한은 supplier로 live 조회하므로 도중 설정에도 즉시 적용된다.
        // (인터셉트 시점 판정 시 무제한으로 반환하면 나중 상한이 평생 미적용됨)
        var source: Source = body.source()
        if (jobId != null) {
            val bucket = taskBuckets.getOrPut(jobId) { TokenBucket() }
            source = ThrottledSource(source, bucket) { runCatching { taskLimit(jobId) }.getOrDefault(0L) }
        }
        source = ThrottledSource(source, downloadBucket) { maxDownloadBps.get() }
        val out = object : ResponseBody() {
            override fun contentType(): MediaType? = body.contentType()
            override fun contentLength(): Long = body.contentLength()
            override fun source(): BufferedSource = source.buffer()
            override fun close() = body.close()
        }
        return response.newBuilder().body(out).build()
    }

    /**
     * 토큰 버킷: 초당 limitBps 토큰 충전, 읽기 시 토큰 소모
     */
    private class TokenBucket {
        private val tokens = AtomicLong(0)
        private val lastRefill = AtomicLong(System.currentTimeMillis())
        private var limitForRefill: Long = 0

        fun take(bytes: Long): Long {
            refill()
            var available = tokens.get()
            if (available >= bytes) {
                tokens.addAndGet(-bytes)
                return 0L
            }
            // 토큰 부족 → 대기 시간 계산 (ms, 올림 — 절삭 시 고속 상한에서 systematic 초과)
            val deficit = bytes - available
            val denom = max(1, limitForRefill)
            val ms = ((deficit * 1000L + denom - 1) / denom).coerceAtLeast(1)
            tokens.set(0)
            return ms
        }

        private fun refill() {
            val now = System.currentTimeMillis()
            val last = lastRefill.getAndSet(now)
            val elapsedMs = now - last
            if (elapsedMs <= 0) return
            // limit은 호출 시점에 AtomicLong에서 읽음
            val added = (limitForRefill * elapsedMs / 1000L).coerceAtMost(limitForRefill * 2) // 버스트 허용 2배
            tokens.updateAndGet { (it + added).coerceAtMost(limitForRefill * 2) }
        }

        fun setLimit(limitBps: Long) {
            limitForRefill = limitBps
            // 버킷 크기도 즉시 조정
            tokens.updateAndGet { it.coerceAtMost(limitBps * 2) }
        }

        /** 수면한 시간은 토큰 적립에서 제외 — 미보정 시 수면분이 다음 refill에 적립되어 2배速이 된다 */
        fun accountSleep(ms: Long) {
            if (ms > 0) lastRefill.addAndGet(ms)
        }
    }

    /**
     * 쓰로틀링된 ResponseBody — source()에서 쓰로틀링된 BufferedSource 반환
     */
    @Suppress("DEPRECATION")
    private class ThrottledResponseBody(
        private val delegate: ResponseBody,
        private val bucket: TokenBucket,
        private val getLimit: () -> Long,
    ) : ResponseBody() {

        override fun contentType(): MediaType? = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        @Suppress("DEPRECATION")
        override fun source(): BufferedSource {
            val throttledSource = ThrottledSource(delegate.source(), bucket, getLimit)
            return throttledSource.buffer()
        }

        override fun close() {
            delegate.close()
        }
    }

    /**
     * 읽기 시 토큰 버킷에서 토큰 소모, 부족하면 스레드 대기
     */
    private class ThrottledSource(
        delegate: Source,
        private val bucket: TokenBucket,
        private val getLimit: () -> Long,
    ) : ForwardingSource(delegate) {

        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n == -1L) return -1L
            val currentLimit = getLimit()
            if (currentLimit > 0) {
                bucket.setLimit(currentLimit)
                val sleepMs = bucket.take(n)
                if (sleepMs > 0) {
                    try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    bucket.accountSleep(sleepMs)
                }
            }
            return n
        }
    }
}