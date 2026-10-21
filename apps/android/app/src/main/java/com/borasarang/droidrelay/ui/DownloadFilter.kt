package com.borasarang.droidrelay.ui

import com.borasarang.droidrelay.relay.Job
import com.borasarang.droidrelay.relay.JobState

/**
 * 받기 탭 필터 칩 (v0.59 2차) — 목업 `android.html` 의 `전체/받는 중/대기/완료`.
 *
 * `PAUSED` 는 사용자가 멈춘 상태라 "대기"로 묶는다(재개 대기). `FAILED`/`CANCELED` 는
 * 조용히 사라지면 안 되므로 별도 칩([FAILED])을 조건부로 노출한다.
 */
enum class DownloadFilter(val label: String) {
    ALL("전체"),
    ACTIVE("받는 중"),
    QUEUED("대기"),
    DONE("완료"),
    FAILED("실패"),
    ;

    fun matches(job: Job): Boolean = when (this) {
        ALL -> true
        ACTIVE -> job.state == JobState.RUNNING
        QUEUED -> job.state == JobState.QUEUED || job.state == JobState.PAUSED
        DONE -> job.state == JobState.DONE
        FAILED -> job.state == JobState.FAILED || job.state == JobState.CANCELED
    }
}

/** 칩 계산은 순수 함수로 분리 — Compose 없이 단위 테스트한다. */
object DownloadFilterCalc {
    /** 기본 4칩 + 실패가 있을 때만 실패 칩. */
    fun chips(jobs: List<Job>): List<DownloadFilter> =
        if (jobs.any { DownloadFilter.FAILED.matches(it) }) {
            listOf(DownloadFilter.ALL, DownloadFilter.ACTIVE, DownloadFilter.QUEUED, DownloadFilter.DONE, DownloadFilter.FAILED)
        } else {
            listOf(DownloadFilter.ALL, DownloadFilter.ACTIVE, DownloadFilter.QUEUED, DownloadFilter.DONE)
        }

    fun count(jobs: List<Job>, filter: DownloadFilter): Int = jobs.count(filter::matches)

    fun apply(jobs: List<Job>, filter: DownloadFilter): List<Job> = jobs.filter(filter::matches)
}
