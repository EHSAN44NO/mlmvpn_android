package com.mlmvpn.scanner.ui

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The cloud coach.
//
// Getting a working config out of this app means: connect a Cloudflare account, install a panel on
// it, pull the configs it generates, then scan, combine, test and connect. Eight moves across three
// screens, and the first three are the ones a new user never completes -- the Cloud screen opens on
// an empty card and says nothing about what to do with it.
//
// [CombineCoach] already covers the last five. This one covers the first three and then hands over,
// numbering its steps 1-3 of 8 so the two read as one assistant rather than two.
//
// Like the combine coach, it DOES the work where the work is doable: it runs the install and the
// fetch itself and reports what actually happened. The one step it cannot do is the first -- typing
// someone's own Cloudflare credentials is theirs to do -- so there it navigates and waits.
// =================================================================================================

object CloudCoach {

    enum class Step {
        /** Not running. */
        IDLE,

        /** Offered on the first visit to an empty Cloud screen. */
        OFFERED,

        /** Waiting for the user to connect a Cloudflare account. */
        ACCOUNT,

        /** Installing a panel on that account. The coach does this. */
        INSTALL,

        /** Pulling configs from the installed panel. The coach does this too. */
        FETCH,

        /** Something produced nothing. [failure] says what. */
        FAILED,
    }

    /** What the coach wants the Cloud screen to do next. The screen owns the code; this asks. */
    enum class Request { NONE, ADD_ACCOUNT, INSTALL, FETCH }

    /**
     * The panel the coach drives.
     *
     * EDG, not BPB. BPB's "get configs" opens its settings screen, where the user has to save
     * before anything is generated -- a fine flow to choose, a bad one to be walked through blind.
     * EDG produces configs on one press, which is the whole point of a guided first run.
     */
    const val PANEL = "EDG"

    /** Steps this coach owns, before the combine coach's five. */
    const val TOTAL = 3

    private const val PREFS = "cloud_coach"
    private const val KEY_STEP = "step"
    private const val KEY_OFFERED = "offered_once"

    private val _step = MutableStateFlow(Step.IDLE)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val _request = MutableStateFlow(Request.NONE)
    val request: StateFlow<Request> = _request.asStateFlow()

    /** What the current step is doing, and how far along. */
    private val _work = MutableStateFlow("")
    val work: StateFlow<String> = _work.asStateFlow()
    private val _workProgress = MutableStateFlow(0f)
    val workProgress: StateFlow<Float> = _workProgress.asStateFlow()

    /** Why it stopped, when it stopped. */
    private val _failure = MutableStateFlow("")
    val failure: StateFlow<String> = _failure.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _step.value = runCatching { Step.valueOf(p.getString(KEY_STEP, "IDLE")!!) }
            .getOrDefault(Step.IDLE)
    }

    private fun persist() {
        prefs?.edit()?.putString(KEY_STEP, _step.value.name)?.apply()
    }

    /**
     * Offer the walkthrough, once, to someone who has not been offered it before.
     *
     * Once ever -- not once per visit. An assistant that asks again every time the user opens the
     * screen stops being an offer and becomes a thing to dismiss.
     */
    fun offerOnce(context: Context) {
        init(context)
        if (_step.value != Step.IDLE) return
        if (CombineCoach.step.value != CombineCoach.Step.IDLE) return
        val p = prefs ?: return
        if (p.getBoolean(KEY_OFFERED, false)) return
        p.edit().putBoolean(KEY_OFFERED, true).apply()
        _step.value = Step.OFFERED
        persist()
    }

    /** Start from the button, with no offer in between. */
    fun start(context: Context, hasAccount: Boolean) {
        init(context)
        prefs?.edit()?.putBoolean(KEY_OFFERED, true)?.apply()
        _failure.value = ""
        _work.value = ""
        _workProgress.value = 0f
        _step.value = if (hasAccount) Step.INSTALL else Step.ACCOUNT
        persist()
    }

    fun accept(hasAccount: Boolean) {
        if (_step.value != Step.OFFERED) return
        _step.value = if (hasAccount) Step.INSTALL else Step.ACCOUNT
        persist()
    }

    fun skip() {
        _step.value = Step.IDLE
        _request.value = Request.NONE
        _work.value = ""
        _workProgress.value = 0f
        _failure.value = ""
        persist()
    }

    fun advance(to: Step) {
        if (_step.value == Step.IDLE) return
        _failure.value = ""
        // The commentary belongs to the step that was running. Carrying it into the next one made
        // the strip claim it was still installing while it sat waiting to be told to fetch.
        _work.value = ""
        _workProgress.value = 0f
        _step.value = to
        persist()
    }

    fun ask(request: Request) {
        _request.value = request
    }

    /** The screen took the request; do not run it twice. */
    fun consume() {
        _request.value = Request.NONE
    }

    fun setWork(text: String, progress: Float = -1f) {
        _work.value = text
        if (progress >= 0f) _workProgress.value = progress
    }

    fun failed(reason: String) {
        _failure.value = reason
        _request.value = Request.NONE
        _step.value = Step.FAILED
        persist()
    }

    /**
     * Configs arrived. Hand the user to the combine coach without a seam.
     *
     * The combine coach picks up at step 4 of 8, so the strip on the scanner screen continues the
     * count the strip on this screen was keeping.
     */
    fun handOff(context: Context, groupId: String, configUri: String) {
        _step.value = Step.IDLE
        _request.value = Request.NONE
        _work.value = ""
        _workProgress.value = 0f
        persist()
        CombineCoach.start(context, groupId, configUri, stepsBefore = TOTAL)
    }

    fun ordinal(step: Step): Int = when (step) {
        Step.ACCOUNT -> 1
        Step.INSTALL -> 2
        Step.FETCH -> 3
        else -> 0
    }

    /** Three of ours plus the combine coach's five: the journey the user is actually on. */
    val total: Int get() = TOTAL + CombineCoach.TOTAL

    /**
     * Why an install or a fetch produced nothing, from conditions the app can actually observe.
     *
     * Every branch is a real check. The point of the coach is that a beginner who hits one of these
     * is told which of them it was, instead of a toast with a Cloudflare error code in it.
     */
    fun diagnose(
        emailVerified: Boolean,
        hasSubdomain: Boolean,
        installed: Boolean,
        rawError: String,
    ): String = when {
        !emailVerified ->
            S(R.string.cloudflare_has_not_verified_this_account_s) +
                S(R.string.open_the_email_cloudflare_sent_when_you) +
                S(R.string.carry_on_here)

        !hasSubdomain ->
            S(R.string.this_account_has_no_workers_dev_subdomain) +
                S(R.string.this_card_tap_create_subdomain_and_then)

        rawError.contains("10000") || rawError.contains("Authentication", ignoreCase = true) ->
            S(R.string.cloudflare_rejected_this_account_s_key_if) +
                S(R.string.the_simplest_route_is_to_remove_the)

        rawError.contains("ERR_ACCOUNT_HAS_SUBDOMAIN") ->
            S(R.string.this_account_s_subdomain_name_is_taken) +
                S(R.string.sign_in_on_cloudflare_s_site_once)

        !installed ->
            S(R.string.the_panel_could_not_be_installed_on) + (if (rawError.isBlank()) "." else ": $rawError") +
                S(R.string.usually_means_cloudflare_has_just_rejected_the)

        else ->
            S(R.string.the_panel_installed_but_returned_no_configs) + (if (rawError.isBlank()) "." else ": $rawError") +
                S(R.string.if_you_have_only_just_installed_it)
    }
}

/** The offer, asked once, on an empty Cloud screen. */
@Composable
fun CloudOfferDialog(onYes: () -> Unit, onNo: () -> Unit) {
    com.mlmvpn.scanner.ui.settings.IosAlert(
        title = S(R.string.shall_we_set_it_up_together),
        message = S(R.string.this_screen_builds_your_own_private_server) +
            S(R.string.step_by_step_we_connect_the_account) +
            S(R.string.carry_on_to_a_working_connection_you),
        onDismiss = onNo,
        actions = listOf(
            com.mlmvpn.scanner.ui.settings.IosAlertAction(S(R.string.i_know_how), onClick = onNo),
            com.mlmvpn.scanner.ui.settings.IosAlertAction(S(R.string.all_right_start), onClick = onYes, preferred = true),
        ),
    )
}

/** The same strip the combine coach uses, counting the same journey. */
@Composable
fun CloudCoachBar(
    step: CloudCoach.Step,
    onSkip: () -> Unit,
    body: String,
    working: Boolean = false,
    workText: String = "",
    workProgress: Float = 0f,
    failed: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) = CoachBar(
    index = CloudCoach.ordinal(step),
    total = CloudCoach.total,
    onSkip = onSkip,
    body = body,
    working = working,
    workText = workText,
    workProgress = workProgress,
    failed = failed,
    actionLabel = actionLabel,
    onAction = onAction,
)
