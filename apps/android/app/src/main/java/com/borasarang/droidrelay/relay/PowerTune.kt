package com.borasarang.droidrelay.relay

import java.io.File

/**
 * 저전력 실행 튜닝 — S22(스냅 8 Gen 1) 장시간 서버 운용용.
 *
 * 다운로드류 작업은 네트워크 대기(I/O 바운드)가 대부분이라 prime(X2)·big(A710)를
 * 깨울 필요가 없다. 저전력 모드가 켜지면 워커 스레드를 백그라운드 우선순위로 낮추고
 * 가능하면 little 클러스터 마스크로 묶어 X2 기상을 줄인다.
 *
 * affinity 고정은 best-effort다: `sched_setaffinity`를 리플렉션으로 시도하고 실패하면
 * 우선순위 강등만 적용된 채 계속된다(루트 불필요 — 자기 스레드 마스크 축소는 허용됨).
 * JVM 단위 테스트에서도 로드 가능하도록 안드로이드 API는 전부 리플렉션 경유한다.
 */
object PowerTune {
    /** S22 기준 little 4코어 마스크(cpu0-3) — sysfs 판독 실패 시 폴백 */
    const val LITTLE_MASK_FALLBACK = 0x0FL

    /** 저전력 시 little로 묶을 코어 수 */
    const val LITTLE_CORE_COUNT = 4

    @Volatile private var cachedMask: Long? = null

    /** 저전력 모드 유효 동시성 — 켜지면 1로 강제, 설정값 자체는 건드리지 않음 */
    fun effectiveConcurrency(concurrency: Int, lowPower: Boolean): Int =
        if (lowPower) 1 else concurrency

    /** 저전력 모드 유효 토렌트 활성 수 — 켜지면 최대 1로 강제 */
    fun effectiveTorrentMaxActive(maxActive: Int, lowPower: Boolean): Int =
        if (lowPower) minOf(maxActive, 1) else maxActive

    /**
     * 코어별 최대 클럭 맵에서 little 마스크 선택 — 클럭이 가장 낮은 N개를 little로 간주.
     * (스냅 8 Gen 1: A510 4 < A710 3 < X2 1)
     */
    fun selectLittleMask(maxFreqKHz: Map<Int, Long>, littleCount: Int = LITTLE_CORE_COUNT): Long {
        if (maxFreqKHz.isEmpty()) return LITTLE_MASK_FALLBACK
        if (maxFreqKHz.size <= littleCount) {
            var all = 0L
            maxFreqKHz.keys.forEach { all = all or (1L shl it) }
            return all
        }
        return maxFreqKHz.entries
            .sortedBy { it.value }
            .take(littleCount)
            .fold(0L) { acc, e -> acc or (1L shl e.key) }
    }

    /** sysfs에서 코어별 최대 클럭을 읽어 little 마스크 판정 (실패 시 폴백) */
    fun detectLittleMask(): Long {
        cachedMask?.let { return it }
        val mask = runCatching {
            val freqs = mutableMapOf<Int, Long>()
            File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
                ?.forEach { dir ->
                    val cpu = dir.name.removePrefix("cpu").toIntOrNull() ?: return@forEach
                    if (cpu >= 64) return@forEach
                    val khz = runCatching {
                        File(dir, "cpufreq/cpuinfo_max_freq_khz").readText().trim().toLong()
                    }.getOrNull() ?: return@forEach
                    freqs[cpu] = khz
                }
            selectLittleMask(freqs)
        }.getOrDefault(LITTLE_MASK_FALLBACK)
        cachedMask = mask
        return mask
    }

    /**
     * 현 스레드에 저전력 튜닝 적용 — 백그라운드 우선순위 + little affinity(best-effort).
     * @return affinity 고정까지 성공하면 true
     */
    fun applyToCurrentThread(lowPower: Boolean): Boolean {
        if (!lowPower) return false
        tryBackgroundPriority()
        return tryPinToLittle(detectLittleMask())
    }

    private fun tryBackgroundPriority() {
        runCatching {
            // android.os.Process.THREAD_PRIORITY_BACKGROUND(10) — 상수 직접 사용해 컴파일 의존 제거
            val process = Class.forName("android.os.Process")
            val tid = (process.getMethod("myTid").invoke(null) as Number).toInt()
            val setPriority = runCatching {
                process.getMethod("setThreadPriority", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            }.getOrNull()
            if (setPriority != null) {
                setPriority.invoke(null, tid, 10)
            } else {
                process.getMethod("setThreadPriority", Int::class.javaPrimitiveType).invoke(null, 10)
            }
        }
    }

    private fun tryPinToLittle(mask: Long): Boolean {
        return runCatching {
            val tid = (Class.forName("android.os.Process").getMethod("myTid").invoke(null) as Number).toInt()
            // 1) android.system.Os 정적 메서드 시도
            val osStatic = runCatching {
                val os = Class.forName("android.system.Os")
                os.getMethod("schedSetAffinity", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            }.getOrNull()
            if (osStatic != null) {
                osStatic.invoke(null, tid, mask)
                return@runCatching true
            }
            // 2) libcore.io.Libcore.os 인스턴스 경유 시도
            val libcore = Class.forName("libcore.io.Libcore")
            val os = libcore.getField("os").get(null) ?: return@runCatching false
            val m = os.javaClass.getMethod("schedSetAffinity", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            m.invoke(os, tid, mask)
            true
        }.getOrDefault(false)
    }
}
