// TurnDriver.kt: starting a turn, watching it, and stopping it.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The object a screen holds: the transcript, whether a turn is running, and
// the last decision for a routing panel.
//
// Interrupting is cancelling the coroutine and nothing else. Every layer below
// was built for it: the flow is cold and cancels with its collector, the
// client checks per line because tool-call fragments emit nothing for a long
// stretch, and ToolBox rethrows CancellationException alone. A flag here would
// be a second answer to "is this turn over".
//
// The generation counter is the part that is not bookkeeping. Interrupt, then
// send again: the first turn unwinds *after* the second has started, and
// clearing isRunning on the way out would leave the second invisible while it
// ran. Job identity does not work: the job that finishes is not the one the
// caller last kept.

package com.getlora.wattrouter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One conversation, as a screen sees it. */
class TurnDriver(
    private val agent: Agent,
    private val scope: CoroutineScope,
) {
    private val script = Transcript()
    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    private val _isRunning = MutableStateFlow(false)
    private val _routing = MutableStateFlow<Decision?>(null)

    /** The conversation, as it is shown. */
    val rows: StateFlow<List<Row>> = _rows.asStateFlow()

    /** Whether a turn is in flight. */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** The last routing decision, for a panel. Null until a turn has run. */
    val routing: StateFlow<Decision?> = _routing.asStateFlow()

    private var running: Job? = null
    private var generation = 0
    /**
     * Say something and run the turn it starts. Does nothing while a turn is
     * running, or for blank text: two turns appending to one conversation would
     * interleave a round, and a blank message is a keyboard, not a question.
     */
    fun send(text: String) {
        if (_isRunning.value || text.isBlank()) return
        script.said(text)
        publish()
        start { agent.send(text) }
    }

    /**
     * Run the turn an interruption stopped. Does nothing unless the last row is
     * one, or it would start a turn nothing had stopped.
     */
    fun resume() {
        if (_isRunning.value || !script.resumed()) return
        publish()
        start { agent.resume() }
    }

    /** Stop the turn in flight, and say so in the transcript. */
    fun interrupt() {
        if (!_isRunning.value) return
        running?.cancel()
        running = null
        _isRunning.value = false
        script.interrupted()
        publish()
    }

    /**
     * Fold a scheduled turn's outcome into the conversation.
     *
     * # Rely
     * Called when the driver is built, before a turn runs. While one is in
     * flight the call is dropped rather than folded: interleaving a finished
     * exchange between a round's question and its answer would read as part of
     * that turn. The store still holds the outcome and the next driver carries
     * it, so a dropped one is late rather than lost.
     */
    fun scheduled(what: String, answered: String?, failed: String?) {
        if (_isRunning.value) return
        script.scheduled(what, answered, failed)
        publish()
    }

    private fun start(turn: () -> kotlinx.coroutines.flow.Flow<TurnEvent>) {
        val mine = ++generation
        _isRunning.value = true

        running = scope.launch {
            try {
                turn().collect { event ->
                    if (event is TurnEvent.Decided) _routing.value = event.decision
                    script.apply(event)
                    publish()
                }
            } catch (e: CancellationException) {
                // interrupt() already recorded it; a failure row too would say
                // the same thing twice.
                throw e
            } catch (e: Exception) {
                script.failed(e.message ?: e::class.java.simpleName)
                publish()
            } finally {
                // Only if this is still the turn the caller is watching.
                if (mine == generation) _isRunning.value = false
            }
        }
    }

    // A new list each time: StateFlow compares by equality, and mutating the
    // one it holds is a change it cannot see.
    private fun publish() { _rows.value = script.rows.toList() }
}
