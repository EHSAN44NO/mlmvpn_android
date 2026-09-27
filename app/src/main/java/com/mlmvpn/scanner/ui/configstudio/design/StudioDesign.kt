package com.mlmvpn.scanner.ui.configstudio.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.config.ConfigShape
import com.mlmvpn.scanner.data.studio.config.LocationConfigs
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.ui.theme.ControlShape
import com.mlmvpn.scanner.ui.theme.PanelShape
import com.mlmvpn.scanner.utils.S

/**
 * Config Studio's design kit (build 18 redesign).
 *
 * Built on the app's own iOS layer (`ui/settings/IosSettings.kt`: [Ios] colours, `SettingsGroup`,
 * `SettingsRow`, `IosScreen`) and its glass cards, so Config Studio still looks like the rest of the
 * app -- what changes is that every screen now takes its pieces from one place:
 *
 *  * one type scale ([StudioType]) instead of sizes typed per component;
 *  * one icon family ([StudioIcons]), one meaning per glyph;
 *  * no emoji or check-mark characters anywhere: state is a [StudioBadge] or a [StatusDot]-led line,
 *    and a country is a [CountryBadge] carrying its two-letter code.
 */
object StudioType {
    val LargeTitle = 28.sp
    val Title = 20.sp
    val Headline = 17.sp
    val Body = 16.sp
    val Callout = 15.sp
    val Subhead = 14.sp
    val Footnote = 13.sp
    val Caption = 12.sp
    val Tiny = 11.sp
}

// ---------------------------------------------------------------------------- the tab bar

enum class StudioTab { HOME, USERS, STATS, MORE }

/** How tall the tab bar is above the system navigation area, for pages that scroll under it. */
val StudioTabBarHeight: Dp = 62.dp

/**
 * The four tabs, iOS style: outline glyphs, the selected one filled and in the accent, labels under
 * them. Glass, pinned to the bottom, over the page -- the page scrolls under it.
 */
@Composable
fun StudioTabBar(
    selected: StudioTab,
    onSelect: (StudioTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .frostedGlass(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .navigationBarsPadding()
            .height(StudioTabBarHeight)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in StudioTab.entries) {
            val isSel = tab == selected
            val (icon, label) = when (tab) {
                StudioTab.HOME -> (if (isSel) StudioIcons.HomeSelected else StudioIcons.Home) to S(R.string.studio_tab_home)
                StudioTab.USERS -> (if (isSel) StudioIcons.UsersSelected else StudioIcons.Users) to S(R.string.studio_tab_users)
                StudioTab.STATS -> (if (isSel) StudioIcons.StatsSelected else StudioIcons.Stats) to S(R.string.studio_tab_stats)
                StudioTab.MORE -> (if (isSel) StudioIcons.MoreSelected else StudioIcons.More) to S(R.string.studio_tab_more)
            }
            val tint = if (isSel) Ios.Blue else Ios.SecondaryLabel
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(ControlShape)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(3.dp))
                Text(
                    label,
                    color = tint,
                    fontSize = StudioType.Tiny,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- badges

/** A small rounded label that states something: «فعال», «XHTTP», «۳ دستگاه». */
@Composable
fun StudioBadge(
    text: String,
    tint: Color = Ios.SecondaryLabel,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(tint.copy(alpha = 0.16f), RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = tint, fontSize = StudioType.Tiny, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * A country, as its two-letter code in a quiet box -- in place of the flag emoji the feature drew
 * before. Always left-to-right, and monospace so a column of them lines up.
 */
@Composable
fun CountryBadge(cc: String, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            cc.uppercase(),
            color = Ios.Label,
            fontSize = StudioType.Tiny,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = modifier
                .border(1.dp, Ios.SecondaryLabel.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** A coloured lamp and a short state line: the one way a row says how something is. */
@Composable
fun StatusLine(text: String, tint: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(tint, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Ios.SecondaryLabel, fontSize = StudioType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------- tiles and cards

/**
 * One figure on the home screen. [onClick] makes it a way in to the list behind the number.
 */
@Composable
fun StudioStatTile(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "statPress")
    Column(
        modifier = modifier
            .scale(scale)
            .clip(PanelShape)
            .frostedGlass(PanelShape)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(label, color = Ios.SecondaryLabel, fontSize = StudioType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Text(value, color = Ios.Label, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (detail != null) {
            Spacer(Modifier.height(2.dp))
            Text(detail, color = Ios.SecondaryLabel, fontSize = StudioType.Tiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Two tiles side by side, spaced like every other card. */
@Composable
fun StudioTilePair(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * A notice that matters: an icon in its colour, a title, one or two sentences, and at most one
 * thing to do about it. What the old screens said in grey paragraphs they now say here, so an
 * operator can tell "this is information" from "this needs you".
 */
@Composable
fun NoticeCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .frostedGlass(CardShape)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Ios.Label, fontSize = StudioType.Callout, fontWeight = FontWeight.SemiBold)
            if (body != null) {
                Spacer(Modifier.height(4.dp))
                Text(body, color = Ios.SecondaryLabel, fontSize = StudioType.Footnote, lineHeight = 21.sp)
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    actionLabel,
                    color = Ios.Blue,
                    fontSize = StudioType.Subhead,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(ControlShape).clickable(onClick = onAction).padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** A page or section with nothing in it yet: what it is for, and the one way to start. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape).background(Ios.SecondaryLabel.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Ios.SecondaryLabel, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = Ios.Label, fontSize = StudioType.Headline, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, color = Ios.SecondaryLabel, fontSize = StudioType.Footnote, lineHeight = 21.sp, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                actionLabel,
                color = Color.White,
                fontSize = StudioType.Callout,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(Ios.Blue, ControlShape)
                    .clip(ControlShape)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------- choosing

/**
 * One option in a list of choices, with the iOS check on the trailing edge when it is chosen -- in
 * place of the «✓» character the old rows printed as their value.
 */
@Composable
fun ChoiceRow(
    title: String,
    selected: Boolean,
    subtitle: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) Ios.Label else Ios.SecondaryLabel,
                fontSize = StudioType.Body,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Ios.SecondaryLabel, fontSize = StudioType.Caption, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(Ios.Blue)
                    else Modifier.border(1.5.dp, Ios.SecondaryLabel.copy(alpha = 0.5f), CircleShape)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/**
 * The protocols a person is given (build 18): VLESS and Trojan over WebSocket -- whatever
 * [LocationConfigs.OFFERED] holds. Several can be chosen; at least one always is.
 */
@Composable
fun ProtocolPicker(
    selected: Set<ConfigShape>,
    onChange: (Set<ConfigShape>) -> Unit,
) {
    SettingsGroup {
        LocationConfigs.OFFERED.forEachIndexed { index, shape ->
            if (index > 0) StudioSeparator()
            val on = shape in selected
            ChoiceRow(
                title = LocationConfigs.shortName(shape) + when (shape) {
                    ConfigShape.VLESS_XHTTP -> ""
                    else -> " · WebSocket"
                },
                subtitle = when (shape) {
                    ConfigShape.VLESS_WS -> S(R.string.studio_proto_vless_sub)
                    ConfigShape.TROJAN_WS -> S(R.string.studio_proto_trojan_sub)
                    ConfigShape.VLESS_XHTTP -> S(R.string.studio_proto_xhttp_sub)
                },
                selected = on,
                onClick = {
                    val next = if (on) selected - shape else selected + shape
                    if (next.isNotEmpty()) onChange(next)
                },
            )
        }
    }
}

/**
 * The hairline between rows that have no icon tile: inset 16dp, where the group's own `Separator()`
 * starts past a 29dp glyph.
 */
@Composable
fun StudioSeparator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(0.5.dp)
            .background(Ios.Separator)
    )
}

/**
 * A segmented control: two to four mutually exclusive views of one page (for example «سرور خودم /
 * سرور عمومی»), glass track, the chosen segment filled.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .frostedGlass(ControlShape)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selectedIndex
            Text(
                label,
                color = if (sel) Color.White else Ios.Label,
                fontSize = StudioType.Subhead,
                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .then(if (sel) Modifier.background(Ios.Blue) else Modifier)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
            )
        }
    }
}
