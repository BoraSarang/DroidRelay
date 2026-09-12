package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.JobTag
import com.borasarang.droidrelay.relay.ThrottleInterceptor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrottleChainTest {

    private fun fakeChain(bodyBytes: ByteArray): Interceptor.Chain {
        val req = Request.Builder()
            .url("https://t.local/big.bin")
            .tag(JobTag::class.java, JobTag("job1"))
            .build()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = bodyBytes.size.toLong()
            override fun source(): okio.BufferedSource {
                val buf = Buffer().apply { write(bodyBytes, 0, bodyBytes.size) }
                return (buf as okio.Source).buffer()
            }
        }
        val resp = Response.Builder()
            .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body)
            .build()
        return object : Interceptor.Chain {
            override fun request(): Request = req
            override fun proceed(request: Request): Response = resp
            override fun connection(): okhttp3.Connection? = null
            override fun call(): okhttp3.Call = OkHttpClient().newCall(req)
            override fun connectTimeoutMillis(): Int = 0
            override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
            override fun readTimeoutMillis(): Int = 0
            override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
            override fun writeTimeoutMillis(): Int = 0
            override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        }
    }

    @Test
    fun `작업 상한 체인은 대략 상한 속도로 읽힌다`() {
        val data = ByteArray(200 * 1024) { (it % 251).toByte() }
        val ic = ThrottleInterceptor(AtomicLong(0L), AtomicLong(0L)) { 100 * 1024L }
        val out = ic.intercept(fakeChain(data))
        val t0 = System.currentTimeMillis()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        out.body!!.byteStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                total += n
            }
        }
        val elapsed = System.currentTimeMillis() - t0
        // 200KB @100KB/s → 약 2초. 2배速(≈1초) 버그와 무제한(≈0초)을 구분
        assertTrue("elapsed=${elapsed}ms total=$total", elapsed >= 1500 && elapsed <= 4000)
    }
}
