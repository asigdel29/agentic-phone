// ScheduledTurnWorker.kt: the turn a saved schedule runs when its time arrives.
//
// History
//   2026-10-24  A. Sigdel  Created, the phone half of #600.
//
// This is the consumer TurnAssembly was built for (#714): a turn with no
// Activity at either end. The worker stands where MainActivity stands --
// Startup, Connections, the assembly -- and runs the same Agent over the
// same transcript, minus the screen.
//
// The tool set is [TurnAssembly.screenless], and the exclusion is the point:
// no calendar, no contacts, no location. Each is built on a Permission that
// raises its dialog through an Activity, and a dialog a scheduled turn
// raises is a dialog nobody is there to answer. The driving tools stay, and
// Barred still refuses a locked phone through the same shared path the chat
// uses, so a schedule cannot do from a locked phone what a person could not.
//
// One-shot, and no retry. A scheduled turn asked again is a turn nobody
// asked again. A failure is marked on the row and folded into the
// conversation at the next launch, which is an outcome a person can act on;
// WorkManager retrying it would be a second asking. Every ending here is
// Result.success() for that reason -- the outcome lives in the store, not in
// the job's exit code.
//
// Inexact timing, deliberately. WorkManager batches and defers, and a
// schedule is "tomorrow morning", not an alarm; the exact-alarm permission
// buys punctuality this request does not want and needs a permission this
// app does not carry.
//
// The Budget and the Replay are built here and handed to both the assembly
// and the Agent. A second instance anywhere would run the turn on an
// allowance its tools cannot see, and write a card about a turn nobody ran.
//
// The outcome outlives the process. mark() writes it into the store, and
// MainActivity folds completed rows into the next driver it builds (#722's
// scheduled), so a turn that ran with the phone face-down on a table is a
// question and an answer in the chat rather than a run nobody can find.

package com.getlora.wattrouter.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.getlora.wattrouter.Agent
import com.getlora.wattrouter.Budget
import com.getlora.wattrouter.ChainWalk
import com.getlora.wattrouter.Connections
import com.getlora.wattrouter.Core
import com.getlora.wattrouter.Credential
import com.getlora.wattrouter.HttpRpc
import com.getlora.wattrouter.NeuralWattInference
import com.getlora.wattrouter.Planned
import com.getlora.wattrouter.Reaching
import com.getlora.wattrouter.Replay
import com.getlora.wattrouter.Row
import com.getlora.wattrouter.Scrollback
import com.getlora.wattrouter.Startup
import com.getlora.wattrouter.ToolBox
import com.getlora.wattrouter.TurnDriver
import com.getlora.wattrouter.connect
import com.getlora.wattrouter.routing
import com.getlora.wattrouter.tools
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

class ScheduledTurnWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val store = ScheduledTurns(applicationContext)
        val row = store.all.firstOrNull { it.id == inputData.getInt(ID, 0) }
            // Forgotten, or folded and forgotten already: the schedule is
            // gone, and a worker holding nothing has nothing to run.
            ?: return Result.success()

        val startup = Startup.begin(applicationContext)
        val core = when (startup) {
            is Startup.Ready -> startup.core
            // The two states a cold launch can end in without a model. Said
            // in the words under the field the sign-in screen shows, because
            // this is the same fact from the other side of the process.
            Startup.NoCredential -> {
                store.mark(row.id, null, "nobody has signed in, so there is no model to ask")
                return Result.success()
            }
            Startup.CoreRefused -> {
                store.mark(row.id, null, "the stored key would not start the model")
                return Result.success()
            }
        }

        try {
            run(row, core)
        } catch (stopped: CancellationException) {
            // The system took the turn back: a reboot, a stopped job. Saying
            // so before the unwind is the difference between a row that says
            // why and one that sits there forever; a process that dies first
            // leaves the row pending, which is the same nothing the kill
            // would have left anyway.
            store.mark(row.id, null, "stopped before finishing")
            throw stopped
        } finally {
            core.close()
        }
        return Result.success()
    }

    /**
     * The turn itself, in the shape MainActivity's [MainActivity.driverFor]
     * builds -- minus the composition, the scope it would cancel with, and
     * the Activity-only tools.
     */
    private suspend fun run(row: Scheduled, core: Core) {
        val modes = Modes(applicationContext)
        val connections = Connections(applicationContext)

        // One allowance and one card, shared with the assembly below: the
        // decorators that spend them and the Agent that holds them must be
        // looking at the same objects.
        val budget = Budget()
        val replay = Replay()

        val assembly = TurnAssembly(
            applicationContext,
            applicationContext.filesDir,
            modes,
            Signing(applicationContext),
            Reaching(applicationContext),
            replay,
            budget,
            Scrollback(),
        )

        // Asked once, like the chat asks them: ToolBox is what the model is
        // told at the top of a turn, and a set that grew afterwards is a tool
        // it was never offered.
        val reached = connect(connections.all) { HttpRpc(it.endpoint) }

        val driver = TurnDriver(
            Agent(
                router = core.routing(),
                walk = ChainWalk(
                    NeuralWattInference(
                        Credential(applicationContext).read().orEmpty(),
                        BuildConfig.UPSTREAM_BASE_URL,
                    ),
                ),
                // Screenless first, server tools last. ToolBox keeps the
                // first of a duplicate name, so a server cannot displace a
                // compiled tool -- the same ordering the chat uses.
                tools = ToolBox(assembly.screenless() + reached.tools()),
                budget = budget,
                planned = Planned({ modes.now }, AndroidApproval()),
                replay = replay,
            ),
            // The worker's own context as a scope: the turn runs as a child
            // of doWork, so a cancellation the system issues finds it, and a
            // turn that dies cancels this wait.
            CoroutineScope(coroutineContext),
        )

        driver.send(row.what)
        driver.isRunning.first { !it }
        val outcome = answeredFrom(driver.rows.value)

        ScheduledTurns(applicationContext).mark(row.id, outcome.answered, outcome.failed)
    }

    companion object {
        private const val ID = "turn-id"

        /**
         * Put one saved turn in WorkManager's hands, to run when its time
         * arrives.
         *
         * Named after the row and KEEP: a schedule saved twice is one turn,
         * not two. The network constraint rather than a runtime wait -- a
         * turn the phone cannot reach a model for is not a turn to start.
         */
        fun schedule(context: Context, row: Scheduled) {
            val request = OneTimeWorkRequestBuilder<ScheduledTurnWorker>()
                .setInitialDelay(
                    maxOf(0, row.at - System.currentTimeMillis()),
                    TimeUnit.MILLISECONDS,
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInputData(workDataOf(ID to row.id))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("scheduled-turn-${row.id}", ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** How a scheduled turn ended, for [ScheduledTurns.mark]. */
internal data class TurnOutcome(val answered: String?, val failed: String?)

/**
 * The outcome a finished turn leaves in its rows: the last word it said,
 * which is an answer, a failure, or neither.
 *
 * A pure function and on the JVM for the reason ScheduledTurnTest gives: the
 * decision with a bug in it should not be the one that only runs on a device.
 */
internal fun answeredFrom(rows: List<Row>): TurnOutcome {
    val last = rows.lastOrNull {
        it is Row.Answered || it is Row.Failed || it is Row.Interrupted
    }
    return when (last) {
        is Row.Answered -> TurnOutcome(last.text, null)
        is Row.Failed -> TurnOutcome(null, last.reason)
        is Row.Interrupted -> TurnOutcome(null, "stopped before finishing")
        else -> TurnOutcome(null, "the turn ended without an answer")
    }
}
