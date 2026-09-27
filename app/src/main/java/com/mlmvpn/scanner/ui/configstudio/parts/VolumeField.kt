package com.mlmvpn.scanner.ui.configstudio.parts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.SettingsGroup

/**
 * A volume, typed in megabytes or gigabytes.
 *
 * The fields used to take gigabytes only, so a 10 MB trial had to be typed as `0.01` -- and on a
 * Persian keyboard, whose digits and decimal mark `toDoubleOrNull()` does not read, not at all.
 */
enum class VolumeUnit(val bytes: Long, val label: String) {
    MB(1_048_576L, "MB"),
    GB(1_073_741_824L, "GB"),
}

/**
 * The typed text in bytes, or null for empty / zero / unreadable (which every caller means as
 * "unlimited"). Persian and Arabic-Indic digits and decimal marks are read as their Latin forms.
 */
fun parseVolumeBytes(text: String, unit: VolumeUnit): Long? {
    val latin = buildString {
        for (c in text.trim()) append(
            when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                '٫', '/', ',', '،' -> '.'
                else -> c
            }
        )
    }
    val n = latin.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: return null
    return (n * unit.bytes).toLong()
}

/**
 * A stored volume as field text plus the unit it reads best in: whole gigabytes as GB, anything
 * under a gigabyte or not a whole number of them as MB -- so a 10 MB plan reopens as «10 MB»
 * rather than «0.01 GB». Latin digits, because the text is parsed back on save.
 */
fun volumeToField(bytes: Long?): Pair<String, VolumeUnit> {
    if (bytes == null || bytes <= 0) return "" to VolumeUnit.GB
    val unit = if (bytes >= VolumeUnit.GB.bytes && bytes % VolumeUnit.GB.bytes == 0L) VolumeUnit.GB else VolumeUnit.MB
    val v = bytes.toDouble() / unit.bytes
    val text = if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    return text to unit
}

/** [WizardField]'s look, with the unit chosen beside the number. */
@Composable
fun VolumeField(
    value: String,
    onValueChange: (String) -> Unit,
    unit: VolumeUnit,
    onUnitChange: (VolumeUnit) -> Unit,
    placeholder: String,
) {
    SettingsGroup {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = TextStyle(color = Ios.Label, fontSize = 17.sp),
                    cursorBrush = SolidColor(Ios.Blue),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty()) {
                                Text(placeholder, color = Ios.SecondaryLabel.copy(alpha = 0.6f), fontSize = 17.sp)
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                for (u in VolumeUnit.entries) {
                    val sel = u == unit
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .background(if (sel) Ios.Blue else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { onUnitChange(u) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            u.label,
                            color = if (sel) Color.White else Ios.SecondaryLabel,
                            fontSize = 14.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
