// ScheduledTurnTest.kt: what may be scheduled, and what is read back.
//
// History
//   2026-09-17  A. Sigdel  Created, the phone half of #600.
//
// On the JVM, which is why `refusing`, `rowsFrom` and `answeredFrom` are
// separate functions. Everything else in the scheduled-turn store is
// SharedPreferences and belongs on a device, the split Connections already
// makes for the same reason: the decision with a bug in it should not be the
// one that only runs on an emulator.

package com.getlora.wattrouter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTurnTest {
    private val now = 1_800_000_000_000L

    @Test
    fun anOrdinaryScheduleIsAccepted() {
        assertNull(refusing(now + 60_000, "check the build", now))
    }

    @Test
    fun aTimeInThePastIsRefusedWithTheReasonSayingWhy() {
        // Not pedantry about ordering. WorkManager clamps a delay past zero,
        // so a past time would run the moment it was saved, which is not what
        // "tomorrow morning" means to anybody who typed it.
        val why = refusing(now - 1, "check the build", now)

        assertNotNull(why)
        assertTrue(why!!, why.contains("still ahead"))
    }

    @Test
    fun thePresentItselfIsRefused() {
        // A clock that reads exactly now is a time that is not still ahead by
        // the time the work is asked for.
        assertNotNull(refusing(now, "check the build", now))
    }

    @Test
    fun aBlankPromptIsRefused() {
        // The prompt is the whole of what the model knows about why it was
        // woken. Blank, it wakes with nothing to do and somebody else's 3am
        // budget is spent finding that out.
        val why = refusing(now + 60_000, "", now)

        assertNotNull(why)
        assertTrue(why!!, why.contains("Say what the turn is for"))
    }

    @Test
    fun whitespaceIsBlank() {
        assertNotNull(refusing(now + 60_000, "   ", now))
    }

    @Test
    fun aRoundTripKeepsEveryField() {
        val rows = listOf(
            Scheduled(1, now, "check the build", answered = "it passed"),
            Scheduled(2, now + 1, "water the plants", failed = "the turn ended without an answer"),
            Scheduled(3, now + 2, "not yet run"),
        )

        assertEquals(rows, rowsFrom(toJson(rows)))
    }

    @Test
    fun nullReadsAsEmpty() {
        assertEquals(emptyList<Scheduled>(), rowsFrom(null))
    }

    @Test
    fun anythingThatWillNotParseReadsAsEmpty() {
        // The rule Connections.all states: a file a later build or an editor
        // mangled should leave the phone working, not crash the chat open.
        assertEquals(emptyList<Scheduled>(), rowsFrom("["))
        assertEquals(emptyList<Scheduled>(), rowsFrom("{\"id\": 1}"))
        assertEquals(emptyList<Scheduled>(), rowsFrom("[]{"))
    }

    @Test
    fun aRowMissingItsIdentityIsDropped() {
        // A defaulted id would be a row that mark() overwrites another with,
        // which is why it is dropped rather than defaulted.
        val saved = """[{"at": $now, "what": "check the build"}]"""

        assertEquals(emptyList<Scheduled>(), rowsFrom(saved))
    }
}
