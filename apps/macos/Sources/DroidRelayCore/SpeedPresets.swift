import Foundation

/// **속도 제한 프리셋 — 웹 대시보드와 같은 값, 같은 라벨.**
///
/// ## 왜 이게 따로 있나
///
/// 서버가 받는 속도 제한 단위는 **B/s** 다. 웹은 `SPEED_PRESETS`(KB/s 목록)을
/// 고른 뒤 `k * 1024` 로 B/s 를 만들어 보낸다.
///
/// 이전 클라이언트는 **초당 바이트 숫자를 직접 입력**하게 했다.
/// → 사용자는 "1048576" 이 무슨 값인지 모르고 256KB/s 를 고르려면
/// `256 * 1024` 를 손으로 계산해야 했다. 사실상 못 쓰는 기능이었다.
///
/// 프리셋으로 바꾸면 "1MB/s" 를 고르는 한 번의 클릭이 된다.
/// **웹이 이미 Provide 하는 것을 클라이언트가 또 다르게 하면 그건 버그다.**
public enum SpeedPresets {

    /// 웹 965행 `SPEED_PRESETS` — **단위는 KB/s.**
    ///
    /// 값이 웹과 한 글자도 달라지면 안 된다. 같은 목록이어야 사용자가
    /// "웹에서 고른 1MB/s" 를 앱에서도 그대로 찾을 수 있다.
    public static let kbpsList: [Int] = [0, 256, 512, 1024, 2048, 5120, 10240, 20480]

    /// 웹 966행 `kbpsName(k)` — **정수 나눗셈**이 핵심이다.
    ///
    /// ```javascript
    /// k<=0 ? '무제한' : (k<1024 ? k+'KB/s' : (k/1024)+'MB/s')
    /// ```
    ///
    /// `1024/1024` 이 JS 정수 나눗셈이라 `1MB/s` 다. Swift 정수 나눗셈도
    /// 버림이므로 같은 결과가 난다(양수 한정).
    public static func name(_ kbps: Int) -> String {
        if kbps <= 0 { return "무제한" }
        if kbps < 1024 { return "\(kbps)KB/s" }
        return "\(kbps / 1024)MB/s"
    }

    /// 웹 979행 `kbps = cur>0 ? Math.ceil(cur/1024) : 0`
    ///
    /// **올림(`ceil`)이 아니라 버림하면 안 된다.** 1025 B/s 는 1KB/s 로
    /// 표시되어야 하는데, 실수로 1024KB/s 로 보이면 사용자가 제한을 두 배로
    /// 설정했다고 오해한다. 웹의 올림을 그대로 쓴다.
    public static func kbps(fromBps bps: Int) -> Int {
        bps > 0 ? Int(ceil(Double(bps) / 1024.0)) : 0
    }

    /// 웹 980~981행: 프리셋 목록 + **현재 값이 목록에 없으면 끼워 넣는다.**
    ///
    /// 이게 없으면 "서버에 300KB/s 제한이 걸려 있는데 선택기가 512KB/s 를
    ///.selected로 보여준다" → 사용자는 제한이 풀린 걸로 오해한다.
    /// **현재 상태를 정확히 표시하는 것이 우선**이라 관례와 무관하게 추가한다.
    public static func options(currentBps: Int) -> [(kbps: Int, label: String)] {
        let cur = kbps(fromBps: currentBps)
        var list = kbpsList
        if !list.contains(cur) { list.append(cur) }
        return list.sorted().map { ($0, name($0)) }
    }

    /// KB/s → B/s. 웹의 `k * 1024` 와 같다.
    public static func bps(fromKbps kbps: Int) -> Int { kbps * 1024 }
}
