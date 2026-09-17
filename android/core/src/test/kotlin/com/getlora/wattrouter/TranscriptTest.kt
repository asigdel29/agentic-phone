// TranscriptTest.kt: the fold, and what each rule stops looking wrong.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Every case here names the thing on screen that the naive fold gets wrong.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    private fun call(name: String = "recall") = TurnEvent.Call(ToolCall("c1", name, "{}"))
    private fun result(text: String) = TurnEvent.Result(ToolResult("c1", text))

    @Test
    fun fragmentsBecomeOneAnswerRatherThanOneBubbleEach() {
        val t = Transcript()
        listOf("the bins ", "go out ", "Tuesday").forEach { t.apply(TurnEvent.Text(it)) }

        assertEquals(1, t.rows.size)
        assertEquals("the bins go out Tuesday", (t.rows.single() as Row.Answered).text)
    }

    @Test
    fun aModelThatOnlyCallsToolsLeavesNoBlankBubble() {
        // Answering arrives before anything is said. Opening a row for it would
        // put an empty bubble with a model name on it into the conversation.
        val t = Transcript()
        t.apply(TurnEvent.Answering("mid", Backend.REMOTE))
        t.apply(call())

        assertEquals(1, t.rows.size)
        assertTrue(t.rows.single() is Row.Used)
    }

    @Test
    fun aModelThatAnnouncedItselfNamesTheAnswerItThenGives() {
        val t = Transcript()
        t.apply(TurnEvent.Answering("glm-5.2", Backend.REMOTE))
        t.apply(TurnEvent.Text("hello"))

        assertEquals("glm-5.2", (t.rows.single() as Row.Answered).model)
    }

    @Test
    fun answeringNamesAnAnswerAlreadyBeingWritten() {
        // The walk emits Answering first, but a retry can name a second model
        // after text has started arriving from the first.
        val t = Transcript()
        t.apply(TurnEvent.Text("half"))
        t.apply(TurnEvent.Answering("kimi-k3", Backend.REMOTE))

        assertEquals("kimi-k3", (t.rows.single() as Row.Answered).model)
    }

    @Test
    fun aToolIsVisibleWhileItRuns() {
        // The row appears with no result, so ten seconds of work is not ten
        // seconds of nothing on screen.
        val t = Transcript()
        t.apply(call("recall"))

        val row = t.rows.single() as Row.Used
        assertEquals("recall", row.tool)
        assertNull(row.result)
    }

    @Test
    fun aResultFillsTheCallItAnswers() {
        val t = Transcript()
        t.apply(call())
        t.apply(result("the bins go out Tuesday"))

        assertEquals("the bins go out Tuesday", (t.rows.single() as Row.Used).result)
    }

    @Test
    fun resultsFillCallsInTheOrderTheyWereMade() {
        // Valid because the Agent runs tools in order. Filling the last would
        // put the first tool's answer under the second tool's name.
        val t = Transcript()
        t.apply(TurnEvent.Call(ToolCall("c1", "first", "{}")))
        t.apply(TurnEvent.Call(ToolCall("c2", "second", "{}")))
        t.apply(TurnEvent.Result(ToolResult("c1", "one")))
        t.apply(TurnEvent.Result(ToolResult("c2", "two")))

        assertEquals(listOf("one", "two"), t.rows.filterIsInstance<Row.Used>().map { it.result })
    }

    @Test
    fun textAfterAToolStartsANewAnswer() {
        // Not a continuation of the paragraph before the tool ran: what the
        // model says next is about the result, and belongs below it.
        val t = Transcript()
        t.apply(TurnEvent.Text("looking"))
        t.apply(call())
        t.apply(result("found"))
        t.apply(TurnEvent.Text("Tuesday"))

        assertEquals(3, t.rows.size)
        assertEquals("Tuesday", (t.rows.last() as Row.Answered).text)
    }

    @Test
    fun aDecisionIsNotSomethingToRead() {
        // It feeds the routing panel. "chose mid" in the middle of a
        // conversation is noise.
        val t = Transcript()
        t.apply(TurnEvent.Decided(Decision("mid", "scored", 0.4f, emptyList())))

        assertEquals(emptyList<Row>(), t.rows)
    }

    @Test
    fun everyRowKeepsItsIdWhileItGrows() {
        // A LazyColumn keyed on position re-creates every item below an insert,
        // which reads as the whole conversation flickering on each fragment.
        val t = Transcript()
        t.said("hello")
        t.apply(TurnEvent.Text("one "))
        val before = t.rows.map { it.id }
        t.apply(TurnEvent.Text("two"))

        assertEquals(before, t.rows.map { it.id })
        assertEquals(listOf(0, 1), before)
    }

    @Test
    fun sayingSomethingClosesTheAnswerBeforeIt() {
        val t = Transcript()
        t.apply(TurnEvent.Text("first turn"))
        t.said("and another thing")
        t.apply(TurnEvent.Text("second turn"))

        assertEquals(3, t.rows.size)
    }

    @Test
    fun aResumedTurnDropsTheInterruptionItResumes() {
        val t = Transcript()
        t.apply(TurnEvent.Text("half an "))
        t.interrupted()

        assertTrue(t.resumed())
        assertEquals(1, t.rows.size)
        assertFalse("nothing to resume twice", t.resumed())
    }

    @Test
    fun aFailureIsARowRatherThanNothing() {
        val t = Transcript()
        t.failed("all 3 models failed")

        assertEquals("all 3 models failed", (t.rows.single() as Row.Failed).reason)
    }

    @Test
    fun aScheduledExchangeFoldsInAboveItsAnswer() {
        // A store the chat does not read would open on an answer with no
        // question above it, which is the failure the fold exists to stop.
        val t = Transcript()
        t.scheduled("check the build", answered = "it passed", failed = null)

        assertEquals(2, t.rows.size)
        assertEquals("check the build", (t.rows[0] as Row.Said).text)
        assertEquals("it passed", (t.rows[1] as Row.Answered).text)
    }

    @Test
    fun aScheduledTurnWithoutAnAnswerFailsAsARow() {
        val t = Transcript()
        t.scheduled("check the build", answered = null, failed = "the turn ended without an answer")

        assertEquals("the turn ended without an answer", (t.rows[1] as Row.Failed).reason)
    }

    @Test
    fun aScheduledFoldAndALiveTurnShareOneCounter() {
        // ChatScreen keys rows on id; two counters would hand a scheduled row
        // the id a live one already holds. What a person typed after the fold
        // starts its own answer, for the reason
        // sayingSomethingClosesTheAnswerBeforeIt states, which is why there
        // are four ids and not three.
        val t = Transcript()
        t.scheduled("check the build", "it passed", null)
        t.said("and now this")
        t.apply(TurnEvent.Text("ok"))

        assertEquals(listOf(0, 1, 2, 3), t.rows.map { it.id })
        assertEquals("ok", (t.rows.last() as Row.Answered).text)
    }
}
