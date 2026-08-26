package com.chad.triptime

import com.chad.triptime.data.AppConfig
import com.chad.triptime.viewmodel.noticeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The remote-config notice is the one part of D-020 a user actually reads, and it is decided by
 * pure logic — so it is worth pinning down here rather than only observing it on a device.
 */
class NoticeTest {

    private fun config(latest: String? = null, message: String? = null) =
        AppConfig.COMPILED_IN.copy(latestVersion = latest, message = message)

    @Test
    fun `no message and no version means nothing to say`() {
        assertNull(noticeFor(config(), currentVersion = "1.3"))
    }

    @Test
    fun `an explicit message wins over a version nudge`() {
        val notice = noticeFor(config(latest = "9.9", message = "Service is down."), "1.3")
        assertEquals("Service is down.", notice)
    }

    @Test
    fun `a newer published version is offered`() {
        assertEquals(
            "TripTime 1.4 is available. This copy is 1.3.",
            noticeFor(config(latest = "1.4"), currentVersion = "1.3"),
        )
    }

    @Test
    fun `the same version says nothing`() {
        assertNull(noticeFor(config(latest = "1.3"), currentVersion = "1.3"))
    }

    /** A local build ahead of what is published must never be told to downgrade itself. */
    @Test
    fun `an older published version says nothing`() {
        assertNull(noticeFor(config(latest = "1.2"), currentVersion = "1.3"))
    }

    /** 1.10 is newer than 1.9 numerically, though a string compare would disagree. */
    @Test
    fun `version parts compare numerically not alphabetically`() {
        assertEquals(
            "TripTime 1.10 is available. This copy is 1.9.",
            noticeFor(config(latest = "1.10"), currentVersion = "1.9"),
        )
    }

    @Test
    fun `an unparseable version is ignored rather than guessed at`() {
        assertNull(noticeFor(config(latest = "next-beta"), currentVersion = "1.3"))
    }

    @Test
    fun `a blank message falls through to the version check`() {
        assertNull(noticeFor(config(latest = "1.3", message = "  "), currentVersion = "1.3"))
    }
}
