package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Https
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.database.SiteEntity
import com.example.core.database.TunnelConfigEntity
import com.example.core.model.TunnelProvider
import com.example.core.model.TunnelStatus
import com.example.engine.tunnel.TunnelRuntimeState
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.SectionHeader
import com.example.ui.components.TunnelStatusChip
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RoyalBluePrimary
import com.example.ui.theme.StatusOkGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelScreen(
    sites: List<SiteEntity>,
    runningSiteIds: Set<String>,
    tunnelConfigs: List<TunnelConfigEntity>,
    tunnelStates: Map<String, TunnelRuntimeState>,
    onSaveTunnelConfig: (String, TunnelProvider, String, Boolean, Boolean, String, String?, String) -> Unit,
    onStartTunnelWithApproval: (TunnelConfigEntity, Int) -> Unit,
    onStopTunnel: (TunnelConfigEntity) -> Unit
) {
    var selectedSite by remember { mutableStateOf<SiteEntity?>(sites.firstOrNull()) }
    LaunchedEffect(sites) {
        if (selectedSite == null && sites.isNotEmpty()) selectedSite = sites.first()
    }

    val existingConfig = tunnelConfigs.firstOrNull { it.siteId == selectedSite?.id }
    val runtimeState = selectedSite?.let { tunnelStates[it.id] } ?: TunnelRuntimeState()

    var provider by remember(selectedSite?.id, existingConfig) {
        mutableStateOf(
            TunnelProvider.entries.firstOrNull { it.name == existingConfig?.provider }
                ?: TunnelProvider.CLOUDFLARED
        )
    }
    var endpoint by remember(selectedSite?.id, existingConfig) {
        mutableStateOf(existingConfig?.controlEndpoint ?: "")
    }
    var enforceHttps by remember(selectedSite?.id, existingConfig) {
        mutableStateOf(existingConfig?.enforceHttps ?: true)
    }
    var requireAuth by remember(selectedSite?.id, existingConfig) {
        mutableStateOf(existingConfig?.requireAuth ?: true)
    }
    var authUsername by remember(selectedSite?.id, existingConfig) {
        mutableStateOf(existingConfig?.authUsername ?: "admin")
    }
    var authPassword by remember(selectedSite?.id) { mutableStateOf("") }
    var allowedIpCidr by remember(selectedSite?.id, existingConfig) {
        mutableStateOf(existingConfig?.allowedIpCidr ?: "")
    }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var showApprovalDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionHeader(
                title = "المشاركة عبر الإنترنت (Tunnel)",
                subtitle = "تكامل آمن مع خدمات النفق المشفرة — لا تُنشأ روابط وهمية أبداً"
            )
        }

        if (sites.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Filled.CloudOff,
                    title = "لا توجد مواقع متاحة للمشاركة",
                    description = "أنشئ موقعاً محلياً وشغّله أولاً قبل تهيئة المشاركة عبر الإنترنت."
                )
            }
        } else {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sites.forEach { site ->
                        FilterChip(
                            selected = selectedSite?.id == site.id,
                            onClick = { selectedSite = site },
                            label = { Text("${site.name} (:${site.port})") }
                        )
                    }
                }
            }

            // Status & Connection Monitor Card
            item {
                val site = selectedSite!!
                val isSiteRunning = runningSiteIds.contains(site.id)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, RoyalBluePrimary.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("حالة النفق الآمن", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            TunnelStatusChip(status = runtimeState.status)
                        }

                        Text(
                            text = runtimeState.detailMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!runtimeState.verifiedPublicUrl.isNullOrBlank()) {
                            Text(
                                text = "الرابط العام المتحقق منه: ${runtimeState.verifiedPublicUrl}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = StatusOkGreen,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                text = "الرابط العام: غير نشط (لا يتم توليد روابط خارجية إلا عند اتصال نفق حقيقي)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = "حالة الموقع المحلي (${site.port}): ${if (isSiteRunning) "يعمل" else "متوقف"}  |  جلسات النفق: ${runtimeState.activeSessionsCount}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily)
                        )
                    }
                }
            }

            // Tunnel Configuration & Security Form
            item {
                val site = selectedSite!!
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("إعدادات النفق والحماية", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        ExposedDropdownMenuBox(
                            expanded = providerMenuExpanded,
                            onExpandedChange = { providerMenuExpanded = !providerMenuExpanded }
                        ) {
                            OutlinedTextField(
                                value = provider.titleAr,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("مزود خدمة النفق (Tunnel Provider)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = providerMenuExpanded,
                                onDismissRequest = { providerMenuExpanded = false }
                            ) {
                                TunnelProvider.entries.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.titleAr) },
                                        onClick = { provider = p; providerMenuExpanded = false }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            label = { Text("عنوان الوسيط / خادم النفق (HTTPS)") },
                            placeholder = { Text("https://tunnel.yourdomain.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("tunnel_endpoint_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Https, contentDescription = null, tint = RoyalBluePrimary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("فرض تشفير HTTPS حصراً")
                            }
                            Switch(checked = enforceHttps, onCheckedChange = { enforceHttps = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Lock, contentDescription = null, tint = CrimsonRed, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("حماية بكلمة مرور عند المشاركة")
                            }
                            Switch(checked = requireAuth, onCheckedChange = { requireAuth = it })
                        }

                        if (requireAuth) {
                            OutlinedTextField(
                                value = authUsername,
                                onValueChange = { authUsername = it },
                                label = { Text("اسم مستخدم الحماية") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = authPassword,
                                onValueChange = { authPassword = it },
                                label = { Text("كلمة مرور المشاركة") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        OutlinedTextField(
                            value = allowedIpCidr,
                            onValueChange = { allowedIpCidr = it },
                            label = { Text("تقييد IP المسموح (اختياري CIDR)") },
                            placeholder = { Text("مثال: 192.168.1.0/24") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onSaveTunnelConfig(
                                        site.id,
                                        provider,
                                        endpoint,
                                        enforceHttps,
                                        requireAuth,
                                        authUsername,
                                        authPassword.ifBlank { null },
                                        allowedIpCidr
                                    )
                                },
                                modifier = Modifier.testTag("save_tunnel_cfg_btn")
                            ) {
                                Text("حفظ الإعدادات")
                            }

                            if (runtimeState.status == TunnelStatus.CONNECTED) {
                                Button(
                                    onClick = { existingConfig?.let { onStopTunnel(it) } },
                                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed),
                                    modifier = Modifier.testTag("stop_tunnel_btn")
                                ) {
                                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("إيقاف المشاركة")
                                }
                            } else {
                                Button(
                                    onClick = {
                                        onSaveTunnelConfig(
                                            site.id,
                                            provider,
                                            endpoint,
                                            enforceHttps,
                                            requireAuth,
                                            authUsername,
                                            authPassword.ifBlank { null },
                                            allowedIpCidr
                                        )
                                        showApprovalDialog = true
                                    },
                                    modifier = Modifier.testTag("start_tunnel_btn")
                                ) {
                                    Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("بدء المشاركة")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showApprovalDialog && selectedSite != null) {
        val site = selectedSite!!
        AlertDialog(
            onDismissRequest = { showApprovalDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Security, contentDescription = null, tint = CrimsonRed)
                    Spacer(Modifier.width(8.dp))
                    Text("موافقة المستخدم على المشاركة الخارجية")
                }
            },
            text = {
                Text(
                    "أنت على وشك محاولة مشاركة الموقع المحلي '${site.name}' (المنفذ ${site.port}) خارج الشبكة المحلية عبر ${provider.titleAr}.\n\n" +
                        "• تأكد من تفعيل HTTPS وحماية كلمة المرور.\n" +
                        "• لن يتم فتح أي منافذ خارجية تلقائياً بدون هذه الموافقة."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showApprovalDialog = false
                        val cfg = existingConfig ?: TunnelConfigEntity(
                            id = "tunnel_${site.id}",
                            siteId = site.id,
                            provider = provider.name,
                            controlEndpoint = endpoint,
                            enforceHttps = enforceHttps,
                            requireAuth = requireAuth,
                            authUsername = authUsername,
                            allowedIpCidr = allowedIpCidr
                        )
                        onStartTunnelWithApproval(cfg, site.port)
                    },
                    modifier = Modifier.testTag("confirm_tunnel_approval_btn")
                ) { Text("أوافق وأبدأ الاتصال") }
            },
            dismissButton = {
                TextButton(onClick = { showApprovalDialog = false }) { Text("إلغاء") }
            }
        )
    }
}
