package com.borasarang.droidrelay.ui

/**
 * 홈 처리량 스파크라인의 고정 길이 표본 버퍼 (v0.59 2차).
 *
 * 1초 간격으로 [push] 하고, 최근 [capacity] 개만 유지한다. 마지막 값이 항상 최신이며
 * UI 는 [downPoints]/[upPoints] 만 읽는다(Compose 상태는 호출부가 tick 으로 유도).
 */
class SpeedSeries(val capacity: Int = 60) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val down = ArrayDeque<Long>(capacity)
    private val up = ArrayDeque<Long>(capacity)

    fun push(downBps: Long, upBps: Long) {
        if (down.size >= capacity) down.removeFirst()
        if (up.size >= capacity) up.removeFirst()
        down.addLast(downBps.coerceAtLeast(0L))
        up.addLast(upBps.coerceAtLeast(0L))
    }

    fun downPoints(): List<Long> = down.toList()

    fun upPoints(): List<Long> = up.toList()

    fun peak(): Long = maxOf(down.maxOrNull() ?: 0L, up.maxOrNull() ?: 0L)

    fun size(): Int = down.size
}
