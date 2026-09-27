package com.mlmvpn.scanner.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.theme.PanelShape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The combine coach.
//
// Combining a panel config with clean IPs is the most useful thing this app does and the least
// discoverable: it crosses four screens in a fixed order, and two of the moves are inside dialogs
// you have to know to open. Nobody finds that by exploring.
//
// The first version of this coach only NARRATED that sequence -- it told the user to press things
// and then claimed the result. That is worse than no coach: after the scan it announced
// "combinations built" when nothing had been built, because building them needs the combine sheet
// and a group choice that the user had not made.
//
// So the coach does the work. On step 3 it combines the group it started from against the IPs the
// scan found, measures every combination for real, transfers the ones that answered, and only then
// hands over to the connection screen. If any of that produces nothing it says WHY, from conditions
// the app can actually observe -- see CombineEngine.diagnose.
// =================================================================================================

object CombineCoach {

    enum class Step {
        /** Not running. */
        IDLE,

        /** Configs just arrived from a panel; the offer is up. */
        OFFERED,

        /** On the scanner. Waiting for a count and a start. */
        SET_COUNT,

        /** A scan is running. */
        SCANNING,

        /** The coach is combining, measuring and transferring. Nothing for the user to do. */
        WORKING,

        /** On the connection list, with the transferred configs. */
        MEASURE,

        /** Measured. Waiting for a connection. */
        CONNECT,

        /** Connected. */
        DONE,

        /**
         * A Config Studio run only: writing the clean addresses into the subscriber's own link.
         *
         * This is where the two runs part. A cloud combine ends on THIS phone -- the configs are
         * for the person holding it, so the last move is to connect. A Studio combine is done on
         * behalf of somebody who is not here, so the last move is to put the addresses into their
         * subscription; connecting to them here would prove nothing about their line and would
         * leave the person exactly as stuck as they were.
         */
        PUBLISHING,

        /** A Config Studio run finished: the subscriber is on the clean addresses. */
        PUBLISHED,

        /** Something produced nothing. [failure] says what. */
        FAILED,
    }

    private const val PREFS = "combine_coach"
    private const val KEY_STEP = "step"
    private const val KEY_GROUP = "group_id"
    private const val KEY_URI = "uri"
    private const val KEY_OFFSET = "offset"
    private const val KEY_PRODUCED = "produced_group_id"
    private const val KEY_STUDIO = "studio_user"
    private const val KEY_PUBLISHED = "published"

    private val _step = MutableStateFlow(Step.IDLE)
    val step: StateFlow<Step> = _step.asStateFlow()

    /** The cloud group the flow is about. The combine needs the whole group, not one config. */
    private val _groupId = MutableStateFlow("")
    val groupId: StateFlow<String> = _groupId.asStateFlow()

    /** One config from that group, used as the scanner's base. */
    private val _uri = MutableStateFlow("")
    val uri: StateFlow<String> = _uri.asStateFlow()

    /** What step 3 is doing right now, and how far along. */
    private val _work = MutableStateFlow("")
    val work: StateFlow<String> = _work.asStateFlow()
    private val _workProgress = MutableStateFlow(0f)
    val workProgress: StateFlow<Float> = _workProgress.asStateFlow()

    /** How many configs step 3 actually moved to the connection list. */
    private val _transferred = MutableStateFlow(0)
    val transferred: StateFlow<Int> = _transferred.asStateFlow()

    /** Why it stopped, when it stopped. */
    private val _failure = MutableStateFlow("")
    val failure: StateFlow<String> = _failure.asStateFlow()

    /**
     * The Config Studio subscriber this run is for; blank on an ordinary cloud combine.
     *
     * One field carries both facts the flow needs -- THAT it is a Studio run and WHO it is for --
     * because every step that behaves differently also has to name the person: the source is read
     * from the handoff instead of a cloud group, the finished group is stamped with their ids, and
     * the last step writes their subscription. An assistant that did that work while saying
     * "config" would be doing the one thing that must not be gotten wrong, silently.
     *
     * The rest of who they are -- installation, user id, config ids -- stays in
     * [com.mlmvpn.scanner.data.studio.StudioCombineHandoff], which already persists it and is
     * already what the scanner's own combine sheet reads.
     */
    private val _studioUser = MutableStateFlow("")
    val studioUser: StateFlow<String> = _studioUser.asStateFlow()

    val isStudio: Boolean get() = _studioUser.value.isNotBlank()

    /** What the publish actually did, in the sentence the last step shows. */
    private val _published = MutableStateFlow("")
    val published: StateFlow<String> = _published.asStateFlow()

    /**
     * The combined group step 3 has already built, if it got that far.
     *
     * Step 3 lives in a `LaunchedEffect` on the scanner screen, so anything that removes that
     * screen from composition -- opening the base-config picker, the process being rebuilt --
     * cancels it. It cancels AFTER the group has been saved and before the measuring is done, and
     * re-entering then ran the whole step again and saved a SECOND identical group. Remembering
     * what was built means a resumed step 3 measures it instead of rebuilding it.
     */
    private val _producedGroupId = MutableStateFlow("")
    val producedGroupId: StateFlow<String> = _producedGroupId.asStateFlow()

    /**
     * How many steps happened before this coach took over.
     *
     * When the cloud coach hands off, the user has already done three numbered steps; restarting
     * the count at "قدم ۱ از ۵" would read as a second, unrelated assistant rather than the same
     * one continuing. With an offset the strip counts straight through to eight.
     */
    private val _offset = MutableStateFlow(0)
    val offset: StateFlow<Int> = _offset.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _step.value = runCatching { Step.valueOf(p.getString(KEY_STEP, "IDLE")!!) }.getOrDefault(Step.IDLE)
        _groupId.value = p.getString(KEY_GROUP, "") ?: ""
        _uri.value = p.getString(KEY_URI, "") ?: ""
        _offset.value = p.getInt(KEY_OFFSET, 0)
        _producedGroupId.value = p.getString(KEY_PRODUCED, "") ?: ""
        _studioUser.value = p.getString(KEY_STUDIO, "") ?: ""
        _published.value = p.getString(KEY_PUBLISHED, "") ?: ""
    }

    private fun persist() {
        prefs?.edit()
            ?.putString(KEY_STEP, _step.value.name)
            ?.putString(KEY_GROUP, _groupId.value)
            ?.putString(KEY_URI, _uri.value)
            ?.putInt(KEY_OFFSET, _offset.value)
            ?.putString(KEY_PRODUCED, _producedGroupId.value)
            ?.putString(KEY_STUDIO, _studioUser.value)
            ?.putString(KEY_PUBLISHED, _published.value)
            ?.apply()
    }

    /** A panel just handed over a group. Offer the combine, unless a flow is already running. */
    fun offer(context: Context, groupId: String, configUri: String) {
        init(context)
        if (_step.value != Step.IDLE) return
        if (groupId.isBlank() || configUri.isBlank()) return
        _groupId.value = groupId
        _uri.value = configUri
        // An offer is always the start of the journey, so it counts from one -- otherwise a run
        // left half-finished by a previous cloud walkthrough would number this one "قدم ۱ از ۸".
        _offset.value = 0
        _step.value = Step.OFFERED
        persist()
    }

    fun accept() {
        if (_step.value != Step.OFFERED) return
        _step.value = Step.SET_COUNT
        persist()
    }

    /**
     * Start straight from a button the user pressed, with no offer in between.
     *
     * The offer exists for the case where configs simply arrived and the user was not asking for
     * anything; a "combine these" button IS the answer to that question, so asking it again would
     * be a dialog whose only honest option is yes. Takes over from any flow already running.
     */
    fun start(context: Context, groupId: String, configUri: String, stepsBefore: Int = 0) {
        init(context)
        _offset.value = stepsBefore
        if (groupId.isBlank() || configUri.isBlank()) return
        _groupId.value = groupId
        _uri.value = configUri
        _failure.value = ""
        _transferred.value = 0
        _work.value = ""
        _workProgress.value = 0f
        _producedGroupId.value = ""
        _studioUser.value = ""
        _published.value = ""
        _step.value = Step.SET_COUNT
        persist()
    }

    /**
     * Start the same journey for one Config Studio subscriber.
     *
     * Separate from [start] only in what it remembers: there is no cloud group to name, and the
     * person has to be. Everything the scanner does afterwards -- the count, the sweep, building
     * the combinations, measuring them -- is the same code on the same screen, which is the point.
     * The one added step is at the end, where the addresses go into their subscription instead of
     * into this phone's connection list.
     *
     * Called after [com.mlmvpn.scanner.data.studio.StudioCombineHandoff.arm], never instead of it:
     * the coach carries the name, the handoff carries the configs, and the scanner needs both.
     */
    fun startStudio(context: Context, username: String, configUri: String) {
        init(context)
        if (configUri.isBlank()) return
        _offset.value = 0
        _groupId.value = ""
        _uri.value = configUri
        _studioUser.value = username
        _failure.value = ""
        _published.value = ""
        _transferred.value = 0
        _work.value = ""
        _workProgress.value = 0f
        _producedGroupId.value = ""
        _step.value = Step.SET_COUNT
        persist()
    }

    fun skip() {
        _step.value = Step.IDLE
        _groupId.value = ""
        _uri.value = ""
        _offset.value = 0
        _failure.value = ""
        _transferred.value = 0
        _producedGroupId.value = ""
        _studioUser.value = ""
        _published.value = ""
        _work.value = ""
        _workProgress.value = 0f
        persist()
    }

    /** Step 3 built this group; a resumed step 3 measures it rather than building another. */
    fun setProducedGroup(id: String) {
        _producedGroupId.value = id
        persist()
    }

    fun advance(to: Step) {
        if (_step.value == Step.IDLE) return
        _step.value = to
        persist()
    }

    /** Step 3's running commentary. */
    fun setWork(text: String, progress: Float = -1f) {
        _work.value = text
        if (progress >= 0f) _workProgress.value = progress
    }

    fun succeeded(count: Int) {
        _transferred.value = count
        _failure.value = ""
        if (isStudio) {
            // The built group is deliberately NOT forgotten here. On a cloud run this was the end
            // of the slow work; on a Studio run the slow work is the publish that comes next, and
            // that is now the step a killed process can be resumed into -- which it can only be
            // against the group it already built.
            _step.value = Step.PUBLISHING
        } else {
            _producedGroupId.value = ""
            _step.value = Step.MEASURE
        }
        persist()
    }

    /** The subscriber is on the clean addresses; [summary] is what was written. */
    fun published(summary: String) {
        _published.value = summary
        _failure.value = ""
        _producedGroupId.value = ""
        _work.value = ""
        _workProgress.value = 0f
        _step.value = Step.PUBLISHED
        persist()
    }

    fun failed(reason: String) {
        _failure.value = reason
        _step.value = Step.FAILED
        persist()
    }

    fun finish() {
        skip()
    }

    /** Which step of the whole journey this is -- this coach's five, plus anything before them. */
    fun ordinal(step: Step): Int = when (step) {
        Step.SET_COUNT -> 1
        Step.SCANNING -> 2
        Step.WORKING -> 3
        // Two fourth steps and two fifth ones, because the run forks after the measuring: a cloud
        // combine ends by connecting on this phone, a Studio combine by writing somebody's
        // subscription. Only one pair can happen in a run, so the count stays at five either way.
        Step.MEASURE, Step.PUBLISHING -> 4
        Step.CONNECT, Step.PUBLISHED -> 5
        else -> 0
    }.let { if (it == 0) 0 else it + _offset.value }

    /** Steps in the journey the user is actually on, which may be longer than this coach. */
    val total: Int get() = TOTAL + _offset.value

    const val TOTAL = 5
}

/** The offer, asked once, where the configs arrived. */
@Composable
fun CombineOfferDialog(onYes: () -> Unit, onNo: () -> Unit) {
    com.mlmvpn.scanner.ui.settings.IosAlert(
        title = S(R.string.shall_we_combine_the_configs_with_a),
        message = S(R.string.combining_means_riding_these_same_configs_on) +
            S(R.string.usually_several_times_faster_than_the_raw) +
            S(R.string.myself_you_can_leave_it_at_any),
        onDismiss = onNo,
        actions = listOf(
            com.mlmvpn.scanner.ui.settings.IosAlertAction(S(R.string.no_not_now), onClick = onNo),
            com.mlmvpn.scanner.ui.settings.IosAlertAction(S(R.string.yes_start), onClick = onYes, preferred = true),
        ),
    )
}

/**
 * The strip that rides along.
 *
 * Three shapes: an instruction with nothing to press (the move is on this screen), an instruction
 * with a button (the move is a navigation the coach can make), and a working state with a progress
 * bar and no controls at all except the dismiss.
 */
@Composable
fun CombineCoachBar(
    step: CombineCoach.Step,
    onSkip: () -> Unit,
    body: String,
    working: Boolean = false,
    workText: String = "",
    workProgress: Float = 0f,
    failed: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) = CoachBar(
    index = CombineCoach.ordinal(step),
    total = CombineCoach.total,
    onSkip = onSkip,
    body = body,
    working = working,
    workText = workText,
    workProgress = workProgress,
    failed = failed,
    actionLabel = actionLabel,
    onAction = onAction,
)

/**
 * The strip itself, with no idea which coach is driving it.
 *
 * Every guided flow in the app renders through this one composable, so a user who has seen the
 * cloud assistant recognises the combine assistant on sight -- and a section that grows an
 * assistant later inherits the look for free rather than inventing a fourth kind of banner.
 */
@Composable
fun CoachBar(
    index: Int,
    total: Int,
    onSkip: () -> Unit,
    body: String,
    working: Boolean = false,
    workText: String = "",
    workProgress: Float = 0f,
    failed: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val n = index
    val accent = if (failed) Ios.Orange else Ios.Green

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(PanelShape)
            .background(accent.copy(alpha = 0.14f))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (n > 0) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.30f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(faCount(n), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (failed) S(R.string.the_guide_was_stopped) else S(R.string.step_of, faCount(n), faCount(total)),
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.Close,
                contentDescription = S(R.string.skip_the_guide),
                tint = Ios.SecondaryLabel,
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onSkip),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(body, color = Ios.Label, fontSize = 13.sp, lineHeight = 21.sp)

        if (working) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = accent,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(workText, color = Ios.SecondaryLabel, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
            if (workProgress > 0f) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = workProgress,
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    color = accent,
                    trackColor = Color.White.copy(alpha = 0.14f),
                )
            }
        }

        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.22f))
                    .clickable(onClick = onAction)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(actionLabel, color = Ios.Label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Shown once, when the tunnel finally comes up on a combined config. */
@Composable
fun CombineDoneDialog(onClose: () -> Unit) {
    com.mlmvpn.scanner.ui.settings.IosAlert(
        title = S(R.string.all_done),
        message = S(R.string.you_are_connected_to_the_fastest_combination) +
            S(R.string.you_will_see_this_offer_again_and),
        onDismiss = onClose,
        actions = listOf(
            com.mlmvpn.scanner.ui.settings.IosAlertAction(S(R.string.great), onClick = onClose, preferred = true),
        ),
    )
}
