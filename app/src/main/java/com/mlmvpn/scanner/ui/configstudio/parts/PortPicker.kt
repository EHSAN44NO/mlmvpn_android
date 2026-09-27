package com.mlmvpn.scanner.ui.configstudio.parts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.theme.ControlShape

/**
 * Which ports a multi-location subscription is served on.
 *
 * Only Cloudflare's TLS ports: every Config Studio config is TLS on the worker's own hostname, and a
 * config on a plain-HTTP port would import cleanly and never connect. Each chosen port multiplies the
 * entries in the person's app — three countries on two ports is six servers to choose from.
 */
object StudioPorts {
    val TLS = listOf(443, 8443, 2053, 2083, 2087, 2096)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortPicker(picked: Set<Int>, onToggle: (Int) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (port in StudioPorts.TLS) {
            val on = port in picked
            Box(
                modifier = Modifier
                    .background(if (on) Ios.Blue else Color.Transparent, ControlShape)
                    .border(1.dp, if (on) Ios.Blue else Ios.SecondaryLabel.copy(alpha = 0.35f), ControlShape)
                    .clickable { onToggle(port) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    port.toString(),
                    color = if (on) Color.White else Ios.Label,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
