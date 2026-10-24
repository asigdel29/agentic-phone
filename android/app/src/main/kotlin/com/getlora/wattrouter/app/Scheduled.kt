// Scheduled.kt: the turns this phone has been asked to run later, and what came back.
//
// History
//   2026-09-17  A. Sigdel  Created, the phone half of #600.
//
// Connections.kt's shape, and its reason: the decisions are pure functions the
// JVM suite reaches, and the class around them only stores. JSON in one
// SharedPreferences string, and anything that will not parse reads as empty,
// for the reason modeFrom gives: a file a later build or an editor mangled
// should leave the phone working.
//
// Times are epoch milliseconds, because the store has no opinion about time
// zones and the screen that will pick one has not been built. A time still
// ahead is the store's whole opinion about when: WorkManager clamps a delay
// past zero, so a schedule in the past would run the moment it was saved,
// which is not what "tomorrow morning" means to anybody who typed it.

package com.getlora.wattrouter.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One scheduled turn, as it was asked for and as it ended.
 *
 * @property id the store's own, one above the highest so far. It names the
 *   WorkManager work and the row, so a screen can forget one schedule without
 *   forgetting another.
 * @property at when the turn should run, as epoch milliseconds.
 * @property what the person's words. The prompt the model wakes to, and the
 *   whole of what it knows about why it was woken.
 * @property answered what the model said back, or null while the turn has not
 *   run or did not answer.
 * @property failed why the turn ended without an answer, or null.
 */
data class Scheduled(
    val id: Int,
    val at: Long,
    val what: String,
    val answered: String? = null,
    val failed: String? = null,
) {
    /** Whether the turn has run, either way. */
    val isDone: Boolean get() = answered != null || failed != null
}

/**
 * Why this schedule cannot be saved, in words to put under a field.
 *
 * # Arguments
 * * `at`: when the turn should run, as epoch milliseconds.
 * * `what`: what the person asked, WHERE it may be blank.
 * * `now`: the time the schedule is being made, so a test does not need a clock.
 *
 * # Returns
 * The reason, or null when it may be saved. A reason rather than a boolean for
 * the reason [refusing] in Connections.kt gives: each one is a different thing
 * to fix, and a field that goes red without saying why is a field people
 * retype unchanged.
 */
fun refusing(at: Long, what: String, now: Long = System.currentTimeMillis()): String? = when {
    what.isBlank() -> "Say what the turn is for. The model reads these words when it wakes."
    at <= now -> "Pick a time still ahead. One in the past would run as soon as it is saved."
    else -> null
}

/** The turns this person has scheduled, between launches. */
class ScheduledTurns(private val store: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE),
    )

    /**
     * Every turn, oldest first.
     *
     * # Returns
     * What was saved, or empty. Anything that will not parse is empty rather
     * than a throw, which is the rule `Connections.all` follows.
     */
    val all: List<Scheduled>
        get() = runCatching { rowsFrom(store.getString(KEY, null)) }.getOrDefault(emptyList())

    /** The turns that have run, either way, in the order they were scheduled. */
    val completed: List<Scheduled> get() = all.filter { it.isDone }

    /**
     * Add one.
     *
     * # Arguments
     * * `at`: when the turn should run, WHERE it is still ahead.
     * * `what`: what the person asked, WHERE it is not blank.
     *
     * # Returns
     * The row saved, or null when [refusing] would refuse it. The check is
     * here rather than only at the caller, so the store cannot be handed a
     * schedule the screen would have refused, which is `Connections.add`'s
     * reason; the caller calls [refusing] itself for the words to show.
     */
    fun add(at: Long, what: String): Scheduled? {
        if (refusing(at, what) != null) return null
        val row = Scheduled((all.maxOfOrNull { it.id } ?: 0) + 1, at, what.trim())
        write(all + row)
        return row
    }

    /**
     * Record how a turn ended. Exactly one of the two is a failure to the
     * worker, which is the only writer; unknown ids are ignored rather than
     * reported, as [Connections.forget] ignores them.
     */
    fun mark(id: Int, answered: String?, failed: String?) =
        write(all.map { if (it.id == id) it.copy(answered = answered, failed = failed) else it })

    /** Take a schedule back, whether it has run or not. */
    fun forget(id: Int) = write(all.filterNot { it.id == id })

    private fun write(rows: List<Scheduled>) {
        store.edit().putString(KEY, toJson(rows)).apply()
    }

    private companion object {
        const val FILE = "scheduled-turns"
        const val KEY = "turns"
    }
}

/** The rows as one JSON array, the shape [Connections.write] writes. */
internal fun toJson(rows: List<Scheduled>): String = buildJsonArray {
    rows.forEach { row ->
        add(
            buildJsonObject {
                put("id", row.id)
                put("at", row.at)
                put("what", row.what)
                row.answered?.let { put("answered", it) }
                row.failed?.let { put("failed", it) }
            },
        )
    }
}.toString()

/**
 * The rows back out of what [toJson] wrote.
 *
 * A row missing its id, its time or its words is dropped rather than
 * defaulted, because a defaulted id is a row that [ScheduledTurns.mark] would
 * overwrite another with. Null reads as empty rather than as a failure, which
 * is the rule [Connections.all] states.
 */
internal fun rowsFrom(saved: String?): List<Scheduled> {
    if (saved == null) return emptyList()
    return runCatching {
        Json.parseToJsonElement(saved).jsonArray.mapNotNull { entry ->
            val row = entry.jsonObject
            val id = row["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val at = row["at"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val what = row["what"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Scheduled(
                id = id,
                at = at,
                what = what,
                answered = row["answered"]?.jsonPrimitive?.contentOrNull,
                failed = row["failed"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }.getOrDefault(emptyList())
}
