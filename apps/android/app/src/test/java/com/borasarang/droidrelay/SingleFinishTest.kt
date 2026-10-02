package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.SingleFinish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `goAsync()` 중복 `finish()` 크래시 회귀 테스트 (T-1088).
 *
 * ## 실제로 난 것 (2026-09-30 실측)
 * ```
 * FATAL EXCEPTION: queued-work-looper
 * java.lang.IllegalStateException: Broadcast already finished
 *     at BroadcastReceiver$PendingResult.sendFinished(BroadcastReceiver.java:313)
 * ```
 *
 * `BootReceiver` 가 `finally` 에서 `finish()` 를 부르면서 `job.invokeOnCompletion` 에
 * "백업"으로 하나 더 붙였다. `invokeOnCompletion` 은 `finally` **이후에** 발화하므로
 * **정상 경로에서 두 번 다 실행**되어 크래시했다.
 *
 * ## 왜 이 테스트가 필요한가
 *
 * `runCatching` 으로 감싸면 **잡히지 않는다.** 예외가 감싼 블록이 아니라
 * `queued-work-looper` 스레드에서 나중에 asynchronously 터지기 때문이다.
 * → 중복 호출이 **구조적으로 불가능**하게 만들어야 하고, 그 구조가 이 테스트다.
 *
 * ## "몇 번 실제로 실행됐나" 를 세는 이유
 *
 * Boolean 만 보면 `true` 가 두 번 나오는 것과 한 번 나오는 것을 구분하지 못한다.
 * **호출 횟수** 를 세야 같은 실수가 다시 들어와도 잡힌다.
 */
class SingleFinishTest {

    @Test
    fun `한 번만 통과시킨다`() {
        var count = 0
        val once = SingleFinish { count++ }
        once.finish()
        assertEquals("최초 1회는 반드시 실행", 1, count)
    }

    /**
     * ★ 이게 원래 크래시였다 — `finally` 와 `invokeOnCompletion` 이 **둘 다** 실행.
     * 두 경로가 있어도 실제 종료는 **한 번**이어야 한다.
     */
    @Test
    fun `두 경로에서 불러도 실제 종료는 한 번이다`() {
        var count = 0
        val once = SingleFinish { count++ }
        once.finish()   // finally
        once.finish()   // invokeOnCompletion (제거됐지만 같은 실수를 다시 하지 않도록 고정)
        assertEquals("중복 경로가 있어도 finish 는 정확히 1회", 1, count)
    }

    @Test
    fun `세 번 불러도 한 번이다`() {
        var count = 0
        val once = SingleFinish { count++ }
        repeat(3) { once.finish() }
        assertEquals(1, count)
    }

    /** 중복 호출은 **조용히 무시**되어야 한다 — 예외를 던지면 크래시가 되므로. */
    @Test
    fun `두 번째 호출은 false 를 돌려주고 예외를 던지지 않는다`() {
        val once = SingleFinish { }
        assertTrue("최초 호출은 실제 수행", once.finish())
        assertFalse("중복 호출은 수행하지 않음", once.finish())
        assertFalse(once.finish())
    }

    /**
     * ★ **동시 호출** — 실제 크래시는 두 경로가 **같은 스레드에서 순차** 실행돼서 났지만,
     * `compareAndSet` 을 쓴 이유를 고정한다. 두 경로가 겹치면 여기서 잡힌다.
     */
    @Test
    fun `동시에 여러 번 불러도 한 번만 수행된다`() {
        val threads = 8
        val perThread = 200
        var count = 0
        val once = SingleFinish { count++ }
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.submit {
                start.await()
                repeat(perThread) { once.finish() }
                done.countDown()
            }
        }
        start.countDown()                       // 전 스레드 동시 출발
        assertTrue("스레드 완료 대기", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals("${threads * perThread}회 호출해도 1회", 1, count)
    }

    /** 여러 경로에서 동시에 불러도 **성공 횟수**는 1 — "실제로 수행된 것"의 기준. */
    @Test
    fun `동시 호출에서 성공 횟수는 정확히 1이다`() {
        val threads = 8
        val ok = AtomicInteger(0)
        val once = SingleFinish { }
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.submit {
                start.await()
                repeat(100) { if (once.finish()) ok.incrementAndGet() }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals("실제 수행은 정확히 1회", 1, ok.get())
    }
}
