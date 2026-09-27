package com.mlmvpn.scanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.engines.freeconfig.FreeConfigEngine

/**
 * «کانفیگ رایگان» as a destination of its own.
 *
 * It used to be the first row of V2Ray's Add-Node page, which made it look like one of the ways to
 * type in a config you already had -- next to "scan a QR", "paste from clipboard", "from a file" --
 * when it is nothing of the sort. It is the only entry in the app that goes and FINDS working
 * servers for a user who has none, which is the thing a first-time user most needs and the last
 * place they would look for it: three levels down, inside the feature they cannot use yet because
 * they have no configs.
 *
 * Splitting it out costs one thing, and this file exists to pay for it. Inside Add-Node the import
 * was the end of the flow, because the user was already standing in V2Ray. From the home screen
 * they are not, so the import hands over explicitly -- see the wizard's DONE step and [NodesFocus].
 */
@Composable
fun FreeConfigTab(onBack: () -> Unit, onOpenNodes: () -> Unit) {
    val context = LocalContext.current
    // The singleton, so an import here is the same list V2Ray is showing. NodesTab keys its own
    // node state on `nodesFlow`, so it picks the new configs up without being told.
    val nodeManager = remember { NodeManager(context) }

    FreeConfigWizard(
        onImport = { nodes ->
            nodeManager.nodes.addAll(0, nodes)
            nodeManager.saveNodes()
        },
        onClose = onBack,
        onGoToNodes = { node ->
            // Post the request BEFORE switching, so the tab applies it on the composition the
            // switch triggers rather than a frame later.
            NodesFocus.focus(FreeConfigEngine.GROUP_NAME, node?.id)
            onOpenNodes()
        },
    )
}
