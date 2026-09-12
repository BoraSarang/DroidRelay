package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.TrackerListProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackerListTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `http udp만 채택하고 주석 빈줄 제외`() {
        val text = "# comment\n\nudp://a:1337/announce\nhttps://b/announce\nhttp://c/x\nftp://d/y\nmagnet:?x\n"
        assertEquals(
            listOf("udp://a:1337/announce", "https://b/announce", "http://c/x"),
            TrackerListProvider.parseList(text),
        )
    }

    @Test
    fun `중복 제거와 상한`() {
        val text = (1..100).joinToString("\n") { "udp://t$it:80/a" } + "\nudp://t1:80/a"
        val list = TrackerListProvider.parseList(text)
        assertEquals(TrackerListProvider.MAX_TRACKERS, list.size)
        assertEquals(1, list.count { it == "udp://t1:80/a" })
    }

    @Test
    fun `공백 포함 줄은 제외`() {
        val list = TrackerListProvider.parseList("udp://a:80/a b\nudp://ok:80/x\n")
        assertEquals(listOf("udp://ok:80/x"), list)
    }

    @Test
    fun `캐시 없으면 번들`() {
        val f = java.io.File(tmp.root, "trackers.txt")
        assertEquals(TrackerListProvider.DEFAULT_TRACKERS, TrackerListProvider.getCached(f))
    }

    @Test
    fun `유효 캐시는 그대로 빈 캐시는 번들`() {
        val f = tmp.newFile("trackers.txt")
        f.writeText("udp://x:80/a\n\n# c\nhttps://y/b\n")
        assertEquals(listOf("udp://x:80/a", "https://y/b"), TrackerListProvider.getCached(f))
        val empty = tmp.newFile("empty.txt")
        empty.writeText("# nothing\n")
        assertEquals(TrackerListProvider.DEFAULT_TRACKERS, TrackerListProvider.getCached(empty))
    }
}
