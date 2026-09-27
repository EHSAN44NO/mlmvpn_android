package com.mlmvpn.scanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.core.tunnel.RegionMemory
import com.mlmvpn.scanner.engines.vpngate.VpnGateGeo
import com.mlmvpn.scanner.engines.vpngate.VpnGateServer
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGlyph
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.theme.BadgeShape
import com.mlmvpn.scanner.ui.theme.ControlShape
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * Which countries the server list is narrowed to.
 *
 * This replaces two controls that had nothing in common but their job. The main list filtered by
 * country through a Material `DropdownMenu` -- eighty unlabelled two-letter codes in a scrolling
 * popup, with no search and no way to pick two. The archive browser filtered through a whole
 * SCREEN of continent sections with checkboxes, which could pick several but had no search, sorted
 * countries by server count so the same country moved between visits, and named them with the raw
 * string VPN Gate publishes rather than the app's own localised name.
 *
 * One page, built on the shape [com.mlmvpn.scanner.ui.tunnel.RegionPickerScreen] already uses for
 * Psiphon and Tor:
 *
 *  - **Search**, matching the localised name, the English name and the code alike, because people
 *    type the code they saw in a config as often as the name they know.
 *  - **Starred and recent countries pinned above the list.** Anyone who cares about the country
 *    cycles between two or three, and scrolling past eighty to reach them every time is exactly
 *    how a country picker stops being used.
 *  - **Multi-select stays**, because the archive genuinely needs it -- picking a whole continent
 *    to test in one sweep is the one thing the old browser did better than the main list.
 *  - **The count is the detail line.** "۹ سرور" next to a country is the honest predictor of
 *    whether narrowing to it will leave anything to connect to.
 */
@Composable
fun VpnGateCountryScreen(
    /** Every server in the scope being filtered, so the counts describe what the list will show. */
    servers: List<VpnGateServer>,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val memory = remember { RegionMemory(context, "vpngate") }
    var favourites by remember { mutableStateOf(memory.favourites()) }
    // Read once and NOT updated as countries are picked. The region picker re-reads it because it
    // pops back on every selection; this page is multi-select and stays open, so a live recents
    // list would grow the pinned section and shift the rows out from under the user's finger
    // mid-selection. It is refreshed the next time the page is opened.
    val recents = remember { memory.recent() }
    var query by remember { mutableStateOf("") }

    val byCountry = remember(servers) { servers.groupBy { it.countryShort } }

    // Sorted by the name the user will actually read, not by the code and not by server count:
    // an alphabetical list of codes is not alphabetical in Persian, and a count order moves a
    // country every time the archive changes, so it is never where it was last time.
    val allCodes = remember(byCountry, locale) {
        byCountry.keys.sortedBy { com.mlmvpn.scanner.ui.tunnel.CountryLabel.name(it, locale) }
    }

    val pinned = remember(allCodes, favourites, recents) {
        (favourites.toList() + recents).distinct().filter { it in byCountry }
    }

    val matches = remember(allCodes, query, locale) {
        val q = query.trim()
        if (q.isBlank()) emptyList() else allCodes.filter {
            com.mlmvpn.scanner.ui.tunnel.CountryLabel.name(it, locale).contains(q, true) ||
                com.mlmvpn.scanner.ui.tunnel.CountryLabel.name(it, java.util.Locale.ENGLISH)
                    .contains(q, true) ||
                it.contains(q, true)
        }
    }

    val byContinent = remember(allCodes) {
        allCodes.groupBy { VpnGateGeo.continentOf(it) }.toSortedMap(compareBy { it.ordinal })
    }

    fun toggle(code: String) {
        onSelectedChange(if (code in selected) selected - code else selected + code)
        if (code !in selected) memory.remember(code)
    }

    fun toggleMany(codes: Collection<String>) {
        onSelectedChange(
            if (codes.all { it in selected }) selected - codes.toSet() else selected + codes
        )
    }

    IosScreen(
        title = S(R.string.country_2),
        onBack = onBack,
        backLabel = S(R.string.servers_7),
        scrollable = false,
        trailing = {
            if (selected.isNotEmpty()) {
                Text(
                    S(R.string.clear_3),
                    color = Ios.Label,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clip(ControlShape)
                        .clickable { onSelectedChange(emptySet()) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(ControlShape)
                .background(Color.White.copy(alpha = 0.10f))
                .heightIn(min = 36.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = Ios.SecondaryLabel,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(7.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Ios.Label, fontSize = 16.sp),
                cursorBrush = SolidColor(Ios.Blue),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(S(R.string.search_country_2), color = Ios.SecondaryLabel, fontSize = 16.sp)
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = S(R.string.clear_3),
                    tint = Ios.SecondaryLabel,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(BadgeShape)
                        .clickable { query = "" }
                        .padding(5.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 4.dp,
                bottom = LocalSystemBottomPadding.current + 32.dp,
            ),
        ) {
            if (query.isNotBlank()) {
                if (matches.isEmpty()) {
                    item {
                        GatewayEmptyState(
                            title = S(R.string.no_country_found),
                            body = S(R.string.no_country_matched_query, query),
                        )
                    }
                } else {
                    item { SettingsSectionHeader(S(R.string.search_results)) }
                    item {
                        SettingsGroup {
                            matches.forEachIndexed { index, code ->
                                if (index > 0) Separator()
                                CountryCheckRow(
                                    code = code,
                                    count = byCountry[code]?.size ?: 0,
                                    checked = code in selected,
                                    starred = code in favourites,
                                    onStar = { favourites = memory.toggleFavourite(code) },
                                    onClick = { toggle(code) },
                                )
                            }
                        }
                    }
                }
                return@LazyColumn
            }

            item {
                SettingsGroup {
                    CountryRow(
                        title = S(R.string.all_countries_2),
                        value = faCount(servers.size) + S(R.string.servers_8),
                        leading = { SettingsGlyph(Icons.Default.Public, Ios.Gray) },
                        checked = selected.isEmpty(),
                        onClick = { onSelectedChange(emptySet()) },
                    )
                }
                SettingsFooter(
                    S(R.string.with_no_filter_every_server_in_this) +
                        S(R.string.at_once_to_test_a_whole_continent)
                )
            }

            if (pinned.isNotEmpty()) {
                item { SettingsSectionHeader(S(R.string.frequently_used)) }
                item {
                    SettingsGroup {
                        pinned.forEachIndexed { index, code ->
                            if (index > 0) Separator()
                            CountryCheckRow(
                                code = code,
                                count = byCountry[code]?.size ?: 0,
                                checked = code in selected,
                                starred = code in favourites,
                                onStar = { favourites = memory.toggleFavourite(code) },
                                onClick = { toggle(code) },
                            )
                        }
                    }
                }
            }

            byContinent.forEach { (continent, codes) ->
                item(key = "h_${continent.name}") {
                    SettingsSectionHeader(continent.emoji + "  " + continent.label)
                }
                item(key = "g_${continent.name}") {
                    SettingsGroup {
                        // The continent's own switch, at the head of the countries it covers
                        // rather than as a text button floating at the end of a heading.
                        CountryRow(
                            title = if (codes.all { it in selected }) S(R.string.deselect_this_continent)
                                    else S(R.string.select_all_of_this_continent),
                            subtitle = faCount(codes.size) + S(R.string.countries_3),
                            checked = codes.all { it in selected },
                            onClick = { toggleMany(codes) },
                        )
                        codes.forEach { code ->
                            Separator()
                            CountryCheckRow(
                                code = code,
                                count = byCountry[code]?.size ?: 0,
                                checked = code in selected,
                                starred = code in favourites,
                                onStar = { favourites = memory.toggleFavourite(code) },
                                onClick = { toggle(code) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A country, with its flag, its localised name, its server count and a star. */
@Composable
private fun CountryCheckRow(
    code: String,
    count: Int,
    checked: Boolean,
    starred: Boolean,
    onStar: () -> Unit,
    onClick: () -> Unit,
) {
    CountryRow(
        title = com.mlmvpn.scanner.ui.tunnel.CountryLabel.localized(code),
        value = faCount(count) + S(R.string.servers_8),
        leading = { Text(getNodeFlagEmoji(code), fontSize = 20.sp) },
        checked = checked,
        onClick = onClick,
        star = starred to onStar,
    )
}

/**
 * A row that is either picked or not.
 *
 * The archive browser marked a chosen country with a tinted tile and a 1dp accent ring; here it is
 * the same check the rest of the app uses, so "chosen" looks the same on this screen as on every
 * other one.
 */
@Composable
private fun CountryRow(
    title: String,
    checked: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    star: Pair<Boolean, () -> Unit>? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Ios.Label,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Ios.SecondaryLabel, fontSize = 12.sp)
            }
        }
        if (value != null) {
            Text(value, color = Ios.SecondaryLabel, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
        }
        if (star != null) {
            Icon(
                if (star.first) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = null,
                tint = if (star.first) Ios.Yellow else Ios.SecondaryLabel.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(30.dp)
                    .clip(BadgeShape)
                    .clickable(onClick = star.second)
                    .padding(5.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            imageVector = if (checked) Icons.Default.CheckCircle
                          else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (checked) Ios.Blue else Ios.SecondaryLabel.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp),
        )
    }
}
