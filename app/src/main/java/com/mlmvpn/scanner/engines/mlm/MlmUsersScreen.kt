package com.mlmvpn.scanner.engines.mlm

import com.mlmvpn.scanner.ui.home.frostedGlass
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mlmvpn.scanner.utils.S

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MlmUsersScreen(
    account: CloudAccount,
    onDismiss: () -> Unit,
    onScanUser: (String) -> Unit,
    onGroupsUpdated: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiManager = remember { MlmApiManager(context) }
    
    var users by remember { mutableStateOf<List<MlmUser>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAddUserDialog by remember { mutableStateOf(false) }
    var editingUser by remember { mutableStateOf<MlmUser?>(null) }

    fun loadUsers() {
        isLoading = true
        scope.launch {
            var fetchedUsers = apiManager.getUsers(account)
            // A brand-new workers.dev route can 404 with Cloudflare's own edge error 1042
            // ("worker not found yet") for a few seconds right after it's first enabled --
            // this is not our worker code running, it's Cloudflare's routing not having
            // propagated yet. Retry a couple of times before surfacing it as a real error.
            var attempt = 0
            while (fetchedUsers == null &&
                apiManager.lastGetUsersError?.let { it.startsWith("HTTP 404") && it.contains("1042") } == true &&
                attempt < 3
            ) {
                attempt++
                kotlinx.coroutines.delay(3000L)
                fetchedUsers = apiManager.getUsers(account)
            }
            if (fetchedUsers != null) {
                users = fetchedUsers
            } else {
                Toast.makeText(context, S(R.string.error_fetching_users, apiManager.lastGetUsersError), Toast.LENGTH_LONG).show()
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadUsers()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        // Transparent over the wallpaper; the Cloud tab that hosts this no longer pads it.
        color = Color.Transparent
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(
                top = com.mlmvpn.scanner.ui.LocalContentTopInset.current,
                bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current,
            )) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                }
                Text(S(R.string.user_management_mlm), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { loadUsers() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Primary)
                }
            }

            Divider(color = BorderDark)

            // List
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(users) { user ->
                        MlmUserCard(
                            user = user,
                            account = account,
                            apiManager = apiManager,
                            onRefresh = { loadUsers() },
                            onScanUser = onScanUser,
                            onGroupsUpdated = onGroupsUpdated,
                            onEditUser = {
                                editingUser = user
                                showAddUserDialog = true
                            }
                        )
                    }
                }
            }

            // Add FAB
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                FloatingActionButton(
                    onClick = {
                        editingUser = null
                        showAddUserDialog = true
                    },
                    containerColor = Primary,
                    contentColor = BgDark
                ) {
                    Icon(Icons.Default.Add, "Add User")
                }
            }
        }
    }

    if (showAddUserDialog) {
        AddMlmUserDialog(
            account = account,
            apiManager = apiManager,
            editingUser = editingUser,
            onDismiss = {
                showAddUserDialog = false
                editingUser = null
            },
            onUserAdded = {
                showAddUserDialog = false
                editingUser = null
                loadUsers()
            }
        )
    }
}

@Composable
fun MlmUserCard(
    user: MlmUser,
    account: CloudAccount,
    apiManager: MlmApiManager,
    onRefresh: () -> Unit,
    onScanUser: (String) -> Unit,
    onGroupsUpdated: () -> Unit = {},
    onEditUser: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExpanded by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass(RoundedCornerShape(16.dp))
            .clickable { isExpanded = !isExpanded }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            user.isActive == 0 -> RedError
                            user.isOnline == 1 -> GreenOk
                            else -> TextDim
                        }
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(user.username, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                // The byte counter where the engine has one, rounded: `used_gb` is a float mirror,
                // and truncating it to whole megabytes showed a third figure beside the two others.
                val usedMb = user.usedBytes?.takeIf { it > 0 }?.let { Math.round(it / 1048576.0).toInt() }
                    ?: Math.round((user.usedGb ?: 0f) * 1024).toInt()
                val limitStr = if (user.limitGb != null) S(R.string.gb, user.limitGb) else S(R.string.unlimited)
                Text(S(R.string.used_so_far_usedmb_mb_of_limitstr, usedMb, limitStr), color = TextMuted, fontSize = 12.sp)
                if (user.limitGb != null && user.limitGb > 0f) {
                    val progress = ((user.usedGb ?: 0f) / user.limitGb).coerceIn(0f, 1f)
                    val progColor = if (progress > 0.9f) RedError else if (progress > 0.7f) YellowWarn else GreenOk
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(8.dp)),
                        color = progColor,
                        trackColor = BorderDark
                    )
                }
            }
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Primary, strokeWidth = 2.dp)
            } else {
                IconButton(onClick = {
                    isBusy = true
                    scope.launch {
                        val success = apiManager.toggleUser(account, user.username)
                        isBusy = false
                        if (success) {
                            onRefresh()
                        } else {
                            Toast.makeText(context, S(R.string.could_not_change_the_user_s_state), Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Icon(
                        if (user.isActive == 1) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Toggle",
                        tint = if (user.isActive == 1) TextMuted else GreenOk
                    )
                }
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.fillMaxWidth().background(BgDark.copy(alpha = 0.5f)).padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    InfoItem(S(R.string.port), user.port.toString())
                    InfoItem(S(R.string.validity), if (user.expiryDays != null) S(R.string.days, user.expiryDays) else "∞")
                    InfoItem(S(R.string.protocol), (user.connectionType ?: "xhttp").uppercase())
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ActionBtn(Icons.Default.Download, S(R.string.get_config)) {
                        isBusy = true
                        scope.launch {
                            val configs = apiManager.getUserConfigs(account, user.username)
                            if (configs.isNotEmpty()) {
                                val engineNodes = configs.mapIndexed { index, uri ->
                                    com.mlmvpn.scanner.models.VpnNode(
                                        id = "mlm_${account.id}_${user.username}_$index",
                                        name = java.net.URLDecoder.decode(uri.substringAfterLast("#", "MLM ${user.username}"), "UTF-8"),
                                        uri = uri,
                                        type = if (uri.startsWith("vless")) "vless" else "trojan",
                                        engineType = "MLM"
                                    )
                                }
                                val groupManager = com.mlmvpn.scanner.data.GroupManager(context)
                                val newGroup = com.mlmvpn.scanner.data.CloudGroup(
                                    id = "mlm_${account.id}_${user.username}_${System.currentTimeMillis()}",
                                    accountId = account.id,
                                    date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                                    title = S(R.string.configs_for, user.username),
                                    nodes = engineNodes
                                )
                                groupManager.cloudGroups.add(0, newGroup)
                                groupManager.saveCloudGroups()
                                onGroupsUpdated()
                                Toast.makeText(context, S(R.string.the_configs_were_added_to_the_cloud), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, S(R.string.no_config_found), Toast.LENGTH_SHORT).show()
                            }
                            isBusy = false
                        }
                    }
                    ActionBtn(Icons.Default.Language, S(R.string.status)) {
                        val statusLink = "${account.mlmWorkerUrl?.trimEnd('/')}/status/${user.username}"
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("status", statusLink)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, S(R.string.status_page_link_copied), Toast.LENGTH_SHORT).show()
                    }
                    ActionBtn(Icons.Default.Speed, S(R.string.ip_scan)) {
                        isBusy = true
                        scope.launch {
                            val configs = apiManager.getUserConfigs(account, user.username)
                            if (configs.isNotEmpty()) {
                                onScanUser(configs.first())
                            } else {
                                Toast.makeText(context, S(R.string.no_config_found), Toast.LENGTH_SHORT).show()
                            }
                            isBusy = false
                        }
                    }
                    ActionBtn(Icons.Default.Edit, S(R.string.edit)) {
                        onEditUser()
                    }
                    ActionBtn(Icons.Default.Delete, S(R.string.delete), RedError) {
                        showDeleteConfirm = true
                    }
                }
            }
        }
    }
    
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(S(R.string.delete_user), color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text(S(R.string.are_you_sure_you_want_to_delete, user.username), color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    isBusy = true
                    scope.launch {
                        val success = apiManager.deleteUser(account, user.username)
                        isBusy = false
                        if (success) {
                            onRefresh()
                        } else {
                            Toast.makeText(context, S(R.string.could_not_delete_the_user), Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text(S(R.string.yes_delete), color = RedError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(S(R.string.cancel), color = TextPrimary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}

@Composable
fun InfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextMuted, fontSize = 10.sp)
        Text(value, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ActionBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Primary, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(8.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 10.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMlmUserDialog(
    account: CloudAccount,
    apiManager: MlmApiManager,
    editingUser: MlmUser?,
    onDismiss: () -> Unit,
    onUserAdded: () -> Unit
) {
    val isEditMode = editingUser != null
    var username by remember { mutableStateOf(editingUser?.username ?: "") }
    var limitGb by remember { mutableStateOf(editingUser?.limitGb?.toString() ?: "") }
    var dailyLimitGb by remember { mutableStateOf(editingUser?.dailyLimitGb?.toString() ?: "") }
    var expiryDays by remember { mutableStateOf(editingUser?.expiryDays?.toString() ?: "") }
    var isSecure by remember { mutableStateOf(editingUser?.tls == "tls" || editingUser?.tls == null) }
    val initialPorts = editingUser?.port?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: setOf("443")
    var selectedPorts by remember { mutableStateOf(initialPorts) }
    var fingerprint by remember { mutableStateOf(editingUser?.fingerprint ?: "chrome") }
    var proxyIp by remember { mutableStateOf(editingUser?.proxyIp ?: "") }
    var isBusy by remember { mutableStateOf(false) }
    var portExpanded by remember { mutableStateOf(false) }
    val securePorts = listOf("443", "8443", "2053", "2083", "2087", "2096")
    val normalPorts = listOf("80", "8080", "8880", "2052", "2082", "2086", "2095")
    val currentPorts = if (isSecure) securePorts else normalPorts
    
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogSurface,
        modifier = androidx.compose.ui.Modifier.border(
            0.7.dp,
            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f),
            CardShape,
        ),
                shape = CardShape,
        title = { Text(if (isEditMode) S(R.string.edit_user) else S(R.string.new_user), color = TextPrimary) },
        text = {
            Column(modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(S(R.string.username), color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = isEditMode, // Cannot change username when editing
                    colors = iosFieldColors()
                ,
        shape = ControlShape,)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = limitGb,
                    onValueChange = { limitGb = it },
                    label = { Text(S(R.string.total_limit_gb_empty_unlimited), color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = iosFieldColors()
                ,
        shape = ControlShape,)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = dailyLimitGb,
                    onValueChange = { dailyLimitGb = it },
                    label = { Text(S(R.string.daily_limit_gb_empty_unlimited), color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = iosFieldColors()
                ,
        shape = ControlShape,)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = expiryDays,
                    onValueChange = { expiryDays = it },
                    label = { Text(S(R.string.validity_days_empty_unlimited), color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = iosFieldColors()
                ,
        shape = ControlShape,)
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(S(R.string.port_type), color = TextMuted, modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { 
                        isSecure = true
                        selectedPorts = selectedPorts.filter { it in securePorts }.toSet().ifEmpty { setOf("443") }
                    }) {
                        RadioButton(
                            selected = isSecure,
                            onClick = { 
                                isSecure = true
                                selectedPorts = selectedPorts.filter { it in securePorts }.toSet().ifEmpty { setOf("443") }
                            }, 
                            colors = RadioButtonDefaults.colors(selectedColor = Primary)
                        )
                        Text(S(R.string.secure_tls), color = TextPrimary, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { 
                        isSecure = false
                        selectedPorts = selectedPorts.filter { it in normalPorts }.toSet().ifEmpty { setOf("80") }
                    }) {
                        RadioButton(
                            selected = !isSecure, 
                            onClick = { 
                                isSecure = false
                                selectedPorts = selectedPorts.filter { it in normalPorts }.toSet().ifEmpty { setOf("80") }
                            }, 
                            colors = RadioButtonDefaults.colors(selectedColor = Primary)
                        )
                        Text(S(R.string.plain), color = TextPrimary, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                
                ExposedDropdownMenuBox(
                    expanded = portExpanded,
                    onExpandedChange = { portExpanded = !portExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedPorts.joinToString(", "),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(S(R.string.choose_a_port), color = TextMuted) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = portExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        colors = iosFieldColors()
                    ,
        shape = ControlShape,)
                    ExposedDropdownMenu(
                        expanded = portExpanded,
                        onDismissRequest = { portExpanded = false },
                        modifier = Modifier.iosMenu(),
                    ) {
                        currentPorts.forEach { p ->
                            DropdownMenuItem(
                                text = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = selectedPorts.contains(p),
                                            onCheckedChange = null,
                                            colors = CheckboxDefaults.colors(checkedColor = Primary, uncheckedColor = TextMuted)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(p, color = TextPrimary)
                                    }
                                },
                                onClick = {
                                    selectedPorts = if (selectedPorts.contains(p)) {
                                        selectedPorts - p
                                    } else {
                                        selectedPorts + p
                                    }
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = { Text(S(R.string.fingerprint), color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = iosFieldColors()
                ,
        shape = ControlShape,)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = proxyIp,
                    onValueChange = { proxyIp = it },
                    label = { Text(S(R.string.dedicated_proxy_ip), color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = iosFieldColors()
                ,
        shape = ControlShape,)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (username.isBlank()) {
                        Toast.makeText(context, S(R.string.a_username_is_required), Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isBusy = true
                    scope.launch {
                        val success = if (isEditMode) {
                            val req = MlmUpdateUserRequest(
                                limitGb = limitGb.toFloatOrNull(),
                                dailyLimitGb = dailyLimitGb.toFloatOrNull(),
                                expiryDays = expiryDays.toIntOrNull(),
                                tls = if (isSecure) "tls" else "none",
                                port = selectedPorts.joinToString(",").ifEmpty { if (isSecure) "443" else "80" },
                                fingerprint = fingerprint.trim().ifEmpty { "chrome" },
                                proxyIp = proxyIp.trim().ifEmpty { null },
                                ips = editingUser?.ips
                            )
                            apiManager.updateUser(account, username.trim(), req)
                        } else {
                            val req = MlmCreateUserRequest(
                                username = username.trim(),
                                limitGb = limitGb.toFloatOrNull(),
                                dailyLimitGb = dailyLimitGb.toFloatOrNull(),
                                expiryDays = expiryDays.toIntOrNull(),
                                tls = if (isSecure) "tls" else "none",
                                port = selectedPorts.joinToString(",").ifEmpty { if (isSecure) "443" else "80" },
                                fingerprint = fingerprint.trim().ifEmpty { "chrome" },
                                proxyIp = proxyIp.trim().ifEmpty { null }
                            )
                            apiManager.createUser(account, req)
                        }
                        isBusy = false
                        if (success) {
                            Toast.makeText(context, if (isEditMode) S(R.string.user_edited_successfully) else S(R.string.user_created_successfully), Toast.LENGTH_SHORT).show()
                            onUserAdded()
                        } else {
                            Toast.makeText(context, if (isEditMode) S(R.string.could_not_edit_the_user) else S(R.string.could_not_create_the_user), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !isBusy
            ) {
                if (isBusy) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgDark)
                else Text(if (isEditMode) S(R.string.save) else S(R.string.create), color = BgDark)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(S(R.string.cancel), color = TextMuted)
            }
        }
    )
}
