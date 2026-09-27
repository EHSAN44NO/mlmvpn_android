package com.mlmvpn.scanner.engines.game.booster.session

import com.mlmvpn.scanner.engines.game.booster.decide.Claims
import com.mlmvpn.scanner.engines.game.booster.decide.ProbeMethod
import com.mlmvpn.scanner.engines.game.booster.model.RouteKind
import com.mlmvpn.scanner.engines.game.booster.probe.PathStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BoostPhase { IDLE, MEASURING, APPLYING, ACTIVE, FAILED }

enum class StageId { CHECK, REGION, DIRECT, WARP, DECIDE, APPLY }

enum class StageStatus { PENDING, RUNNING, DONE, SKIPPED, FAILED }

/** One line of the stage list: its state and, once measured, the numbers behind it. */
data class Stage(val id: StageId, val status: StageStatus = StageStatus.PENDING, val stats: PathStats? = null)

/** Why WARP was or was not part of the comparison -- said on the card, never silently. */
enum class WarpNote {
    MEASURED, SKIPPED_MEMORY, SKIPPED_CHOICE, NOT_READY, FAILED, NOT_TRIED,
    /** Players on this operator measured WARP for this game many times and it almost never won. */
    SKIPPED_CROWD,
}

/** Whether the game's login servers answer from this line, and what fixed it. */
enum class AccessState { OK, FIXED_BY_DNS, NEEDS_TUNNEL, BLOCKED, UNKNOWN }

enum class VerdictKind {
    STAY_DIRECT, SWITCH, MUST_TUNNEL, NOTHING_WORKS,
    /**
     * The test points of the game's region did not answer on the direct line, but nothing says
     * the game cannot: it stays on the direct line, and the card offers WARP as a button.
     */
    UNMEASURED,
    /** The route the numbers pointed to kept failing for this player here; the other one was taken. */
    LEARNED,
}

/** What fixed a part of the game on this boost. */
enum class FixedBy { CLEAN_DNS, SANCTION_DNS, FRAGMENT, WARP }

/** One part of the game that is not simply open, for the card: what is in its way and what fixed it. */
data class ClassLine(
    val cls: com.mlmvpn.scanner.engines.game.booster.model.TrafficClass,
    val obstacle: com.mlmvpn.scanner.engines.game.booster.doctor.Obstacle,
    /** Fixed on this boost: by clean DNS, the anti-sanction DNS, fragmenting, or WARP. */
    val fixed: Boolean,
    val fixedBy: FixedBy? = null,
)

/** One region of the game as the sweep saw it, for the card's region list. */
data class RegionRow(
    val key: String,
    val labelFa: String,
    val labelEn: String,
    val p50: Int?,
    val answered: Boolean,
    val status: com.mlmvpn.scanner.engines.game.booster.model.RegionCatalog.Status,
)

/** The result card's content: what was measured, what was chosen, and what may be claimed. */
data class BoostOutcome(
    val gameId: String,
    val gameName: String,
    val gamePackage: String?,
    val regionKey: String,
    val anchorLabelFa: String,
    val anchorLabelEn: String,
    val route: RouteKind,
    val verdict: VerdictKind,
    val method: ProbeMethod,
    val direct: PathStats?,
    val warp: PathStats?,
    val chosen: PathStats?,
    val claims: Claims,
    val warpNote: WarpNote,
    val access: AccessState,
    val wifiPowerSaveOff: Boolean,
    val tips: List<Preflight.Tip>,
    /** The route was reused from this network's memory rather than re-measured in full. */
    val fromMemory: Boolean,
    /** Something went wrong applying the route; the booster fell back to the direct line. */
    val applyFellBack: Boolean = false,
    /** The region has a UDP echo point, so a TCP measurement means UDP got no answer. */
    val echoAvailable: Boolean = true,
    /** What kind of game this is on this line (1–5), when the doctor could check anything. */
    val kind: com.mlmvpn.scanner.engines.game.booster.doctor.GameKind? = null,
    /** The parts of the game that are not simply open, and whether this boost fixed each. */
    val classLines: List<ClassLine> = emptyList(),
    /** Which region to pick inside the game, from the sweep; null when no region answered. */
    val regionAdvice: com.mlmvpn.scanner.engines.game.booster.decide.RegionAdvisor.Advice? = null,
    val regions: List<RegionRow> = emptyList(),
    /** The anti-sanction DNS now carrying the refused parts, by display name. */
    val sanctionProviderFa: String? = null,
    val sanctionProviderEn: String? = null,
    /** A sign-in refused on country that no anti-sanction DNS opened on this line. */
    val sanctionUnfixed: Boolean = false,
    /** The region's UDP test point answered nothing directly: jitter is approximate, the game may still work. */
    val udpSilent: Boolean = false,
    /** Show «امتحان با WARP»: the line could not be judged, or only WARP answered. */
    val offerWarp: Boolean = false,
    /** WARP measured better, but the sign-in fix needs the direct line, so the direct line was kept. */
    val warpYieldedToSignIn: Boolean = false,
    /** The game is not installed: measured, nothing applied. */
    val measureOnly: Boolean = false,
    /** The measured region, when the sweep moved the measurement away from the one the player is on. */
    val measuredRegionKey: String? = null,
    val noteFa: String? = null,
    val noteEn: String? = null,
    /** How the applied plan did for this game on this operator, when enough players said: % worked, votes. */
    val crowdOkPct: Int? = null,
    val crowdVotes: Int? = null,
    /** The route the numbers pointed to kept failing for this player here; the other one was taken. */
    val escalated: Boolean = false,
    /** The WARP measured was its TCP variant, because this line drops foreign UDP. */
    val warpOverTcp: Boolean = false,
    /** On Wi-Fi with mobile data up behind it: how much lower the typical ping was on mobile data. */
    val cellularBetterByMs: Int? = null,
    /** The problem the player said they have, when the boost was started from «چه مشکلی داری؟». */
    val symptom: com.mlmvpn.scanner.engines.game.booster.model.Symptom? = null,
)

enum class LiveStatus { GOOD, DEGRADED, UNSTABLE, LOSSY, DOWN }

data class LiveReading(
    val p50: Int?,
    val jitter: Double?,
    val loss: Double?,
    val lossMeaningful: Boolean,
    val status: LiveStatus,
    /** Recent single readings in ms (null = no answer), oldest first, for the sparkline. */
    val series: List<Int?>,
    val at: Long,
    /** What the last lag spike came with, while it is recent. */
    val lastCause: SpikeWatch.Cause? = null,
    /** Lag spikes so far this session. */
    val spikes: Int = 0,
)

data class BoostUiState(
    val phase: BoostPhase = BoostPhase.IDLE,
    val gameId: String? = null,
    val stages: List<Stage> = emptyList(),
    val outcome: BoostOutcome? = null,
    val live: LiveReading? = null,
    val startedAt: Long = 0L,
    /** A short machine key for the failure, mapped to a string by the UI. */
    val error: String? = null,
)

/**
 * Process-wide state of the game booster, observed by the screen and written by the service.
 * One boost at a time: starting another replaces the running one.
 */
object GameBoostController {
    private val _state = MutableStateFlow(BoostUiState())
    val state: StateFlow<BoostUiState> = _state.asStateFlow()

    internal fun update(change: (BoostUiState) -> BoostUiState) = _state.update(change)

    internal fun stage(id: StageId, status: StageStatus, stats: PathStats? = null) = update { s ->
        s.copy(stages = s.stages.map { if (it.id == id) it.copy(status = status, stats = stats ?: it.stats) else it })
    }

    internal fun reset() = update { BoostUiState() }

    val isActive: Boolean
        get() = _state.value.phase.let { it == BoostPhase.MEASURING || it == BoostPhase.APPLYING || it == BoostPhase.ACTIVE }
}
