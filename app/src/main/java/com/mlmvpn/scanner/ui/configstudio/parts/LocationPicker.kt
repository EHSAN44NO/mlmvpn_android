package com.mlmvpn.scanner.ui.configstudio.parts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.domain.ExitCountries
import com.mlmvpn.scanner.ui.configstudio.design.CountryBadge
import com.mlmvpn.scanner.ui.configstudio.design.StudioType
import com.mlmvpn.scanner.ui.configstudio.faNum
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.ui.theme.ControlShape
import com.mlmvpn.scanner.utils.S

/** What is known about a country's server right now, shown on its chip -- as words, not symbols. */
sealed interface CountryStatus {
    /** One of the operator's own exits. */
    object Own : CountryStatus
    /** Being tested on the engine now. */
    object Checking : CountryStatus
    /** This many public servers were tested and really leave from there. */
    data class Verified(val count: Int) : CountryStatus
    /** Tested, and nothing working was found. */
    object None : CountryStatus
}

/**
 * «کشورها»: which countries a person gets configs for, one tap per country (build 18 redesign).
 *
 * Each chip carries the country's code badge, its name and what is known about its server, in words
 * and colour: «سرور خودتان», «در حال بررسی», «۳ سرور سالم», «سرور سالم نیست». It used to carry a flag
 * emoji and «✓ ۱۲۰ms», «✗» or «…».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationPicker(
    countries: List<String>,
    picked: Set<String>,
    onToggle: (String) -> Unit,
    includeDirect: Boolean,
    onIncludeDirect: (Boolean) -> Unit,
    status: Map<String, CountryStatus> = emptyMap(),
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (cc in countries) {
            val on = cc in picked
            val st = status[cc]
            Row(
                modifier = Modifier
                    .background(if (on) Ios.Blue.copy(alpha = 0.22f) else Color.Transparent, ControlShape)
                    .border(1.dp, if (on) Ios.Blue else Ios.SecondaryLabel.copy(alpha = 0.35f), ControlShape)
                    .clickable { onToggle(cc) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CountryBadge(cc)
                Spacer(Modifier.width(7.dp))
                Column {
                    Text(
                        ExitCountries.name(cc),
                        color = Ios.Label,
                        fontSize = StudioType.Subhead,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (st != null) {
                        Text(
                            statusText(st),
                            color = statusColor(st),
                            fontSize = StudioType.Tiny,
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    SettingsGroup {
        SettingsToggle(
            title = S(R.string.studio_location_direct),
            checked = includeDirect || picked.isEmpty(),
            subtitle = S(R.string.studio_location_direct_sub),
            onCheckedChange = onIncludeDirect,
        )
    }
}

@Composable
private fun statusText(st: CountryStatus): String = when (st) {
    CountryStatus.Own -> S(R.string.studio_country_own)
    CountryStatus.Checking -> S(R.string.studio_country_checking)
    is CountryStatus.Verified -> S(R.string.studio_country_verified).replace("%1\$s", faNum(st.count))
    CountryStatus.None -> S(R.string.studio_country_none)
}

private fun statusColor(st: CountryStatus): Color = when (st) {
    CountryStatus.Own -> Color(0xFF30D158)
    CountryStatus.Checking -> Color(0xFF98989F)
    is CountryStatus.Verified -> Color(0xFF30D158)
    CountryStatus.None -> Color(0xFFFF9F0A)
}
