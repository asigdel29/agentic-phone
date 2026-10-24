// Transcript.kt: a turn's events, folded into what a person reads.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Contents
//   Row         One line of the conversation as it is shown.
//   Transcript  The fold, and the rows it has produced.
//
// Every rule below exists because the naive version looks wrong on screen, and
// putting them in a composable would mean deciding per event whether to open a
// bubble or extend one, in a function that also has to lay one out.
//
// Ids come from a counter rather than a position in the list, so a row that
// grows keeps its identity. A LazyColumn keyed on position re-creates every
// item below an insert, which is visible as the whole conversation flickering
// each time a fragment arrives.

package com.getlora.wattrouter

/** One line of the conversation, as it is shown. */
sealed interface Row {
    val id: Int

    /** What the person typed. */
    data class Said(override val id: Int, val text: String) : Row

    /** What the model answered. [model] is null until one has announced itself. */
    data class Answered(override val id: Int, val model: String?, val text: String) : Row

    /** A tool, and its result once there is one. */
    data class Used(override val id: Int, val tool: String, val result: String?) : Row

    /** The turn stopped without answering. */
    data class Failed(override val id: Int, val reason: String) : Row

    /** The turn was interrupted. */
    data class Interrupted(override val id: Int) : Row
}

/** The conversation, as it is shown. */
class Transcript {
    private val backing = mutableListOf<Row>()

    /** In the order they happened. */
    val rows: List<Row> get() = backing

    private var next = 0

    /** Where the answer being written is, if one is open. */
    private var open: Int? = null

    /** A model that announced itself before saying anything. */
    private var pending: String? = null

    /** Record what the person typed. Closes any answer still open. */
    fun said(text: String) {
        open = null
        backing += Row.Said(next++, text)
    }

    /** Fold one event in. */
    fun apply(event: TurnEvent) {
        when (event) {
            // Names the row being written if there is one; otherwise waits. A
            // round that only calls tools would leave a blank bubble with a
            // model name on it.
            is TurnEvent.Answering -> open
                ?.let { at -> backing[at] = (backing[at] as Row.Answered).copy(model = event.model) }
                ?: run { pending = event.model }

            is TurnEvent.Text -> open
                ?.let { at ->
                    val row = backing[at] as Row.Answered
                    backing[at] = row.copy(text = row.text + event.text)
                }
                ?: run {
                    backing += Row.Answered(next++, pending, event.text)
                    pending = null
                    open = backing.lastIndex
                }

            // Closes the answer: anything the model says after a tool is a new
            // paragraph, after the result, not a continuation of the last one.
            is TurnEvent.Call -> {
                open = null
                backing += Row.Used(next++, event.call.name, result = null)
            }

            // The earliest unfilled one, which is valid because the Agent runs
            // tools in the order the model asked for them.
            is TurnEvent.Result -> {
                val at = backing.indexOfFirst { it is Row.Used && it.result == null }
                if (at >= 0) {
                    backing[at] = (backing[at] as Row.Used).copy(result = event.result.content)
                }
            }

            // No row. It feeds the routing panel, and "chose mid" in the middle
            // of a conversation is noise.
            is TurnEvent.Decided -> Unit
        }
    }

    /** Record that the turn was interrupted. */
    fun interrupted() {
        open = null
        pending = null
        backing += Row.Interrupted(next++)
    }

    /**
     * Fold in an exchange that ran with nobody watching: what was asked when it
     * was scheduled, and the answer or the reason there was none.
     *
     * The record a scheduled turn leaves in its store would otherwise open the
     * conversation on an answer with no question above it. Its ids come from
     * this counter, because ChatScreen keys rows on id and a second counter
     * would collide with the live one.
     */
    fun scheduled(what: String, answered: String?, failed: String?) {
        open = null
        pending = null
        backing += Row.Said(next++, what)
        when {
            answered != null -> backing += Row.Answered(next++, null, answered)
            failed != null -> backing += Row.Failed(next++, failed)
        }
    }

    /** Record that the turn stopped without answering. */
    fun failed(reason: String) {
        open = null
        pending = null
        backing += Row.Failed(next++, reason)
    }

    /**
     * Drop a trailing interruption, for a turn about to be resumed.
     *
     * @return whether there was one. A caller resumes only if so; otherwise it
     *   would be starting a turn nothing interrupted.
     */
    fun resumed(): Boolean {
        val last = backing.lastOrNull()
        if (last !is Row.Interrupted) return false
        backing.removeAt(backing.lastIndex)
        return true
    }
}
