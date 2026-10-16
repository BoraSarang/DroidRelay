package com.borasarang.droidrelay.relay

/// **대시보드 HTML** — 조각 셋을 이어 붙인다.
///
/// ## 왜 쪼갰나
///
/// 3,041줄짜리 단일 원시 문자열이었다. CSS(`WebDashboardHead`)·마크업
/// (`WebDashboardBody`)·JS(`WebDashboardScript`) 가 한 덩어리라, 3,000줄 안에서
/// `grep` 으로 위치를 찾아야 했다.
///
/// ## 계약
///
/// `WebAssets.dashboardHtml` 은 **공개 계약**이다 — 서버가 이걸 서빙하고
/// JVM 단위 테스트 6파일이 문자열을 검사한다. 그래서 `assets/` 로 옮기지 않았고
/// (테스트는 Android 프레임워크 없이 읽는다), **출력은 원본과 바이트 동일**하다.
internal object WebDashboardHtml {
    val content: String by lazy {
        WebDashboardHead.content + "\n" + WebDashboardBody.content + "\n" + WebDashboardScript.content
    }
}
