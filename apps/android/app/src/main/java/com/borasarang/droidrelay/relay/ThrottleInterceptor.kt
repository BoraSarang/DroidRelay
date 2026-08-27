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
 * - AtomicLong으로 동적 변경 즉시 반영
 */

class ThrottleInterceptor(
    private val maxDownloadBps: AtomicLong,
    private val maxUploadBps: AtomicLong,
) : Interceptor {

    private val downloadBucket = TokenBucket()
    private val uploadBucket = TokenBucket()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val body = response.body
        if (body == null) return response

        val isDownload = request.method == "GET"
        val bucket = if (isDownload) downloadBucket else uploadBucket
        val limit = if (isDownload) maxDownloadBps else maxUploadBps

        if (limit.get() <= 0) return response // 무제한

        val throttledBody = ThrottledResponseBody(body, bucket, limit)
        return response.newBuilder()
            .body(throttledBody)
            .build()
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
            // 토큰 부족 → 대기 시간 계산 (ms)
            val deficit = bytes - available
            val ms = (deficit * 1000L / max(1, limitForRefill)).toLong().coerceAtLeast(1)
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
    }

    /**
     * 쓰로틀링된 ResponseBody — source()에서 쓰로틀링된 BufferedSource 반환
     */
    @Suppress("DEPRECATION")
    private class ThrottledResponseBody(
        private val delegate: ResponseBody,
        private val bucket: TokenBucket,
        private val limit: AtomicLong,
    ) : ResponseBody() {

        override fun contentType(): MediaType? = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        @Suppress("DEPRECATION")
        override fun source(): BufferedSource {
            val bufferedSource = delegate.source().buffer()
            val throttledSource = ThrottledSource(bufferedSource, bucket, limit)
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
        delegate: BufferedSource,
        private val bucket: TokenBucket,
        private val limit: AtomicLong,
    ) : ForwardingSource(delegate) {

        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = delegate.read(sink, byteCount)
            if (n == -1L) return -1L
            val currentLimit = limit.get()
            if (currentLimit > 0) {
                bucket.setLimit(currentLimit)
                val sleepMs = bucket.take(n)
                if (sleepMs > 0) {
                    try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                }
            }
            return n
        }
    }
}