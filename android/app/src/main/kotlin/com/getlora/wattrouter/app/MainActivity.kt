// MainActivity.kt: the launch, and which of three states it lands in.
//
// History
//   2026-08-08  A. Sigdel  Created. Reported whether the core loaded.
//   2026-08-08  A. Sigdel  Signs in, so it reports which of Startup's three
//                          states it reached instead.
//   2026-08-09  A. Sigdel  Holds a conversation once it is signed in.
//   2026-08-09  A. Sigdel  Reads the calendar, which is the first tool needing
//                          a permission and so the first needing an Activity.
//   2026-08-09  A. Sigdel  Takes what another app shares, once.
//   2026-08-09  A. Sigdel  Shows the checklist when the phone half is not on.
//   2026-08-09  A. Sigdel  Can look at the screen and tap it, when the service
//                          behind that has been switched on.
//   2026-08-11  A. Sigdel  Holds the workspace, rather than the git tools
//                          computing it, now that it is about to have a second
//                          reader.
//   2026-08-11  A. Sigdel  Listens when the person asks it to, #659.
//   2026-08-12  A. Sigdel  Runs one command, in the workspace the git tools
//                          already use, #677.
//   2026-08-13  A. Sigdel  Hands the repository a key to reach a host with, #467.
//   2026-08-13  A. Sigdel  Offers the two network tools that cannot lose
//                          anything, #467.
//   2026-08-13  A. Sigdel  Offers the two that can, #467.
//   2026-08-13  A. Sigdel  Shows the key and the hosts it has met, #467.
//   2026-08-13  A. Sigdel  Gives a wrong provider key a way back, #512.
//   2026-09-17  A. Sigdel  Shows what the terminal ran, behind the
//                          settings, #713.
//
// The core and the driver are built once and held for the process. The core
// owns a native pointer and a decision cache, and a second one is a second
// cache that disagrees with the first: the same reasoning WattRouterApp.swift
// gives for building its driver once into @State and never reassigning.

package com.getlora.wattrouter.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.getlora.wattrouter.Agent
import com.getlora.wattrouter.Barred
import com.getlora.wattrouter.Budget
import com.getlora.wattrouter.CalendarTool
import com.getlora.wattrouter.Capability
import com.getlora.wattrouter.ChainWalk
import com.getlora.wattrouter.ContactsTool
import com.getlora.wattrouter.Credential
import com.getlora.wattrouter.Heard
import com.getlora.wattrouter.LocationTool
import com.getlora.wattrouter.Needed
import com.getlora.wattrouter.Connections
import com.getlora.wattrouter.HttpRpc
import com.getlora.wattrouter.Permission
import com.getlora.wattrouter.PermissionError
import com.getlora.wattrouter.Planned
import com.getlora.wattrouter.Reached
import com.getlora.wattrouter.Reaching
import com.getlora.wattrouter.connect
import com.getlora.wattrouter.tools
import com.getlora.wattrouter.Tool
import com.getlora.wattrouter.ReadScreenTool
import com.getlora.wattrouter.Replay
import com.getlora.wattrouter.Scrollback
import com.getlora.wattrouter.Speaking
import com.getlora.wattrouter.Spoken
import com.getlora.wattrouter.NeuralWattInference
import com.getlora.wattrouter.Row
import com.getlora.wattrouter.Startup
import com.getlora.wattrouter.ToolBox
import com.getlora.wattrouter.worthSaying
import com.getlora.wattrouter.TurnDriver
import com.getlora.wattrouter.routing

class MainActivity : ComponentActivity() {
    private var started: Startup? = null
    private lateinit var permission: Permission

    /**
     * What one turn may do to the phone.
     *
     * Held here so the Agent that resets it and the tools that spend it are the
     * same one. A second would be a second allowance.
     */
    private val budget = Budget()

    /** What the last turn did, for the card the transcript will show. */
    private val replay = Replay()

    /** What the terminal has run, for the card the transcript will show. */
    private val scrollback = Scrollback()

    /**
     * How involved this person wants to be, read per action by `Confirmed`.
     *
     * Built here rather than per composition: a store rebuilt on every
     * recomposition is a file opened on every recomposition, and the mode is
     * read from a turn rather than from the composition anyway.
     */
    private val modes by lazy { Modes(applicationContext) }
    private val signing by lazy { Signing(applicationContext) }
    private val connections by lazy { Connections(applicationContext) }

    /**
     * The turns this person has asked for later, and what came back.
     *
     * Built once for the process like the stores above it. The worker is the
     * only writer while the turn runs; this side reads the finished rows and
     * forgets them as a driver takes each one.
     */
    private val scheduled by lazy { ScheduledTurns(applicationContext) }

    /**
     * The microphone, built once for the process like the three above it.
     *
     * The application context rather than this Activity: it outlives a rotation
     * and the recognizer is torn down at the end of every press anyway, so a
     * reference to the Activity here would be one nothing needs.
     */
    private val listening by lazy { AndroidListening(applicationContext) }

    /**
     * The voice, and whether it is used, built once for the process as above.
     *
     * The engine itself is made and shut down per utterance inside
     * [AndroidSpeaking]; what is held here is the conformance, which is a
     * context and nothing else.
     */
    private val speaking by lazy { AndroidSpeaking(applicationContext) }
    private val aloud by lazy { Aloud(applicationContext) }

    /**
     * The turn's tools, assembled without this Activity, #714: the four
     * builders below delegate into it, and a scheduled turn can call it where
     * this Activity stands. Nullable so [onDestroy] closes without building.
     */
    private var assembly: TurnAssembly? = null

    private fun turnAssembly(): TurnAssembly = assembly ?: TurnAssembly(
        applicationContext, filesDir, modes, signing, reaching, replay, budget, scrollback,
    ).also { assembly = it }

    /**
     * The key this phone pushes with, and the hosts it has met.
     *
     * Beside the workspace rather than in it: the workspace is a repository the
     * agent writes to, and a private key inside one is a private key a model can
     * stage. Neither is made here; [Reaching.ensure] makes the key when somebody
     * asks to see it, and `git::trust` writes the pins on the first connection.
     */
    private val reaching by lazy { Reaching(applicationContext) }

    /**
     * What another app shared, until a turn takes it.
     *
     * Compose state rather than a field, because the composition is what
     * notices: a share arriving while the app is already open reaches
     * [onNewIntent] and nothing recomposes on its own.
     */
    private val handed = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val credential = Credential(this)
        take(intent)

        // Here rather than in the composition. AndroidAsking registers an
        // activity-result launcher, which has to happen before this Activity is
        // STARTED, and setContent's first composition is not before it, and built
        // there it throws about a lifecycle state rather than about the line.
        permission = Permission(AndroidAsking(this))

        setContent {
            var state by remember { mutableStateOf(started ?: Startup.begin(this)) }
            started = state

            // One place rather than two booleans, which is #641: `connecting`
            // was set from the checklist and nowhere else and `checking` was
            // never set true again, so the connections screen could be reached
            // exactly once in an installation's life and nothing said so.
            //
            // The checklist still opens by itself when something required is
            // off, which is the difference between a checklist and a wizard
            // nobody can dismiss; it is now somewhere to come back to as well.
            var where by remember {
                mutableStateOf(
                    if (readiness(this).canDrive) Where.Conversation else Where.Readiness,
                )
            }

            // Null until every saved server has been asked, and the
            // conversation waits for it, because ToolBox is what the model is
            // told at the top of a turn: a set that grew afterwards is a tool
            // it was never offered. With nothing saved this costs a frame.
            var connected by remember { mutableStateOf<List<Reached>?>(null) }
            LaunchedEffect(where) {
                if (where != Where.Connections) {
                    connected = connect(connections.all) { HttpRpc(it.endpoint) }
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val now = state) {
                        is Startup.Ready ->
                            when (where) {
                                Where.Readiness -> Checklist(
                                    // Onward rather than closed: at launch this
                                    // is the way in, and from the settings it is
                                    // the way back to where the tap came from.
                                    onCarryOn = { where = Where.Conversation },
                                    onConnections = { where = Where.Connections },
                                )

                                Where.Connections -> ConnectionsScreen(
                                    connected = connected.orEmpty(),
                                    onAdd = { label, endpoint ->
                                        connections.add(label, endpoint)
                                    },
                                    onForget = { connections.forget(it) },
                                    // Leaving re-asks, because a server added
                                    // or forgotten while it was open changes
                                    // what the next turn is offered.
                                    onDone = { where = Where.Settings },
                                )

                                Where.SigningKey -> SigningKeyScreen(
                                    shown = reaching.shown,
                                    hosts = met(),
                                    onMake = { reaching.ensure() },
                                    onDone = { where = Where.Settings },
                                )

                                Where.Credential -> CredentialScreen(
                                    onForget = { credential.forget() },
                                    // Re-read rather than assumed. begin() is
                                    // what decides which of the three states
                                    // this is, and a forget that half failed
                                    // would otherwise show a sign-in screen
                                    // over a key that is still stored.
                                    onSignIn = {
                                        state = Startup.begin(this@MainActivity)
                                        where = Where.Conversation
                                    },
                                    onDone = { where = Where.Settings },
                                )

                                Where.Turn -> TurnScreen(
                                    modes,
                                    signing,
                                    onDone = { where = Where.Settings },
                                )

                                Where.Scrollback -> ScrollbackScreen(
                                    // Read at composition rather than collected,
                                    // the replay card's reason: the list changes
                                    // only while a turn runs, and a screen whose
                                    // body moves under the eye of somebody
                                    // reading it is a screen nobody reads. This
                                    // is built per visit, so it is what there
                                    // was on the way in.
                                    commands = scrollback.commands,
                                    onDone = { where = Where.Settings },
                                )

                                Where.Settings -> SettingsScreen(
                                    destinations = places(connected),
                                    onGo = { where = it },
                                    onDone = { where = Where.Conversation },
                                )

                                Where.Conversation -> if (connected != null) {
                                    Conversation(
                                        driverFor(now, credential, connected!!),
                                        handed,
                                        replay,
                                        ::heard,
                                        aloud,
                                        speaking,
                                    ) { where = Where.Settings }
                                }
                            }
                        Startup.NoCredential, Startup.CoreRefused ->
                            SignInScreen(refused = now == Startup.CoreRefused) { typed ->
                                // store() answers false for an unusable key, and
                                // leaving the state alone keeps the field on
                                // screen rather than flashing past it.
                                if (credential.store(typed)) {
                                    state = Startup.begin(this@MainActivity)
                                }
                            }
                    }
                }
            }
        }
    }

    /**
     * The driver, built once and kept.
     *
     * Keyed on the core, so a sign-in that replaces one does not leave a driver
     * holding the old handle. rememberCoroutineScope rather than lifecycleScope:
     * it needs no dependency, and it cancels a turn with the composition that
     * started it. A turn that should outlive the screen is #233's item on long
     * work, and wants a foreground service rather than a wider scope here.
     */
    @Composable
    private fun driverFor(
        ready: Startup.Ready,
        credential: Credential,
        connected: List<Reached>,
    ): TurnDriver {
        val scope = rememberCoroutineScope()
        return remember(ready.core, connected) {
            val driver = TurnDriver(
                Agent(
                    router = ready.core.routing(),
                    // BuildConfig rather than the client's default, so where a
                    // turn goes is decided when the application is built and
                    // cannot be moved at runtime by anybody, including the
                    // agent, which can drive this application's own screen.
                    walk = ChainWalk(
                        NeuralWattInference(
                            credential.read().orEmpty(),
                            BuildConfig.UPSTREAM_BASE_URL,
                        ),
                    ),
                    // Last, so a server cannot displace a compiled tool:
                    // ToolBox keeps the first of a duplicate name, and prefixed
                    // makes the collision impossible anyway. Both, because one
                    // is a rule and the other is an ordering.
                    tools = ToolBox(
                        remembering() + phone() + working() + driving() + terminal() +
                            connected.tools(),
                    ),
                    budget = budget,
                    // The mode is read here rather than captured, so somebody
                    // who changed it between typing and the model answering
                    // means this turn. Confirmed reads the same setting the
                    // same way, one action at a time, and the two never both
                    // fire: Planned is silent in every mode but Plan.
                    planned = Planned({ modes.now }, AndroidApproval()),
                    replay = replay,
                ),
                scope,
            )
            // A turn that ran with the app closed folds here, on the first
            // driver that exists to hold it: scheduled() writes the exchange
            // in as it went, and the store forgets what a driver has taken.
            // A fresh driver is never mid-turn, so the drop path in
            // scheduled() cannot fire, and the rely it states holds.
            scheduled.completed.forEach { row ->
                driver.scheduled(row.what, row.answered, row.failed)
                scheduled.forget(row.id)
            }
            driver
        }
    }

    /**
     * The memory tools, if a store opened.
     *
     * An empty list rather than a refusal when it did not: a phone that cannot
     * open a store can still hold a conversation, and a model offered a tool
     * that always fails learns to apologise rather than to stop asking.
     *
     * The store lives in filesDir rather than cacheDir. Everything in cacheDir
     * is the system's to delete when space is short, and this is the one file
     * here nobody can reconstruct.
     */
    private fun remembering(): List<Tool> = turnAssembly().remembering()

    /**
     * The tools that read the phone rather than the app's own store.
     *
     * One [Permission] for all of them, so two asking in a round produce one
     * dialog. Contacts and location join this list; each brings a seam of its
     * own and none brings a second way of asking.
     */
    private fun phone(): List<Tool> = listOf(
        CalendarTool(AndroidCalendars(this), permission),
        ContactsTool(AndroidContacts(this), permission),
        LocationTool(AndroidWhereabouts(this), permission),
    )

    /**
     * One press of the microphone.
     *
     * Deliberately not in the list above. It is a person's button rather than
     * a tool, and the difference is the point: a model that could call this
     * could open the microphone in a turn nobody was watching.
     *
     * The same [Permission] as those three, in [CalendarTool]'s order, and the
     * refusal is returned rather than thrown for its reason too. A refusal here
     * has somewhere to be read, because [Heard.Silence] is already what the
     * button shows when a press produces nothing.
     *
     * # Rely
     * Called from the composition's scope, which cancels with the screen. It may
     * put a dialog on screen, and then holds the microphone until whoever
     * pressed it stops speaking.
     */
    private suspend fun heard(): Heard {
        try {
            permission.obtain(Capability.MICROPHONE)
        } catch (e: PermissionError) {
            return Heard.Silence(e.message.orEmpty())
        }
        return listening.listen()
    }

    /**
     * The repository the agent works in.
     *
     * The workspace in [TurnAssembly]. A directory is still not a repository,
     * so on a fresh install the other three answer that it is not one until
     * `GitInitTool` has been called. That is the model's call to make rather
     * than this function's: the two answers `init` distinguishes are "made you
     * one" and "there already was one", and a repository created here at
     * startup would spend that distinction before anybody could read it.
     */
    private fun working(): List<Tool> = turnAssembly().working()

    /**
     * What the model would see, bounded, or what to do to make it say anything.
     *
     * #511. The panel is the moment somebody decides whether they are
     * comfortable with an application that reads other applications, and with
     * the service on it was guaranteed to show a refusal instead: read_screen
     * begins with barredNow, and the screen in front at that moment is this
     * one, which Barred refuses correctly and by design.
     *
     * So the refusal becomes the instruction that resolves it. The screen
     * re-reads on resume, which it has always done for its own reasons, so
     * following the sentence is what produces the evidence: open something,
     * come back, and this is a reading of what was in front when you left.
     *
     * Not read without the gate, which was the cheap answer. The bar stops the
     * agent acting on its own screen and reading it would be harmless, but the
     * evidence would then be a reading of the checklist, which demonstrates
     * nothing about the applications anybody is actually worried about.
     */
    private fun evidence(said: String): String =
        if (said.startsWith(Barred.ITSELF.why)) {
            "This is the assistant's own screen, and it does not read its own. " +
                "Open something else and come back here: this panel reads again " +
                "every time you return, so what it shows will be what the model " +
                "would see of that."
        } else {
            said.lines().take(LOOK).joinToString("\n")
        }

    /**
     * What the settings screen offers, and one line about each.
     *
     * Built here rather than in the screen, because the counts are this
     * Activity's: [Connections] is a store it holds and readiness is a thing it
     * asks the platform. The screen draws rows.
     */
    private fun places(connected: List<Reached>?): List<Destination> = listOf(
        Destination(
            "A turn",
            "What a send does, and who a commit says made it.",
            Where.Turn,
        ),
        Destination(
            "Connections",
            when (val servers = connected?.size) {
                null, 0 -> "No servers. Their tools join every turn."
                1 -> "One server, and its tools join every turn."
                else -> "$servers servers, and their tools join every turn."
            },
            Where.Connections,
        ),
        Destination(
            "Signing key",
            if (reaching.isMade) {
                "Made. Add it to a forge to push from this phone."
            } else {
                "None yet. One is made when you ask to see it."
            },
            Where.SigningKey,
        ),
        Destination(
            "Provider key",
            "Stored. Forget it to sign in with a different one.",
            Where.Credential,
        ),
        Destination(
            "Readiness",
            if (readiness(this).canDrive) {
                "Everything the phone half needs is on."
            } else {
                "Something the phone half needs is off."
            },
            Where.Readiness,
        ),
        Destination(
            "What ran",
            // One read: a turn could be running, and two would be two
            // snapshots that can disagree with each other.
            scrollback.commands.size.let { ran ->
                if (ran == 0) "Nothing yet. What the terminal runs lands here."
                else "The last $ran commands, and what each came back."
            },
            Where.Scrollback,
        ),
    )

    /**
     * Every host pinned, one line each, or empty before the first connection.
     *
     * Read rather than parsed. `git::trust` writes a host and a hash per line
     * and this shows them; a second reader that understood the format would be
     * a second place to keep in step with it, for a screen that only displays.
     */
    private fun met(): List<String> =
        runCatching { turnAssembly().pins.readLines().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())

    /**
     * The shell, in the same directory the repository is in.
     *
     * The workspace again rather than a second one, which is what #647 hoisted
     * it for: a formatter the agent runs and a file it staged have to be the
     * same file.
     *
     * `Shown` outermost, and reading the mode rather than holding one, for the
     * reason [working] gives about identity. It is also the reason this is not
     * a `Confirmed`: that seam is a [Phone], and it treats Plan as Auto because
     * a round's tool names were approved once at the top of the turn. The name
     * approved here is `run_command`, which is the same name for `git status`
     * and for `rm -rf .`, so Plan asks.
     */
    private fun terminal(): List<Tool> = turnAssembly().terminal()

    /**
     * A share arriving while the app is already open.
     *
     * `setIntent` as well, because `getIntent` keeps answering with whatever
     * started the Activity: without it a later recreation would read this same
     * intent again. [take] then empties it, so the pair is what makes a share
     * happen once: Inbox.drain's "a read removes", in the shape Android has.
     */
    override fun onNewIntent(incoming: Intent) {
        super.onNewIntent(incoming)
        setIntent(incoming)
        take(incoming)
    }

    /** Read a share out of an intent, and leave nothing behind to read twice. */
    private fun take(incoming: Intent?) {
        handedIn(
            incoming?.action,
            incoming?.type,
            incoming?.getStringExtra(Intent.EXTRA_TEXT),
            incoming?.getStringExtra(Intent.EXTRA_SUBJECT),
        )?.let { shared ->
            handed.value = shared
            // Emptied rather than remembered as handled. A rotation recreates
            // the Activity and reads getIntent() again, and the same note
            // shared twice is a conversation nobody had.
            incoming?.action = null
        }
    }

    /**
     * The tools that drive the phone rather than read its data.
     *
     * Offered whether or not the accessibility service is on. A model that
     * cannot see the tools cannot be told why they are unavailable, and
     * read_screen's own answer names the switch and the restricted-settings
     * trap behind it, which is the only place somebody learns about either.
     */
    private fun driving(): List<Tool> = turnAssembly().driving()

    /**
     * The checklist, re-read every time this screen comes back.
     *
     * ON_RESUME rather than first composition: the moment somebody looks at
     * this is the moment they return from Settings, and a state read before
     * they went is the lie a wizard tells.
     */
    @Composable
    private fun Checklist(onCarryOn: () -> Unit, onConnections: () -> Unit) {
        val owner = LocalLifecycleOwner.current
        var now by remember { mutableStateOf(readiness(this)) }
        var seeing by remember { mutableStateOf<String?>(null) }

        DisposableEffect(owner) {
            val watching = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) now = readiness(this@MainActivity)
            }
            owner.lifecycle.addObserver(watching)
            onDispose { owner.lifecycle.removeObserver(watching) }
        }

        // Evidence rather than a claim, and only once there is any: before the
        // service is on there is nothing to show and nothing to promise.
        //
        // Keyed on `now` rather than on `now.canDrive`, which is #511's other
        // half: the observer above replaces `now` on every resume, so coming
        // back to this screen re-reads. That is what makes the sentence below
        // an instruction that works rather than a promise.
        LaunchedEffect(now) {
            seeing = if (!now.canDrive) {
                null
            } else {
                evidence(ReadScreenTool(AndroidPhone(applicationContext)).run("{}"))
            }
        }

        ReadinessScreen(
            now,
            seeing,
            onOpen = { open(it) },
            onCarryOn = onCarryOn,
            onConnections = onConnections,
        )
    }

    /**
     * Take somebody to the screen a row names.
     *
     * The restricted-settings row names a menu inside a page rather than a
     * page, and there is no intent for a menu, so it opens the page and the
     * row's own words say which item to press.
     */
    private fun open(step: Needed) {
        val where = when {
            step.what.contains("screen") -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            else -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", packageName, null),
            )
        }
        runCatching { startActivity(where.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun onDestroy() {
        (started as? Startup.Ready)?.core?.close()
        assembly?.close()
        started = null
        super.onDestroy()
    }
}

@Composable
private fun Conversation(
    driver: TurnDriver,
    handed: MutableState<String?>,
    replay: Replay,
    /** One press of the microphone, permission and all. */
    listen: suspend () -> Heard,
    aloud: Aloud,
    speaking: Speaking,
    /** The door #641 gave this application, and the only one on this screen. */
    onSettings: () -> Unit,
) {
    // Held in composition as well as in the store, because the switch has to
    // repaint when it is tapped and the store is not observable. The store
    // stays the truth: it is what the turn loop reads. The mode and the name
    // were held here too until #712 moved both behind the settings door, where
    // TurnScreen holds its own copy for the same reason.
    var reading by remember { mutableStateOf(aloud.on) }

    // Why the last answer was not read out, until the next turn. Spoken.Silence
    // carries a whole sentence for the person, and a voice that did nothing and
    // said nothing is what it was written against.
    var unspoken by remember { mutableStateOf<String?>(null) }
    val rows by driver.rows.collectAsState()
    val isRunning by driver.isRunning.collectAsState()
    val routing by driver.routing.collectAsState()
    val context = LocalContext.current

    // Cleared before sending, not after. send() starts a turn and returns, so
    // clearing afterwards would still be inside this effect and correct, but
    // clearing first is what makes a recomposition during the turn harmless.
    LaunchedEffect(handed.value) {
        handed.value?.let { shared ->
            handed.value = null
            driver.send(shared)
        }
    }

    // The service follows the driver rather than being started beside a send.
    // Every way a turn can end (answered, failed, interrupted) goes through
    // isRunning, and a notification left behind by one of them is a turn the
    // person believes is still running.
    LaunchedEffect(isRunning) {
        val about = rows.filterIsInstance<Row.Said>().lastOrNull()?.text.orEmpty()
        if (isRunning) {
            // Before the service, not after: the notification carries a Stop
            // button and the system may deliver a press the moment it is
            // posted. Set second, that press finds no callback and does what
            // #470 was about.
            TurnService.onStop = driver::interrupt
            TurnService.begin(context, about)
        } else {
            TurnService.end(context)
            TurnService.onStop = null
        }

        // The banner follows the same signal rather than a second lifecycle
        // that can disagree with it. Every way a turn ends goes through
        // isRunning, and an overlay left behind by one of them says the agent
        // is still driving something.
        DrivingService.connected?.let {
            it.onStop = driver::interrupt
            it.showing(if (isRunning) about else null)
        }
    }

    val asking = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Spoken when a turn ends rather than as it streams. An answer read a
    // fragment at a time is a phone stuttering, and worthSaying needs the
    // finished transcript anyway: a turn that answered and then failed ends
    // failed, and that is only knowable at the end.
    //
    // Keyed on isRunning alongside the service effect rather than inside it,
    // because the two are different subjects: that one is a notification about
    // work, this one is what the work said.
    LaunchedEffect(isRunning) {
        if (isRunning || !reading) return@LaunchedEffect
        worthSaying(rows)?.let { unspoken = (speaking.say(it) as? Spoken.Silence)?.why }
    }

    ChatScreen(
        onSettings = onSettings,
        rows = rows,
        // Read at composition rather than collected. It changes only while a
        // turn is running, and the card is drawn only while one is not, so a
        // flow here would be a subscription that never fires when it is read.
        replay = if (isRunning) emptyList() else replay.steps,
        isRunning = isRunning,
        routing = routing,
        onSend = { text ->
            // Asked at the first send rather than at launch: a permission
            // prompt before anybody has done anything is one people refuse.
            // Context.checkSelfPermission rather than ContextCompat: it is
            // API 23 and this app's floor is 29, and androidx.core 1.19 wants
            // an AGP this build does not have (#357).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                asking.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            driver.send(text)
        },
        onInterrupt = driver::interrupt,
        onListen = listen,
        aloud = reading,
        onAloud = {
            reading = it
            aloud.on = it
            if (!it) unspoken = null
        },
        unspoken = unspoken,
    )
}

/**
 * Whether the native library is present and linkable in this APK.
 *
 * `Core`'s companion calls `System.loadLibrary` when the class initialises, so
 * touching it at all is the test. An empty credential is refused and answers
 * [Startup.CoreRefused], which is a normal answer and means the library
 * answered; a missing or unloadable `.so` throws out of the initialiser.
 */
/** Lines of a reading shown as evidence. Enough to recognise the screen. */
private const val LOOK = 10

internal fun coreLoads(): Boolean = runCatching { Startup.from("") }.isSuccess
