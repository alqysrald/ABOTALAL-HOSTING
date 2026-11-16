package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Https
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.database.DomainEntity
import com.example.core.database.SiteEntity
import com.example.engine.domain.LocalDnsVpnService
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.SectionHeader
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RoyalBluePrimary
import com.example.ui.theme.StatusOkGreen
import com.example.ui.theme.StatusWarning

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DomainsScreen(
    domains: List<DomainEntity>,
    sites: List<SiteEntity>,
    isDnsVpnRunning: Boolean,
    onAddDomain: (String, SiteEntity, String, String, String, String, Boolean) -> Unit,
    onVerifyGlobalDns: (DomainEntity) -> Unit,
    onImportSslCert: (DomainEntity, Uri) -> Unit,
    onLinkTunnel: (DomainEntity, String, Boolean) -> Unit,
    onDeleteDomain: (DomainEntity) -> Unit
) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingSslDomain by remember { mutableStateOf<DomainEntity?>(null) }
    var tunnelLinkDomain by remember { mutableStateOf<DomainEntity?>(null) }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            context.startService(Intent(context, LocalDnsVpnService::class.java))
        }
    }

    val sslCertLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val dom = pendingSslDomain
        if (uri != null && dom != null) {
            onImportSslCert(dom, uri)
        }
    }

    Scaffold(
        floatingActionButton = {
            if (sites.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.testTag("fab_add_domain"),
                    containerColor = RoyalBluePrimary,
                    contentColor = PureWhite
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "إضافة دومين")
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionHeader(
                    title = "إدارة النطاقات (Domain Manager)",
                    subtitle = "دعم النطاقات المحلية (*.test) والدومينات الفرعية والعامة و SSL"
                )
            }

            // Local DNS via VpnService Card
            item {
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
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.VpnKey, contentDescription = null, tint = RoyalBluePrimary)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("خادم DNS المحلي (Android VpnService)", fontWeight = FontWeight.Bold)
                                    Text(
                                        text = if (isDnsVpnRunning) "يعمل (10.222.0.2) — يحل النطاقات المحلية إلى 127.0.0.1" else "متوقف — شغّله لفتح *.test مباشرة في المتصفح دون إنترنت",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isDnsVpnRunning) StatusOkGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = isDnsVpnRunning,
                                onCheckedChange = { enable ->
                                    if (enable) {
                                        val prep = VpnService.prepare(context)
                                        if (prep != null) {
                                            vpnPrepareLauncher.launch(prep)
                                        } else {
                                            context.startService(Intent(context, LocalDnsVpnService::class.java))
                                        }
                                    } else {
                                        val stopIntent = Intent(context, LocalDnsVpnService::class.java).apply {
                                            action = LocalDnsVpnService.ACTION_STOP
                                        }
                                        context.startService(stopIntent)
                                    }
                                },
                                modifier = Modifier.testTag("toggle_dns_vpn")
                            )
                        }
                        Text(
                            text = "ملاحظة: إضافة دومين محلي (مثل project.test أو example.com) يربطه محلياً فقط، ولا يجعله متاحاً عالمياً على الإنترنت بدون DNS عام أو نفق Tunnel.",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusWarning
                        )
                    }
                }
            }

            if (sites.isEmpty()) {
                item {
                    EmptyStatePanel(
                        icon = Icons.Filled.Language,
                        title = "أنشئ موقعاً محلياً أولاً",
                        description = "يجب وجود موقع محلي واحد على الأقل لربط الدومينات وتحديد Document Root الخاص بها."
                    )
                }
            } else if (domains.isEmpty()) {
                item {
                    EmptyStatePanel(
                        icon = Icons.Filled.Dns,
                        title = "لا توجد نطاقات مضافة بعد",
                        description = "أضف نطاقاً محلياً مثل project.test أو نطاقاً عاماً (.com / .net / .org) أو نطاقاً فرعياً واربطه بأي موقع.",
                        actionButton = {
                            Button(onClick = { showAddDialog = true }, modifier = Modifier.testTag("empty_add_domain_btn")) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("إضافة دومين")
                            }
                        }
                    )
                }
            } else {
                items(domains, key = { it.id }) { dom ->
                    val linkedSite = sites.firstOrNull { it.id == dom.siteId }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dom.domainName,
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                        fontWeight = FontWeight.Bold,
                                        color = RoyalBluePrimary
                                    )
                                    Text(
                                        text = "الموقع: ${linkedSite?.name ?: "غير محدد"} (:${linkedSite?.port ?: "-"})",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                IconButton(onClick = { onDeleteDomain(dom) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "حذف النطاق", tint = CrimsonRed)
                                }
                            }

                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                DomainBadge(
                                    label = if (dom.isLocalDomain) "نطاق محلي (Offline)" else "نطاق عام (TLD)",
                                    isPositive = dom.isLocalDomain || dom.dnsResolvedGlobally
                                )
                                if (dom.parentDomain.isNotBlank()) {
                                    DomainBadge(label = "فرعي من ${dom.parentDomain}", isPositive = true)
                                }
                                DomainBadge(
                                    label = if (dom.sslEnabled) "SSL / HTTPS مفعل" else "بدون SSL",
                                    isPositive = dom.sslEnabled
                                )
                                DomainBadge(
                                    label = if (dom.tunnelLinked) "مربوط بـ Tunnel" else "محلي فقط",
                                    isPositive = dom.tunnelLinked
                                )
                            }

                            Text(
                                text = "Document Root: ${dom.documentRoot}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "DNS: A=${dom.dnsARecord.ifBlank { "-" }} | CNAME=${dom.dnsCnameRecord.ifBlank { "-" }} | عام=${if (dom.dnsResolvedGlobally) "موجه" else "محلي فقط"}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily)
                            )
                            if (dom.sslSubject.isNotBlank()) {
                                Text(
                                    text = "شهادة SSL: ${dom.sslSubject}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusOkGreen
                                )
                            }

                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!dom.isLocalDomain) {
                                    OutlinedButton(onClick = { onVerifyGlobalDns(dom) }) {
                                        Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("فحص DNS")
                                    }
                                }
                                OutlinedButton(onClick = {
                                    pendingSslDomain = dom
                                    sslCertLauncher.launch(arrayOf("*/*"))
                                }) {
                                    Icon(Icons.Filled.Https, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("استيراد SSL")
                                }
                                OutlinedButton(onClick = { tunnelLinkDomain = dom }) {
                                    Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("ربط Tunnel")
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showAddDialog && sites.isNotEmpty()) {
        AddDomainDialog(
            sites = sites,
            onDismiss = { showAddDialog = false },
            onAdd = { name, site, docRoot, aRec, cname, txt, ssl ->
                onAddDomain(name, site, docRoot, aRec, cname, txt, ssl)
                showAddDialog = false
            }
        )
    }

    tunnelLinkDomain?.let { dom ->
        var hostText by remember { mutableStateOf(dom.tunnelHostname) }
        AlertDialog(
            onDismissRequest = { tunnelLinkDomain = null },
            title = { Text("ربط ${dom.domainName} بخدمة Tunnel") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أدخل عنوان النفق العام المرتبط لتوجيه الدومين الحقيقي عبر الإنترنت:", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = hostText,
                        onValueChange = { hostText = it },
                        label = { Text("عنوان النفق / Hostname") },
                        placeholder = { Text("https://tunnel.example.com") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onLinkTunnel(dom, hostText, hostText.isNotBlank())
                    tunnelLinkDomain = null
                }) { Text("حفظ الربط") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onLinkTunnel(dom, "", false)
                    tunnelLinkDomain = null
                }) { Text("فصل") }
            }
        )
    }
}

@Composable
private fun DomainBadge(label: String, isPositive: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (isPositive) RoyalBluePrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (isPositive) RoyalBluePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDomainDialog(
    sites: List<SiteEntity>,
    onDismiss: () -> Unit,
    onAdd: (String, SiteEntity, String, String, String, String, Boolean) -> Unit
) {
    var domainName by remember { mutableStateOf("project.test") }
    var selectedSite by remember { mutableStateOf(sites.first()) }
    var documentRoot by remember { mutableStateOf(".") }
    var dnsA by remember { mutableStateOf("127.0.0.1") }
    var dnsCname by remember { mutableStateOf("") }
    var dnsTxt by remember { mutableStateOf("") }
    var siteExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة دومين أو نطاق فرعي") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = domainName,
                    onValueChange = { domainName = it },
                    label = { Text("اسم الدومين (مثل project.test أو app.site.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("domain_name_input")
                )
                ExposedDropdownMenuBox(expanded = siteExpanded, onExpandedChange = { siteExpanded = !siteExpanded }) {
                    OutlinedTextField(
                        value = "${selectedSite.name} (:${selectedSite.port})",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("الموقع المرتبط") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = siteExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = siteExpanded, onDismissRequest = { siteExpanded = false }) {
                        sites.forEach { s ->
                            DropdownMenuItem(text = { Text("${s.name} (:${s.port})") }, onClick = { selectedSite = s; siteExpanded = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = documentRoot,
                    onValueChange = { documentRoot = it },
                    label = { Text("Document Root (نسبي داخل الموقع، مثل . أو public)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dnsA,
                    onValueChange = { dnsA = it },
                    label = { Text("سجل DNS A") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dnsCname,
                    onValueChange = { dnsCname = it },
                    label = { Text("سجل CNAME (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dnsTxt,
                    onValueChange = { dnsTxt = it },
                    label = { Text("سجل TXT (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (domainName.isNotBlank()) {
                        onAdd(domainName, selectedSite, documentRoot, dnsA, dnsCname, dnsTxt, false)
                    }
                },
                modifier = Modifier.testTag("confirm_add_domain_btn")
            ) { Text("إضافة وربط") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
