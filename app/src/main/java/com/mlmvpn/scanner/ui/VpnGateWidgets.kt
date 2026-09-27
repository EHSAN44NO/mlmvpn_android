package com.mlmvpn.scanner.ui

// Deliberately in the `ui` package, like VpnGateTab: getNodeFlagEmoji() and faCount() live here
// and are used as-is, with no import and no second copy of the regional-indicator maths.

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.engines.vpngate.SoftEtherProbe
import com.mlmvpn.scanner.engines.vpngate.VpnGateServer
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.theme.BadgeShape
import com.mlmvpn.scanner.ui.theme.ControlShape
import com.mlmvpn.scanner.ui.theme.PanelShape
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The pieces the gateway's list screens are built from.
//
// There used to be two of everything here. The main list's picker had `ServerRow` + `PingBadge` +
// `HandshakeBadge`; the "more servers" browser had `BrowseServerRow` + `PingText` + `HandshakeText`
// -- the same server, the same two measurements, drawn four different ways across two files. The
// two handshake renderers were character-for-character identical `when` blocks over the same five
// failure reasons, and the two ping renderers disagreed about whether to print the unit.
//
// One row, one badge each. What differs between the two lists is what the row is FOR, and that is
// [RowMark]: a list you are picking from marks the current choice with a tick, a list you are
// editing in bulk marks each row with a checkbox, and the archive additionally has to say which
// rows are already in the main list.
// =================================================================================================

/** What a [GatewayServerRow] says about its own state. */
sealed class RowMark {
    /** Nothing to say. */
    object None : RowMark()

    /** The server currently selected to connect through. */
    object Selected : RowMark()

    /** Bulk-edit mode: this row is or is not ticked. */
    data class Check(val checked: Boolean) : RowMark()

    /** Archive only: already in the main list, so there is nothing to add. */
    object Kept : RowMark()
}

/**
 * One server, wherever it is listed.
 *
 * Port, session count and advertised bandwidth are deliberately not shown. The first two leak the
 * endpoint, and the bandwidth figure is measured by VPN Gate's own probes in Japan -- it says
 * nothing useful from here. All three are still available as sort keys, and the detail page shows
 * what is safe to show.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GatewayServerRow(
    server: VpnGateServer,
    ping: Int?,
    handshake: SoftEtherProbe.Result?,
    mark: RowMark,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    showSeparator: Boolean = true,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            // The checkbox LEADS the row, the way a list being edited does everywhere else in the
            // app; the tick TRAILS it, because that is a result rather than a control.
            val lead: Pair<ImageVector, Color>? = when (mark) {
                is RowMark.Kept -> Icons.Default.Bookmark to Ios.Green
                is RowMark.Check ->
                    if (mark.checked) Icons.Default.CheckCircle to Ios.Blue
                    else Icons.Default.RadioButtonUnchecked to Ios.SecondaryLabel.copy(alpha = 0.45f)
                else -> null
            }
            if (lead != null) {
                Icon(
                    lead.first,
                    contentDescription = null,
                    tint = lead.second,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
            }

            Text(getNodeFlagEmoji(server.countryShort), fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.countryLong.ifBlank { server.countryShort },
                    color = Ios.Label,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val note = when {
                    mark is RowMark.Kept -> S(R.string.in_my_list)
                    server.isOfficialRelay -> S(R.string.official_2)
                    else -> null
                }
                if (note != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(note, color = Ios.SecondaryLabel, fontSize = 11.sp)
                }
            }

            if (handshake != null) {
                HandshakeBadge(handshake)
                Spacer(Modifier.width(6.dp))
            }
            PingBadge(ping)

            if (mark is RowMark.Selected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Ios.Blue,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (showSeparator) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 46.dp)
                    .height(0.5.dp)
                    .background(Ios.Separator)
            )
        }
    }
}

/**
 * Outcome of the real handshake test. Shown next to -- never instead of -- the ping, because the
 * whole point is that the two can disagree.
 *
 * A successful probe used to print `ms / 10`: the raw handshake cost runs to four digits and
 * crowded the row, so the last digit was dropped. That left "✓ ۱۸۳" on screen, a number in no unit
 * at all, with the note explaining it living only in the source. What the reader is deciding here
 * is which server to pick, so a band is the answer; [GatewayServerDetailScreen] prints the
 * millisecond figure for anyone who wants it, and the sort still uses the raw value.
 */
@Composable
fun HandshakeBadge(result: SoftEtherProbe.Result) {
    val (label, color) = handshakeLabel(result)
    Text(
        text = label,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(BadgeShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/** The words and the tone [HandshakeBadge] uses, so the detail page can say the same thing. */
@Composable
fun handshakeLabel(result: SoftEtherProbe.Result): Pair<String, Color> = when (result) {
    is SoftEtherProbe.Result.Ok -> when {
        result.ms <= 1000 -> S(R.string.fast) to Ios.Green
        result.ms <= 2500 -> S(R.string.medium_2) to Ios.Yellow
        else -> S(R.string.slow) to Ios.Orange
    }
    is SoftEtherProbe.Result.Failed -> when (result.reason) {
        SoftEtherProbe.Failure.UNREACHABLE -> S(R.string.blocked_2) to Ios.Red
        SoftEtherProbe.Failure.TLS_BLOCKED -> "TLS" to Ios.Red
        SoftEtherProbe.Failure.NOT_SOFTETHER -> S(R.string.disabled) to Ios.Yellow
        SoftEtherProbe.Failure.REFUSED -> S(R.string.failed_2) to Ios.Red
        SoftEtherProbe.Failure.TIMEOUT -> S(R.string.no_response_2) to Ios.Red
    }
}

/** Measured latency, in the app's own bands and in Persian digits like every other count. */
@Composable
fun PingBadge(ping: Int?) {
    val text = when {
        ping == null -> "—"
        ping > 0 -> faCount(ping)
        else -> "✕"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(46.dp),
    ) {
        Text(text, color = pingTone(ping), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (ping != null && ping > 0) {
            Text("ms", color = Ios.SecondaryLabel.copy(alpha = 0.7f), fontSize = 8.sp)
        }
    }
}

/**
 * The gateway's latency ramp.
 *
 * A ping of zero or less is [com.mlmvpn.scanner.engines.vpngate.VpnGatePinger.FAILED] -- an
 * unreachable server, not a slow one -- which is why it is red rather than the far end of the
 * scale.
 */
@Composable
fun pingTone(ping: Int?): Color = when {
    ping == null -> Ios.SecondaryLabel.copy(alpha = 0.55f)
    ping <= 0 -> Ios.Red
    ping < 150 -> Ios.Green
    ping < 400 -> Ios.Yellow
    else -> Ios.Red
}

/**
 * The things you can do to the whole list, in one glass strip.
 *
 * Modelled on [NodeToolbar], the same control on the V2Ray screen. It replaces two separate
 * arrangements of the same actions: a row of chips at the top of the old picker, and a bar of
 * buttons at the bottom of the old browser -- so running a test looked different and sat somewhere
 * different depending on which of the two lists you happened to be in.
 *
 * Labels stay under the glyphs. A row of six unlabelled icons is a guessing game, and this is the
 * densest control on the screen.
 */
@Composable
fun GatewayToolbar(
    busy: Boolean,
    refreshing: Boolean,
    filterActive: Boolean,
    sortActive: Boolean,
    onPing: () -> Unit,
    onProbe: () -> Unit,
    onCountry: () -> Unit,
    onSort: () -> Unit,
    onRefresh: () -> Unit,
    onHelp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .frostedGlass(PanelShape)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GateToolbarAction(Icons.Default.NetworkPing, S(R.string.ping_2), enabled = !busy, onClick = onPing)
        GateToolbarAction(Icons.Default.VerifiedUser, S(R.string.real_test_2), enabled = !busy, onClick = onProbe)
        GateToolbarAction(Icons.Default.Public, S(R.string.country_2), active = filterActive, onClick = onCountry)
        GateToolbarAction(Icons.Default.Sort, S(R.string.sort_3), active = sortActive, onClick = onSort)
        GateToolbarAction(Icons.Default.Refresh, S(R.string.refresh_2), enabled = !refreshing, onClick = onRefresh)
        GateToolbarAction(Icons.Default.HelpOutline, S(R.string.guide_2), onClick = onHelp)
    }
}

@Composable
private fun GateToolbarAction(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // White, like every other action label in the app. Colour on this screen means state, and the
    // state a toolbar button has is "this control is narrowing the list", which the accent says.
    val tint = when {
        !enabled -> Ios.SecondaryLabel.copy(alpha = 0.45f)
        active -> Ios.Green
        else -> Ios.Label
    }
    Column(
        modifier = Modifier
            .clip(ControlShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 10.sp, maxLines = 1)
    }
}

/**
 * The one action-bar button the gateway uses.
 *
 * Replaces `BarButton`, which was a fourth button style in an app that has two: tinted glass with
 * an accent rim for the action that commits something, plain glass for everything else.
 */
@Composable
fun GatewayBarButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    accent: Color? = null,
) {
    val tone = when {
        !enabled -> Color.White.copy(alpha = 0.05f)
        accent != null -> accent.copy(alpha = 0.22f)
        else -> Color.White.copy(alpha = 0.09f)
    }
    Row(
        modifier = modifier
            .clip(PanelShape)
            .background(tone)
            .then(
                if (enabled && accent != null) Modifier.border(1.5.dp, accent, PanelShape)
                else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) Ios.Label else Ios.SecondaryLabel.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            color = if (enabled) Ios.Label else Ios.SecondaryLabel,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A tinted pill for the one-line notice these screens post after a bulk action. */
@Composable
fun GatewaySnack(text: String, tint: Color = Ios.Green) {
    Text(
        text = text,
        color = tint,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(tint.copy(alpha = 0.14f))
            .padding(10.dp),
    )
}

/**
 * What a list shows when it has nothing in it.
 *
 * Neither gateway list had one: an empty archive, or a country filter that matched nothing, drew
 * an empty panel with no explanation of whether it was still loading, misconfigured, or simply
 * empty.
 */
@Composable
fun GatewayEmptyState(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
        )
    }
}
