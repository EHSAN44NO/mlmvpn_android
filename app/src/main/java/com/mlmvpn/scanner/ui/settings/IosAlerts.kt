package com.mlmvpn.scanner.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Text
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// Alerts.
//
// Not every question deserves a page. "What should this folder be called?" is one field and two
// buttons, and pushing a whole screen for it is as wrong as the reverse -- which is why the app's
// long-form content moved OFF dialogs and its one-line questions stayed on them.
//
// What these replace is stock `androidx.compose.material3.AlertDialog` with no colours passed at
// all: a Material 3 surface in the Material default palette, with Material title typography and
// Material text buttons, in the middle of an app that has none of those. The three folder dialogs
// on the connection screen were the last of them.
//
// The shape below is iOS's own: a narrow card, centred title and message, and actions separated by
// hairlines rather than floated to one corner -- stacked when there are more than two, side by side
// when there are exactly two, which is the rule iOS uses and the reason a two-button alert reads as
// a choice and a three-button one reads as a menu.
// =================================================================================================

/** One button in an alert. [destructive] paints it red; [preferred] gives it the heavier weight. */
data class IosAlertAction(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val preferred: Boolean = false,
)

@Composable
fun IosAlert(
    title: String,
    message: String? = null,
    actions: List<IosAlertAction>,
    onDismiss: () -> Unit,
    content: @Composable (() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(290.dp)
                .clip(CardShape)
                .background(Ios.DialogSurface),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    color = Ios.Label,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp,
                )
                if (message != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        message,
                        color = Ios.SecondaryLabel,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                if (content != null) {
                    Spacer(Modifier.height(14.dp))
                    content()
                }
            }

            AlertSeparator()

            // Two actions sit side by side; three or more stack. Side-by-side with four buttons is
            // how a folder menu ended up as four eight-character words on one line.
            if (actions.size == 2) {
                Row(modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    AlertButton(actions[0], Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .width(0.5.dp)
                            .height(44.dp)
                            .background(Ios.Separator)
                    )
                    AlertButton(actions[1], Modifier.weight(1f))
                }
            } else {
                actions.forEachIndexed { index, action ->
                    if (index > 0) AlertSeparator()
                    AlertButton(action, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun AlertSeparator() {
    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Ios.Separator))
}

@Composable
private fun AlertButton(action: IosAlertAction, modifier: Modifier) {
    Box(
        modifier = modifier
            .clickable(onClick = action.onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            action.label,
            // White, not the accent -- the same rule the rest of the app follows. Red still means
            // destructive, because that is state, not decoration.
            color = if (action.destructive) Ios.Destructive else Ios.Label,
            fontSize = 16.sp,
            fontWeight = if (action.preferred) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * An alert with one field in it: iOS's "New Folder" shape.
 *
 * The confirm action is disabled while the field is blank rather than accepting the tap and doing
 * nothing, which is what the Material version did -- there was no feedback at all for creating a
 * folder with no name.
 */
@Composable
fun IosPrompt(
    title: String,
    message: String? = null,
    initial: String = "",
    placeholder: String = "",
    confirmLabel: String,
    cancelLabel: String = S(R.string.cancel_3),
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    IosAlert(
        title = title,
        message = message,
        onDismiss = onDismiss,
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(com.mlmvpn.scanner.ui.theme.ControlShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Ios.Label, fontSize = 16.sp),
                    cursorBrush = SolidColor(Ios.Blue),
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty() && placeholder.isNotEmpty()) {
                                Text(
                                    placeholder,
                                    color = Ios.SecondaryLabel.copy(alpha = 0.6f),
                                    fontSize = 16.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        },
        actions = listOf(
            IosAlertAction(cancelLabel, onDismiss),
            IosAlertAction(
                confirmLabel,
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                preferred = true,
            ),
        ),
    )
}
