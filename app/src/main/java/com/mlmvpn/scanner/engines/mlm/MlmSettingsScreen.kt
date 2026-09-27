package com.mlmvpn.scanner.engines.mlm

import androidx.compose.ui.graphics.Color
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MlmSettingsScreen(
    account: CloudAccount,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiManager = remember { MlmApiManager(context) }
    
    var proxyIp by remember { mutableStateOf("") }
    var iata by remember { mutableStateOf("") }
    var fragLen by remember { mutableStateOf("") }
    var fragInt by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val settings = apiManager.getProxySettings(account)
        if (settings != null) {
            proxyIp = settings.proxyIp
            iata = settings.iata
            fragLen = settings.fragLen
            fragInt = settings.fragInt
        } else {
            Toast.makeText(context, S(R.string.could_not_fetch_the_settings), Toast.LENGTH_SHORT).show()
            onDismiss()
        }
        isLoading = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        // Transparent over the wallpaper; the Cloud tab that hosts this no longer pads it.
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = com.mlmvpn.scanner.ui.LocalContentTopInset.current + 24.dp,
                    bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current + 24.dp,
                )
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(S(R.string.global_worker_settings), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }
        } else {
            OutlinedTextField(
                value = proxyIp,
                onValueChange = { proxyIp = it },
                label = { Text(S(R.string.proxy_ip_label), color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = iosFieldColors()
            ,
        shape = ControlShape,)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = iata,
                onValueChange = { iata = it },
                label = { Text(S(R.string.location_iata), color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = iosFieldColors()
            ,
        shape = ControlShape,)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = fragLen,
                onValueChange = { fragLen = it },
                label = { Text(S(R.string.fragment_length_e_g_20_30), color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = iosFieldColors()
            ,
        shape = ControlShape,)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = fragInt,
                onValueChange = { fragInt = it },
                label = { Text(S(R.string.fragment_interval_e_g_1_2), color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = iosFieldColors()
            ,
        shape = ControlShape,)
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !isSaving) {
                    Text(S(R.string.cancel_3), color = TextMuted)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        isSaving = true
                        scope.launch {
                            val req = MlmProxySettingsRequest(
                                proxyIp = proxyIp.trim(),
                                iata = iata.trim(),
                                fragLen = fragLen.trim(),
                                fragInt = fragInt.trim()
                            )
                            val success = apiManager.updateProxySettings(account, req)
                            isSaving = false
                            if (success) {
                                Toast.makeText(context, S(R.string.settings_saved_2), Toast.LENGTH_SHORT).show()
                                onDismiss()
                            } else {
                                Toast.makeText(context, S(R.string.could_not_save_the_settings), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isSaving
                ) {
                    if (isSaving) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgDark)
                    else Text(S(R.string.save_3), color = BgDark)
                }
            }
        }
    }
}
}
