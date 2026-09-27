package com.borasarang.droidrelay.relay

/**
 * 보관함 이동 충돌 판정 (v0.41, T-1072).
 *
 * **왜 필요한가**
 * `/api/storage/move` 는 이동을 `File.renameTo()` 로 수행한다. 이것은 POSIX `rename(2)` 래퍼라
 * **대상 파일이 이미 있어도 실패 없이 조용히 그 파일을 대체한다.** 웹 보관함에서 파일을
 * 드래그해同名 파일이 있는 폴더에 놓으면, 사용자에게 아무 확인 없이 기존 파일이 사라졌다.
 * `copyTo(overwrite = true)` 폴백 경로도 마찬가지였다.
 *
 * 같은 저장소의 다른 경로는 이미 가드가 있다 — 이 라우트만 유일하게 무방어였다.
 * | 경로 | 충돌 처리 |
 * |---|---|
 * | `POST /api/storage/rename` | `toFile.exists()` → `"대상 이미 존재"` 로 거부 |
 * | `POST /api/storage/delete` (휴지통) | 접미사 `-2`, `-3` … 자동 부여 |
 * | `POST /api/storage/trash/restore` | 접미사 `-2`, `-3` … 자동 부여 |
 * | `POST /api/storage/move` | **없음** → 조용히 덮어씀 (v0.41 수정) |
 *
 * **계약**
 * `overwrite=true` 가 명시적으로 들어온 경우에만 덮어쓴다. 그 외에는 충돌을 보고하고,
 * 호출측(웹 UI)이 사용자에게 확인받은 뒤 `overwrite=true` 로 재요청하게 한다.
 * 확인 없이 데이터가 사라지는 경로를 만들지 않는 것이 이 객체의 목적이다.
 */
internal object StorageMove {

    sealed interface Decision {
        /** 대상 없음 또는 덮어쓰기 승인 → 그대로 진행 */
        object Proceed : Decision

        /** 대상 있음 + 덮어쓰기 미승인 → 사용자 확인 필요 */
        data class Conflict(
            val name: String,
            val isDir: Boolean,
            val size: Long,
            val modified: Long,
        ) : Decision
    }

    /**
     * 충돌 판정. [dstExists] 는 대상 경로 존재 여부, 나머지 값은 보고용 메타다.
     * 판정 자체는 [dstExists] 와 [overwrite] 만으로 결정된다.
     */
    fun decide(
        srcName: String,
        dstExists: Boolean,
        dstIsDir: Boolean = false,
        dstSize: Long = 0L,
        dstModified: Long = 0L,
        overwrite: Boolean = false,
    ): Decision = if (overwrite || !dstExists) {
        Decision.Proceed
    } else {
        Decision.Conflict(srcName, dstIsDir, dstSize, dstModified)
    }

    /**
     * 충돌 응답 필드. 라우트가 `JSONObject(...)` 로 감싸 JSON 으로 직렬화한다.
     * 판정 로직이 전송 형식에 의존하지 않도록 Map 로 돌려준다 — 단위 테스트에서
     * org.json 스텁 없이 검증된다.
     *
     * `conflict:true` 가 구분자다 — 다른 오류와 달리 "확인 후 재요청 가능" 이라는
     * 뜻이라 클라이언트가 UI 를 바꿔야 하기 때문.
     */
    fun conflictFields(d: Decision.Conflict): Map<String, Any> = mapOf(
        "error" to "대상 위치에 같은 이름이 이미 있습니다",
        "conflict" to true,
        "name" to d.name,
        "isDir" to d.isDir,
        "size" to d.size,
        "modified" to d.modified,
    )
}
