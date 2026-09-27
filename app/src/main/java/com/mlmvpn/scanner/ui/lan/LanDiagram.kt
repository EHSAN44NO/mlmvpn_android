package com.mlmvpn.scanner.ui.lan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.lan.EngineShare
import com.mlmvpn.scanner.lan.LanStatus
import com.mlmvpn.scanner.ui.settings.Ios

// =================================================================================================
// The four hops, and which of them is currently working.
//
// A shared tunnel is a chain -- other device, this phone, the tunnel, the internet -- and it can
// break at any link, with each break presenting to the user in exactly the same way: a laptop that
// will not load a page. The text on the status card names the first broken link, but naming it and
// SHOWING it are different things, and a picture answers "where is it stuck" in one glance in a way
// four sentences cannot.
//
// Deliberately not a progress bar. The links are not stages that complete in order and stay done:
// the tunnel can drop while a client is still attached, so the third box can go grey while the
// first is green. A row of independently lit boxes says that; a bar filling left to right does not.
// =================================================================================================

@Composable
fun LanDiagram(status: LanStatus, modifier: Modifier = Modifier) {
    // Each hop is asked its own question rather than inheriting from the one before, so the
    // drawing can show a genuinely mixed state instead of a tidy fiction.
    val clientOk = status.clients.any { it.connections > 0 }
    val phoneOk = status.lanEnabled && status.address != null
    val tunnelOk = status.vpnUp &&
        status.engine != EngineShare.NEEDS_PROXY_MODE &&
        status.engine != EngineShare.IMPOSSIBLE
    val exitOk = tunnelOk && phoneOk

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Hop(R.string.lan_hop_device, Icons.Default.Laptop, clientOk, Modifier.weight(1f))
        Link(clientOk && phoneOk)
        Hop(R.string.lan_hop_phone, Icons.Default.Smartphone, phoneOk, Modifier.weight(1f))
        Link(phoneOk && tunnelOk)
        Hop(R.string.lan_hop_tunnel, Icons.Default.VpnLock, tunnelOk, Modifier.weight(1f))
        Link(exitOk)
        Hop(R.string.lan_hop_internet, Icons.Default.Public, exitOk, Modifier.weight(1f))
    }
}

@Composable
private fun Hop(labelRes: Int, icon: ImageVector, live: Boolean, modifier: Modifier = Modifier) {
    val tint = if (live) Ios.Green else Ios.Gray
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(tint.copy(alpha = if (live) 0.20f else 0.10f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (live) tint else Ios.Chevron,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            stringResource(labelRes),
            color = if (live) Ios.Label else Ios.SecondaryLabel,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** The bar between two hops. Lit only when BOTH ends are, since a link is what joins them. */
@Composable
private fun Link(live: Boolean) {
    Box(
        modifier = Modifier
            .padding(top = 21.dp)
            .width(18.dp)
            .height(2.dp)
            .background(
                if (live) Ios.Green else Color.White.copy(alpha = 0.14f),
                RoundedCornerShape(1.dp),
            ),
    )
}
