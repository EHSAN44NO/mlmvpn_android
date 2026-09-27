package com.mlmvpn.scanner.ui.sublink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mlmvpn.scanner.data.CloudGroup
import com.mlmvpn.scanner.data.ScannerGroup
import com.mlmvpn.scanner.data.VpnSubscription
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * One group of configs a sub link can be pointed at.
 *
 * [id] is a composite string -- `manual:<engine>:<groupTitle>`, `cloud:<groupId>:<engine>`,
 * `scanner:<groupId>:<engine>` -- and it is PERSISTED as a link's `mappedGroupName` and parsed
 * back by [com.mlmvpn.scanner.engines.subgenerator.SubGenManager.resolveGroupNodes]. Its format
 * is therefore a wire format, not a display detail: changing how it is built here silently
 * detaches every link the user has already made.
 *
 * [source] and [subtitle] are new. The old picker flattened all three origins into one
 * horizontally-scrolling row of chips whose labels each carried their own prefix -- "دستی: 📁 …",
 * "ابری: …", "اسکنر: …" -- so the category was repeated on every chip and sortable by none of
 * them. Split out, the category becomes the section heading it always was.
 */
data class SubGenGroup(
    val id: String,
    val displayName: String,
    val nodes: List<VpnNode>,
    val source: Source,
    val subtitle: String = "",
) {
    enum class Source(val label: String) {
        MANUAL(S(R.string.connection_section)),
        CLOUD(S(R.string.cloud_2)),
        SCANNER(S(R.string.scanner_2)),
    }
}

/**
 * The link being created or edited.
 *
 * Held by the screen rather than by the form, so pushing the group picker and coming back does
 * not lose what has already been typed -- the old dialog could not do this at all, which is part
 * of why the group picker had to be crammed inside it.
 */
class SubLinkDraft {
    var name by mutableStateOf("")
    var slug by mutableStateOf("")
    var group by mutableStateOf<SubGenGroup?>(null)
    var expiryDays by mutableStateOf("")

    /** Null while creating; the slug being edited otherwise. */
    var editing by mutableStateOf<String?>(null)

    fun reset(freshSlug: String) {
        name = ""
        slug = freshSlug
        group = null
        expiryDays = ""
        editing = null
    }
}

/**
 * Every group the app can offer, in one list.
 *
 * Lifted out of the create dialog unchanged in what it produces -- the ids are byte-for-byte the
 * ones the old builder made -- but the labels are split into a name and a subtitle so a row can
 * show the count in the grey trailing slot instead of appending "(۱۲ کانفیگ)" to the name.
 */
fun buildSubGenGroups(
    nodes: List<VpnNode>,
    cloudGroups: List<CloudGroup>,
    scannerGroups: List<ScannerGroup>,
    subscriptions: List<VpnSubscription>,
): List<SubGenGroup> {
    val list = mutableListOf<SubGenGroup>()

    nodes.groupBy { it.engineType }.forEach { (engine, engineNodes) ->
        if (engine.equals("Manual", ignoreCase = true)) {
            engineNodes.groupBy { it.groupTitle }.forEach { (groupTitle, groupNodes) ->
                val sub = subscriptions.firstOrNull { it.id == groupTitle || it.name == groupTitle }
                val label = when {
                    groupTitle == null -> S(R.string.default_str_2)
                    sub != null -> "🔗 " + sub.name
                    else -> "📁 " + groupTitle
                }
                list.add(
                    SubGenGroup(
                        id = "manual:$engine:${groupTitle ?: "null"}",
                        displayName = label,
                        nodes = groupNodes,
                        source = SubGenGroup.Source.MANUAL,
                        subtitle = if (sub != null) S(R.string.subscription) else S(R.string.folder_2),
                    )
                )
            }
        } else {
            list.add(
                SubGenGroup(
                    id = "manual:$engine:null",
                    displayName = engine,
                    nodes = engineNodes,
                    source = SubGenGroup.Source.MANUAL,
                    subtitle = S(R.string.engine),
                )
            )
        }
    }

    cloudGroups.forEach { group ->
        group.nodes.groupBy { it.engineType }.forEach { (engine, groupNodes) ->
            list.add(
                SubGenGroup(
                    id = "cloud:${group.id}:$engine",
                    displayName = group.title,
                    nodes = groupNodes,
                    source = SubGenGroup.Source.CLOUD,
                    subtitle = engine,
                )
            )
        }
    }

    scannerGroups.forEach { group ->
        group.nodes.groupBy { it.engineType }.forEach { (engine, groupNodes) ->
            list.add(
                SubGenGroup(
                    id = "scanner:${group.id}:$engine",
                    displayName = group.title,
                    nodes = groupNodes,
                    source = SubGenGroup.Source.SCANNER,
                    subtitle = engine,
                )
            )
        }
    }

    return list
}
