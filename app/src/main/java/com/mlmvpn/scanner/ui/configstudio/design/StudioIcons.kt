package com.mlmvpn.scanner.ui.configstudio.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PublishedWithChanges
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Every icon Config Studio draws, by what it MEANS.
 *
 * One family -- Google's Material Icons in their outlined style, with the filled style for the
 * selected tab the way iOS pairs SF Symbols -- and one meaning per icon. Before this the feature drew about
 * forty filled Material glyphs chosen screen by screen, several of them meaning two things (the same
 * globe for "multi-location", "exits" and "edit locations"; the same arrows for "renew" and "edit"),
 * mixed with emoji and check-mark characters. A screen now names what it wants to say and gets the
 * one glyph that says it everywhere.
 *
 * Bundled with the app already (`material-icons-extended`), so there is nothing to download, and
 * release builds strip the ones that are not used.
 */
object StudioIcons {
    // The tab bar: outline normally, filled when selected.
    val Home: ImageVector get() = Icons.Outlined.Home
    val HomeSelected: ImageVector get() = Icons.Filled.Home
    val Users: ImageVector get() = Icons.Outlined.People
    val UsersSelected: ImageVector get() = Icons.Filled.People
    val Stats: ImageVector get() = Icons.Outlined.BarChart
    val StatsSelected: ImageVector get() = Icons.Filled.BarChart
    val More: ImageVector get() = Icons.Outlined.GridView
    val MoreSelected: ImageVector get() = Icons.Filled.GridView

    // People
    val NewUser: ImageVector get() = Icons.Outlined.PersonAdd
    val BulkUsers: ImageVector get() = Icons.Outlined.GroupAdd
    val Select: ImageVector get() = Icons.Outlined.Checklist
    val Devices: ImageVector get() = Icons.Outlined.Devices
    val Note: ImageVector get() = Icons.Outlined.StickyNote2
    val Tag: ImageVector get() = Icons.Outlined.Label
    val Renew: ImageVector get() = Icons.Outlined.EventRepeat
    val ResetUsage: ImageVector get() = Icons.Outlined.RestartAlt
    val Power: ImageVector get() = Icons.Outlined.PowerSettingsNew
    val Block: ImageVector get() = Icons.Outlined.Block

    // Links and configs
    val Config: ImageVector get() = Icons.Outlined.Link
    val Template: ImageVector get() = Icons.Outlined.Layers
    val Plan: ImageVector get() = Icons.Outlined.Inventory2
    val ApplyToUsers: ImageVector get() = Icons.Outlined.PublishedWithChanges
    val Archive: ImageVector get() = Icons.Outlined.Archive
    val Credential: ImageVector get() = Icons.Outlined.Key
    val Copy: ImageVector get() = Icons.Outlined.ContentCopy
    val Share: ImageVector get() = Icons.Outlined.Share
    val Export: ImageVector get() = Icons.Outlined.IosShare
    val Qr: ImageVector get() = Icons.Outlined.QrCode2
    val OpenPage: ImageVector get() = Icons.Outlined.OpenInBrowser
    val Combine: ImageVector get() = Icons.Outlined.CallMerge

    // The network
    val Locations: ImageVector get() = Icons.Outlined.Public
    val OwnServer: ImageVector get() = Icons.Outlined.Dns
    val PublicServer: ImageVector get() = Icons.Outlined.TravelExplore
    val Verified: ImageVector get() = Icons.Outlined.VerifiedUser
    val Endpoints: ImageVector get() = Icons.Outlined.Lan
    val EndpointGroups: ImageVector get() = Icons.Outlined.AccountTree
    val Test: ImageVector get() = Icons.Outlined.NetworkCheck
    val Latency: ImageVector get() = Icons.Outlined.Speed
    val ProbeHistory: ImageVector get() = Icons.Outlined.QueryStats

    // The fleet and the engine
    val Accounts: ImageVector get() = Icons.Outlined.Cloud
    val Settings: ImageVector get() = Icons.Outlined.Settings
    val EngineUpdate: ImageVector get() = Icons.Outlined.SystemUpdateAlt
    val ApiKey: ImageVector get() = Icons.Outlined.VpnKey
    val Repair: ImageVector get() = Icons.Outlined.Build
    val Reinstall: ImageVector get() = Icons.Outlined.Replay
    val Uninstall: ImageVector get() = Icons.Outlined.DeleteForever
    val Disconnect: ImageVector get() = Icons.Outlined.LinkOff
    val Domain: ImageVector get() = Icons.Outlined.Language
    val Activity: ImageVector get() = Icons.Outlined.History
    val Audit: ImageVector get() = Icons.Outlined.FactCheck
    val Contact: ImageVector get() = Icons.Outlined.SupportAgent
    val Options: ImageVector get() = Icons.Outlined.Tune

    // Figures
    val Volume: ImageVector get() = Icons.Outlined.DataUsage
    val Time: ImageVector get() = Icons.Outlined.Schedule
    val Calendar: ImageVector get() = Icons.Outlined.CalendarMonth
    val Traffic: ImageVector get() = Icons.Outlined.SwapVert

    // States and actions
    val Ok: ImageVector get() = Icons.Outlined.CheckCircle
    val Warning: ImageVector get() = Icons.Outlined.WarningAmber
    val Error: ImageVector get() = Icons.Outlined.ErrorOutline
    val Info: ImageVector get() = Icons.Outlined.Info
    val Refresh: ImageVector get() = Icons.Outlined.Refresh
    val Search: ImageVector get() = Icons.Outlined.Search
    val Add: ImageVector get() = Icons.Outlined.Add
    val BulkAdd: ImageVector get() = Icons.Outlined.PlaylistAdd
    val Edit: ImageVector get() = Icons.Outlined.Edit
    val Save: ImageVector get() = Icons.Outlined.Save
    val Delete: ImageVector get() = Icons.Outlined.Delete
}
