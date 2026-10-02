package com.borasarang.droidrelay.relay

import java.util.concurrent.atomic.AtomicBoolean

/**
 * `goAsync()` 의 `PendingResult.finish()` 는 **정확히 한 번만** 부를 수 있다. (T-1088)
 *
 * ## 왜 이 클래스가 있나
 *
 * 2026-09-30 실측 크래시:
 * ```
 * FATAL EXCEPTION: queued-work-looper
 * java.lang.IllegalStateException: Broadcast already finished
 *     at BroadcastReceiver$PendingResult.sendFinished(BroadcastReceiver.java:313)
 * ```
 *
 * `BootReceiver` 는 `finally` 에서 `finish()` 를 부르면서
 * "혹시 코루틴이 못 끝나면" 이라는 이유로 `job.invokeOnCompletion { finish() }` 를 **더 붙였다.**
 * 하지만 `invokeOnCompletion` 은 `finally` **이후에** 발화하므로 정상 경로에서 **둘 다 실행**된다.
 * 백업이 아니라 **중복 호출**이었다.
 *
 * ## 왜 `runCatching` 으로 감싸도 안 됐나
 *
 * 예외는 감싼 블록 안이 아니라 **`queued-work-looper` 스레드에서** 나중에 asynchronously 터진다.
 * `runCatching` 은 그 예외를 잡지 못한다. → **중복 호출 자체를 막아야 한다.**
 *
 * ## 왜 이 형태로 만들었나
 *
 * `PendingResult` 를 직접 받는 대신 **종료 동작을 람다로 받는다.**
 * 그래야 JVM 단위 테스트(Robolectric 미사용)에서 **실제 몇 번 실행됐는지** 셀 수 있다.
 * 중복 호출을 막는 책임이 `BootReceiver` 안에 흩어지지 않고 **여기 한 곳**에 있다.
 *
 * @param onFinish 실제 1회만 실행될 동작. 내부에서 `finish()` 를 부른다.
 */
internal class SingleFinish(private val onFinish: () -> Unit) {

    private val done = AtomicBoolean(false)

    /**
     * **최초 1회만** [onFinish] 를 실행한다.
     *
     * @return 실제 실행했으면 `true`, 이미 실행되어 **무시했으면** `false`.
     *         중복 호출은 **조용히 무시**된다 — 예외를 던지면 그게 바로 크래시다.
     */
    fun finish(): Boolean {
        if (!done.compareAndSet(false, true)) return false
        onFinish()
        return true
    }
}
