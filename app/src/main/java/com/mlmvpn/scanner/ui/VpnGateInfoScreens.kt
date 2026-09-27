package com.mlmvpn.scanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.engines.vpngate.SoftEtherProbe
import com.mlmvpn.scanner.engines.vpngate.VpnGateServer
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The gateway's two reading screens.
//
// Both used to be missing or misplaced. The help was a full-height `Dialog` opened from inside the
// server picker -- a third modal layer, drawn on a Material `Surface` with its own title bar and
// its own close button, in an app whose every other explanation is a pushed page. And there was no
// detail page at all: fifteen columns are parsed off the VPN Gate CSV and fourteen of them were
// thrown away, so a user comparing two servers in the same country had nothing to compare.
// =================================================================================================

/**
 * Everything one server is willing to say about itself.
 *
 * The endpoint is deliberately absent -- no address, no port. Those are what someone reading over
 * a shoulder, or a screenshot, would need in order to block it, and the app has no reason to print
 * them. The hostname is shown because it is what the archive is keyed by and what a user has to
 * quote to say which server they mean.
 */
@Composable
fun GatewayServerDetailScreen(
    server: VpnGateServer,
    ping: Int?,
    handshake: SoftEtherProbe.Result?,
    isKept: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onKeepToggle: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    IosScreen(
        title = server.countryLong.ifBlank { server.countryShort },
        onBack = onBack,
        backLabel = S(R.string.servers_r2),
    ) {
        Spacer(Modifier.height(14.dp))

        SettingsSectionHeader(S(R.string.your_measurements))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.ping_r2),
                icon = Icons.Default.NetworkPing,
                tint = Ios.Gray,
                value = when {
                    ping == null -> S(R.string.not_tested)
                    ping > 0 -> faCount(ping) + " ms"
                    else -> S(R.string.no_response_r2)
                },
                showChevron = false,
            )
            Separator()
            SettingsRow(
                title = S(R.string.real_test),
                icon = Icons.Default.VerifiedUser,
                tint = Ios.Gray,
                // The exact millisecond figure, which the row badge deliberately reduces to a
                // band. This is the page that has room for it.
                value = when (handshake) {
                    null -> S(R.string.not_tested)
                    is SoftEtherProbe.Result.Ok -> faCount(handshake.ms) + " ms"
                    is SoftEtherProbe.Result.Failed -> handshakeLabel(handshake).first
                },
                showChevron = false,
            )
        }
        SettingsFooter(
            S(R.string.ping_only_says_how_long_a_packet) +
                S(R.string.handshake_which_makes_it_far_more_reliable) +
                S(R.string.disagree_with_each_other)
        )

        SettingsSectionHeader(S(R.string.the_server_itself))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.type),
                value = if (server.isOfficialRelay) S(R.string.official_r2) else S(R.string.volunteer),
                showChevron = false,
            )
            Separator()
            SettingsRow(title = S(R.string.country_r2), value = server.countryShort, showChevron = false)
            Separator()
            // Clamped rather than cast: the score is a Long and faGrouped takes an Int, so an
            // outlier would silently wrap to a negative number rather than being obviously large.
            SettingsRow(
                title = S(R.string.vpn_gate_score),
                value = faGrouped(server.score.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()),
                showChevron = false,
            )
            Separator()
            SettingsRow(title = S(R.string.uptime), value = uptimeText(server.uptimeMs), showChevron = false)
            Separator()
            SettingsRow(title = S(R.string.active_sessions), value = faCount(server.numSessions), showChevron = false)
            Separator()
            SettingsRow(title = S(R.string.logging_policy), value = logPolicy(server.logType), showChevron = false)
            if (server.operator.isNotBlank()) {
                Separator()
                SettingsRow(title = S(R.string.server_operator), value = server.operator, showChevron = false)
            }
            Separator()
            SettingsRow(title = S(R.string.hostname), value = server.hostName, showChevron = false)
        }
        SettingsFooter(
            if (server.isOfficialRelay) {
                S(R.string.this_server_runs_on_the_service_s)
            } else {
                S(R.string.a_volunteer_shares_this_server_from_their)
            }
        )

        if (server.message.isNotBlank()) {
            SettingsSectionHeader(S(R.string.message_from_the_operator))
            SettingsGroup {
                // Untrusted text written by whoever runs the server, so it is shown as a quoted
                // note in its own card rather than woven into the app's own copy.
                Text(
                    server.message,
                    color = Ios.SecondaryLabel,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        SettingsSectionHeader(S(R.string.actions))
        SettingsGroup {
            SettingsActionRow(
                label = if (isSelected) S(R.string.selected_right_now) else S(R.string.select_for_connecting),
                icon = Icons.Default.Public,
                enabled = !isSelected,
            ) { onSelect(); onBack() }
            Separator()
            SettingsActionRow(
                label = if (isKept) S(R.string.remove_from_my_list) else S(R.string.add_to_my_list),
                icon = if (isKept) Icons.Default.BookmarkRemove else Icons.Default.Bookmark,
            ) { onKeepToggle() }
            Separator()
            SettingsActionRow(
                label = S(R.string.delete_this_server),
                icon = Icons.Default.DeleteSweep,
                tint = Ios.Red,
            ) { onRemove(); onBack() }
        }
        SettingsFooter(
            S(R.string.my_list_is_the_list_the_connect) +
                S(R.string.stays_even_after_vpn_gate_drops_it)
        )

        Spacer(Modifier.height(32.dp))
    }
}

private fun uptimeText(ms: Long): String {
    if (ms <= 0L) return S(R.string.unknown_r2)
    val minutes = ms / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 60 -> faCount(minutes.toInt()) + S(R.string.minutes_r2)
        hours < 24 -> faCount(hours.toInt()) + S(R.string.hours)
        days < 30 -> faCount(days.toInt()) + S(R.string.days_r2)
        else -> faCount((days / 30).toInt()) + S(R.string.months)
    }
}

/**
 * VPN Gate's `LogType` column, in words.
 *
 * The raw values are short English tokens the operator picks when registering, and "2week" on its
 * own tells a reader nothing about what it is two weeks OF.
 */
private fun logPolicy(raw: String): String = when (raw.trim().lowercase()) {
    "no" -> S(R.string.no_logs)
    "2weeks", "2week" -> S(R.string.keeps_them_two_weeks)
    "1month", "1months" -> S(R.string.keeps_them_one_month)
    "3months", "3month" -> S(R.string.keeps_them_three_months)
    "unknown", "" -> S(R.string.not_stated)
    else -> raw
}

// =================================================================================================

/**
 * What the list is and how to use it.
 *
 * A pushed page rather than the `Dialog` it used to be. The concepts here -- a handshake test that
 * can disagree with a ping, an archive that outgrows the live window -- are not guessable from the
 * icons, and they are the difference between the feature working and the user concluding it does
 * not.
 */
@Composable
fun GatewayHelpScreen(onBack: () -> Unit) {
    IosScreen(title = S(R.string.server_guide), onBack = onBack, backLabel = S(R.string.servers_r2)) {
        Spacer(Modifier.height(14.dp))

        SettingsFooter(
            S(R.string.these_servers_are_shared_by_volunteers_around) +
                S(R.string.the_app_keeps_every_server_it_has) +
                S(R.string.much_larger_than_the_number_of_servers)
        )

        SettingsSectionHeader(S(R.string.two_lists))
        SettingsGroup {
            HelpRow(
                Icons.Default.Public,
                Ios.Blue,
                S(R.string.my_list_r2),
                S(R.string.the_list_the_connect_button_chooses_from) +
                    S(R.string.whatever_you_added_from_the_archive_minus),
            )
            Separator()
            HelpRow(
                Icons.Default.TravelExplore,
                Ios.Teal,
                S(R.string.archive_r2),
                S(R.string.every_server_the_app_has_ever_seen) +
                    S(R.string.do_not_appear_in_the_connect_button),
            )
        }

        SettingsSectionHeader(S(R.string.two_tests))
        SettingsGroup {
            HelpRow(
                Icons.Default.NetworkPing,
                Ios.Gray,
                S(R.string.ping_r2),
                S(R.string.only_measures_how_long_your_packet_takes) +
                    S(R.string.the_connection_will_succeed),
            )
            Separator()
            HelpRow(
                Icons.Default.VerifiedUser,
                Ios.Green,
                S(R.string.real_test),
                S(R.string.actually_performs_the_handshake_with_the_server) +
                    S(R.string.your_own_line_it_is_slower_but) +
                    S(R.string.more_reliable_than_ping_if_a_server) +
                    S(R.string.shows_you_why),
            )
        }
        SettingsFooter(
            S(R.string.the_real_test_result_is_shown_next) +
                S(R.string.to_see_the_exact_number_press_and)
        )

        SettingsSectionHeader(S(R.string.controls))
        SettingsGroup {
            HelpRow(
                Icons.Default.Sort,
                Ios.Gray,
                S(R.string.sorting),
                S(R.string.the_default_is_tested_first_each_mode),
            )
            Separator()
            HelpRow(
                Icons.Default.Public,
                Ios.Gray,
                S(R.string.country_r2),
                S(R.string.limits_the_list_to_one_or_more) +
                    S(R.string.stay_at_the_top_of_the_list),
            )
            Separator()
            HelpRow(
                Icons.Default.Bookmark,
                Ios.Gray,
                S(R.string.select_r2),
                S(R.string.the_select_button_at_the_top_lets) +
                    S(R.string.or_remove_them_together),
            )
            Separator()
            HelpRow(
                Icons.Default.DeleteSweep,
                Ios.Red,
                S(R.string.delete_dead_servers),
                S(R.string.only_removes_servers_that_were_tested_and) +
                    S(R.string.never_deleted),
            )
        }

        SettingsSectionHeader(S(R.string.suggestion))
        SettingsFooter(
            S(R.string.the_first_time_tap_real_test_wait) +
                S(R.string.list_if_none_turns_green_go_to) +
                S(R.string.and_run_the_same_test_there)
        )

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun HelpRow(icon: ImageVector, tint: Color, title: String, body: String) {
    // The app's own row shape rather than the dialog's hand-built 34dp tinted box: a titled
    // paragraph is a row with a glyph and a subtitle, which SettingsRow already is.
    SettingsRow(title = title, icon = icon, tint = tint, subtitle = body, showChevron = false)
}
