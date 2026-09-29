package com.mlmvpn.scanner.ui.arena

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A team's short mark. */
fun teamMark(id: String): String = when (id) {
    "BPB" -> "B"
    "EDG" -> "E"
    "NHN" -> "N"
    "MLM" -> "M"
    "SPD" -> "S"
    "NTR" -> "Nt"
    "GZG" -> "G"
    "NVA" -> "Nv"
    else -> id.take(1)
}

/**
 * A team's tile, in the system's own restraint: one neutral squircle for every team, like an
 * app icon placeholder, so the teams read as equals and colour is left for what it means on iOS --
 * state. Only [dim] (out of the race) changes it.
 */
@Composable
fun TeamTile(id: String, size: Dp, modifier: Modifier = Modifier, dim: Boolean = false) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.27f))
            .background(Brush.verticalGradient(listOf(Color(0xFF636366), Color(0xFF48484A))))
            .alpha(if (dim) 0.45f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Text(teamMark(id), color = Color.White, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold)
    }
}
