package com.borasarang.droidrelay.relay

/**
 * Netty 할당기 조정 — A90 대용량 서빙 lmkd kill 대응 (v0.5x).
 *
 * 실측: 32MB 슬라이스 1회 서빙마다 Dalvik Other(추적外 직접 버퍼)가 약 1배씩 누적돼
 * 수십 회 만에 RSS 수 GB → lmkd kill. 풀드 직접 아레나는 GC와 무관하게 고수위를 유지하고
 * 미해제 버퍼의 Cleaner는 힙 압박이 없어 돌지 않으므로, 파일 서빙처럼 대용량·저속 전송이
 * 섞이면 프로세스가 죽는다. 힙(unpooled+heap 선호)으로 바꾸면 같은 누락이 생겨도
 * 일반 GC가 힙 압박에 반응해 회수하므로 생존한다. 처리량은 근소하게 줄 수 있으나
 * 릴레이 서버의 병목은 Wi-Fi라 체감 없다.
 *
 * Netty 클래스가 처음 로드되기 전에 적용해야 해서 MainActivity/RelayService의
 * onCreate 선두에서 호출한다 (서버 기동보다 항상 먼저 실행된다).
 */
internal object NettyTuning {
    @Volatile private var applied = false

    fun apply() {
        if (applied) return
        synchronized(this) {
            if (applied) return
            // pooled → unpooled: 아레나 고수위 유지 없음, 해제 즉시 반환
            System.setProperty("io.netty.allocator.type", "unpooled")
            // 직접 버퍼보다 힙 버퍼 선호 — GC 회수 가능 영역으로 이동
            System.setProperty("io.netty.noPreferDirect", "true")
            applied = true
            DebugLogger.i("Server", "Netty 할당기 조정 적용 (unpooled+heap)")
        }
    }
}
