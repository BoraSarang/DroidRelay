package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.PersistenceGuard
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistenceGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `손상 파일은 bak으로 보존`() {
        val f = tmp.newFile("jobs.json")
        f.writeText("corrupt{{{")
        val bak = PersistenceGuard.backupCorrupt(f)
        assertNotNull(bak)
        assertTrue(bak!!.exists())
        assertEquals("corrupt{{{", bak.readText())
        assertTrue(f.exists())
    }

    @Test
    fun `없는 파일은 null`() {
        val f = File(tmp.root, "nope.json")
        assertNull(PersistenceGuard.backupCorrupt(f))
    }

    @Test
    fun `스키마 버전 상수`() {
        assertEquals(1, PersistenceGuard.JOBS_SCHEMA_VERSION)
        assertEquals(1, PersistenceGuard.TORRENTS_SCHEMA_VERSION)
    }

    @Test
    fun `백업은 덮어쓰기 멱등`() {
        val f = tmp.newFile("torrents.json")
        f.writeText("a")
        PersistenceGuard.backupCorrupt(f)
        f.writeText("b")
        val bak2 = PersistenceGuard.backupCorrupt(f)
        assertEquals("b", bak2!!.readText())
    }
}
