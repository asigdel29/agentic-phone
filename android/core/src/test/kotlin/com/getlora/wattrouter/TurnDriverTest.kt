// TurnDriverTest.kt: starting, stopping, and not confusing the two.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The driver's scope is backgroundScope with an UnconfinedTestDispatcher.
// Unconfined so a launched turn runs to its first real suspension immediately:
// under the standard dispatcher the turn was still queued after
// advanceUntilIdle, and every assertion read a transcript nothing had written
// to. backgroundScope so a turn parked on a gate is cancelled with the test.
//
// The gates make an interruption land at a known point rather than a hopeful
// one.

package com.getlora.wattrouter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val CHAIN = listOf(Step("mid", Backend.REMOTE))
class TurnDriverTest {
    private fun TestScope.driving(agent: Agent) = TurnDriver(
        agent,
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
    )

    private val routing = Routing { _, _ -> Decision("mid", "s", null, CHAIN) }

    private fun agentSaying(vararg events: StreamEvent) = Agent(
        routing,
        ChainWalk(ScriptedInference(events.toList())),
        ToolBox(emptyList()),
    )

    /** Says one thing, then waits on the next gate before finishing. */
    private class Holding(vararg gates: CompletableDeferred<Unit>) : Inference {
        private val waiting = ArrayDeque(gates.toList())

        override fun complete(
            conversation: Conversation,
            model: String,
            tools: String?,
            maxTokens: Int?,
        ): Flow<StreamEvent> = flow {
            emit(StreamEvent.Text("half an "))
            waiting.removeFirst().await()
            emit(StreamEvent.Text("answer"))
        }
    }

    @Test
    fun aTurnShowsWhatWasSaidAndWhatWasAnswered() = runTest {
        val driver = driving(agentSaying(StreamEvent.Text("Tuesday")))

        driver.send("when do the bins go out")

        assertEquals(2, driver.rows.value.size)
        assertTrue(driver.rows.value[0] is Row.Said)
        assertEquals("Tuesday", (driver.rows.value[1] as Row.Answered).text)
        assertFalse(driver.isRunning.value)
    }

    @Test
    fun theRoutingPanelIsFedWithoutARowBeingWritten() = runTest {
        val driver = driving(agentSaying(StreamEvent.Text("hi")))

        driver.send("hello")

        assertNotNull(driver.routing.value)
        assertEquals("mid", driver.routing.value?.tier)
    }

    @Test
    fun aBlankMessageIsAKeyboardRatherThanAQuestion() = runTest {
        val driver = driving(agentSaying(StreamEvent.Text("hi")))

        driver.send("   ")

        assertEquals(emptyList<Row>(), driver.rows.value)
    }

    @Test
    fun aSecondSendWhileOneIsRunningIsIgnored() = runTest {
        // Two turns appending to one conversation would interleave a round.
        val gate = CompletableDeferred<Unit>()
        val agent = Agent(routing, ChainWalk(Holding(gate)), ToolBox(emptyList()))
        val driver = driving(agent)

        driver.send("first")
        driver.send("second")

        assertEquals(1, driver.rows.value.count { it is Row.Said })
        gate.complete(Unit)
    }

    @Test
    fun interruptingStopsTheTurnAndSaysSo() = runTest {
        val gate = CompletableDeferred<Unit>()
        val agent = Agent(routing, ChainWalk(Holding(gate)), ToolBox(emptyList()))
        val driver = driving(agent)

        driver.send("go")
        assertTrue(driver.isRunning.value)

        driver.interrupt()

        assertFalse(driver.isRunning.value)
        assertTrue(driver.rows.value.last() is Row.Interrupted)
        // What arrived before is kept: it is what the person read.
        assertTrue(driver.rows.value.any { it is Row.Answered })
    }

    @Test
    fun aTurnFinishingAfterAnotherHasStartedDoesNotClearIt() = runTest {
        // The generation counter: the first turn unwinds after the second has
        // started, and clearing isRunning would hide the second while it ran.
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val driver = driving(
            Agent(routing, ChainWalk(Holding(first, second)), ToolBox(emptyList())),
        )

        driver.send("one")
        driver.interrupt()
        driver.send("two")
        first.complete(Unit)

        assertTrue("the second turn should still be running", driver.isRunning.value)
        second.complete(Unit)
    }

    @Test
    fun resumingNeedsAnInterruptionToResume() = runTest {
        val driver = driving(agentSaying(StreamEvent.Text("hi")))

        driver.resume()

        assertEquals(emptyList<Row>(), driver.rows.value)
    }

    @Test
    fun resumingDropsTheInterruptionAndAsksAgainWithoutRepeatingTheQuestion() = runTest {
        val gate = CompletableDeferred<Unit>()
        val agent = Agent(routing, ChainWalk(Holding(gate)), ToolBox(emptyList()))
        val driver = driving(agent)

        driver.send("only once")
        driver.interrupt()
        gate.complete(Unit)

        driver.resume()

        assertFalse(driver.rows.value.any { it is Row.Interrupted })
        assertEquals(1, agent.conversation.messages.count { it.role == Role.USER })
    }

    @Test
    fun aTurnThatFailsSaysWhyRatherThanStopping() = runTest {
        val agent = Agent(Routing { _, _ -> null }, ChainWalk(ScriptedInference("x")), ToolBox(emptyList()))
        val driver = driving(agent)

        driver.send("go")

        assertFalse(driver.isRunning.value)
        assertTrue(driver.rows.value.last() is Row.Failed)
    }

    @Test
    fun aScheduledExchangeEntersTheConversationBeforeAnythingRuns() = runTest {
        val driver = driving(agentSaying(StreamEvent.Text("hi")))

        driver.scheduled("check the build", "it passed", null)
        driver.send("hello")

        // The scheduled pair above the live one, and one turn each.
        assertEquals(4, driver.rows.value.size)
        assertEquals("check the build", (driver.rows.value[0] as Row.Said).text)
        assertTrue(driver.rows.value[1] is Row.Answered)
        assertFalse(driver.isRunning.value)
    }

    @Test
    fun aScheduledOutcomeArrivingMidTurnIsLateRatherThanInterleaved() = runTest {
        // Folding a finished exchange between a round's question and its
        // answer would read as part of the turn in flight. The store still
        // holds it and the next driver carries it, so a dropped one is late,
        // not lost.
        val gate = CompletableDeferred<Unit>()
        val driver = driving(Agent(routing, ChainWalk(Holding(gate)), ToolBox(emptyList())))

        driver.send("go")
        driver.scheduled("check the build", "it passed", null)

        assertEquals(1, driver.rows.value.count { it is Row.Said })
        gate.complete(Unit)
    }
}
