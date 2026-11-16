package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.core.database.AppLogEntity
import com.example.core.database.SiteEntity
import com.example.core.model.EngineStatus
import com.example.core.model.ProjectType
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.EngineStatusChip
import com.example.ui.components.SectionHeader
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RoyalBluePrimary
import com.example.ui.theme.StatusOkGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SitesScreen(
    sites: List<SiteEntity>,
    runningSiteIds: Set<String>,
    logs: List<AppLogEntity>,
    onCreateSite: (String, Int, ProjectType, String, String, Boolean) -> Unit,
    onUpdateSite: (SiteEntity) -> Unit,
    onStartSite: (SiteEntity) -> Unit,
    onStopSite: (SiteEntity) -> Unit,
    onDeleteSite: (SiteEntity) -> Unit,
    onOpenFilesForSite: (SiteEntity) -> Unit,
    onCreatePythonVenv: (SiteEntity, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingSite by remember { mutableStateOf<SiteEntity?>(null) }
    var logsDialogSite by remember { mutableStateOf<SiteEntity?>(null) }
    var deletingSite by remember { mutableStateOf<SiteEntity?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.testTag("fab_create_site"),
                containerColor = RoyalBluePrimary,
                contentColor = PureWhite
            ) {
                Icon(Icons.Filled.Add, contentDescription = "إنشاء موقع جديد")
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
                    title = "إدارة المواقع المستضافة",
                    subtitle = "كل موقع يعمل في مجلد معزول (public_html/config/logs/backups) ومنفذ مستقل"
                )
            }

            if (sites.isEmpty()) {
                item {
                    EmptyStatePanel(
                        icon = Icons.Filled.Language,
                        title = "لا توجد مواقع مضافة بعد",
                        description = "أنشئ أول موقع استضافة محلي (HTML أو PHP أو Python) مع منفذ ومجلد معزول خاص به.",
                        actionButton = {
                            Button(
                                onClick = { showCreateDialog = true },
                                modifier = Modifier.testTag("empty_create_site_btn")
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("إنشاء موقع جديد")
                            }
                        }
                    )
                }
            } else {
                items(sites, key = { it.id }) { site ->
                    val isRunning = runningSiteIds.contains(site.id)
                    val engineStatus = when {
                        isRunning -> EngineStatus.RUNNING
                        site.status == "ERROR" -> EngineStatus.ERROR
                        else -> EngineStatus.STOPPED
                    }
                    val typeTitle = ProjectType.entries.firstOrNull { it.name == site.projectType }?.titleAr
                        ?: site.projectType

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("site_card_${site.id}"),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(
                            1.dp,
                            if (isRunning) RoyalBluePrimary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = site.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "$typeTitle • ${site.runtimeVersion}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                EngineStatusChip(status = engineStatus)
                            }

                            Spacer(Modifier.height(10.dp))

                            Text(
                                text = "المنفذ: ${site.port}  |  الملف الرئيسي: ${site.entryFile}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = RoyalBluePrimary
                            )
                            Text(
                                text = "المجلد المعزول: ${site.rootFolder}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (site.allowLanAccess) "الوصول: الشبكة المحلية + localhost (0.0.0.0)" else "الوصول: محلي فقط (127.0.0.1)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (site.allowLanAccess) CrimsonRed else StatusOkGreen
                            )

                            if (site.lastError.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "آخر خطأ: ${site.lastError}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CrimsonRed
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isRunning) {
                                    Button(
                                        onClick = { onStopSite(site) },
                                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed),
                                        modifier = Modifier.testTag("stop_site_${site.id}")
                                    ) {
                                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("إيقاف")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${site.port}"))
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.testTag("open_site_browser_${site.id}")
                                    ) {
                                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("فتح")
                                    }
                                } else {
                                    Button(
                                        onClick = { onStartSite(site) },
                                        colors = ButtonDefaults.buttonColors(containerColor = RoyalBluePrimary),
                                        modifier = Modifier.testTag("start_site_${site.id}")
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("تشغيل")
                                    }
                                }

                                if (site.projectType.startsWith("PYTHON_")) {
                                    OutlinedButton(
                                        onClick = { onCreatePythonVenv(site, site.runtimeVersion) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("venv")
                                    }
                                }

                                Spacer(Modifier.weight(1f))

                                IconButton(
                                    onClick = { onOpenFilesForSite(site) },
                                    modifier = Modifier.testTag("files_site_${site.id}")
                                ) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = "ملفات الموقع", tint = RoyalBluePrimary)
                                }
                                IconButton(
                                    onClick = { logsDialogSite = site },
                                    modifier = Modifier.testTag("logs_site_${site.id}")
                                ) {
                                    Icon(Icons.Filled.BugReport, contentDescription = "سجل الأخطاء", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(
                                    onClick = { editingSite = site },
                                    modifier = Modifier.testTag("edit_site_${site.id}")
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = "إعدادات الموقع")
                                }
                                IconButton(
                                    onClick = { deletingSite = site },
                                    modifier = Modifier.testTag("delete_site_${site.id}")
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "حذف الموقع", tint = CrimsonRed)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showCreateDialog) {
        SiteEditorDialog(
            title = "إنشاء موقع استضافة جديد",
            existingSite = null,
            suggestedPort = (8080 + sites.size).coerceAtMost(65000),
            onDismiss = { showCreateDialog = false },
            onSave = { name, port, type, version, entry, allowLan ->
                onCreateSite(name, port, type, version, entry, allowLan)
                showCreateDialog = false
            }
        )
    }

    editingSite?.let { site ->
        SiteEditorDialog(
            title = "إعدادات الموقع: ${site.name}",
            existingSite = site,
            suggestedPort = site.port,
            onDismiss = { editingSite = null },
            onSave = { name, port, type, version, entry, allowLan ->
                onUpdateSite(
                    site.copy(
                        name = name,
                        port = port,
                        projectType = type.name,
                        runtimeVersion = version,
                        entryFile = entry,
                        allowLanAccess = allowLan
                    )
                )
                editingSite = null
            }
        )
    }

    logsDialogSite?.let { site ->
        val siteLogs = logs.filter { it.source == "SITE:${site.name}" }
        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
        AlertDialog(
            onDismissRequest = { logsDialogSite = null },
            title = { Text("سجل أخطاء وعمليات: ${site.name}") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (siteLogs.isEmpty()) {
                        Text("لا توجد سجلات مسجلة لهذا الموقع حتى الآن.")
                    } else {
                        siteLogs.take(40).forEach { entry ->
                            Text(
                                text = "[${fmt.format(Date(entry.timestamp))}] [${entry.level}] ${entry.message}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = if (entry.level == "ERROR" || entry.level == "SECURITY") CrimsonRed else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 3.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { logsDialogSite = null }) { Text("إغلاق") }
            }
        )
    }

    deletingSite?.let { site ->
        AlertDialog(
            onDismissRequest = { deletingSite = null },
            title = { Text("تأكيد حذف الموقع") },
            text = { Text("سيتم إيقاف الموقع '${site.name}' وحذف مجلده المعزول وكافة ملفاته نهائياً. هل تريد المتابعة؟") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSite(site)
                        deletingSite = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed)
                ) { Text("حذف نهائياً") }
            },
            dismissButton = {
                TextButton(onClick = { deletingSite = null }) { Text("إلغاء") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteEditorDialog(
    title: String,
    existingSite: SiteEntity?,
    suggestedPort: Int,
    onDismiss: () -> Unit,
    onSave: (String, Int, ProjectType, String, String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf(existingSite?.name ?: "") }
    var portText by remember { mutableStateOf((existingSite?.port ?: suggestedPort).toString()) }
    var selectedType by remember {
        mutableStateOf(
            ProjectType.entries.firstOrNull { it.name == existingSite?.projectType }
                ?: ProjectType.STATIC_HTML
        )
    }
    var runtimeVersion by remember {
        mutableStateOf(
            existingSite?.runtimeVersion ?: when (selectedType) {
                ProjectType.PHP_APP -> "8.4"
                ProjectType.NODE_SOCKETIO -> "20"
                ProjectType.PYTHON_FLASK, ProjectType.PYTHON_DJANGO, ProjectType.PYTHON_FASTAPI -> "3.12"
                else -> "HTML5"
            }
        )
    }
    var entryFile by remember { mutableStateOf(existingSite?.entryFile ?: selectedType.defaultEntry) }
    var allowLan by remember { mutableStateOf(existingSite?.allowLanAccess ?: false) }

    var typeMenuExpanded by remember { mutableStateOf(false) }
    var versionMenuExpanded by remember { mutableStateOf(false) }

    val availableVersions = when (selectedType) {
        ProjectType.PHP_APP -> listOf("8.4", "8.3", "8.2", "8.1")
        ProjectType.NODE_SOCKETIO -> listOf("22", "20", "18")
        ProjectType.PYTHON_FLASK, ProjectType.PYTHON_DJANGO, ProjectType.PYTHON_FASTAPI -> listOf("3.13", "3.12", "3.11", "3.10", "3.9")
        ProjectType.STATIC_HTML -> listOf("HTML5")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم الموقع") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("site_name_input")
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("رقم المنفذ (1024 - 65535)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("site_port_input")
                )
                ExposedDropdownMenuBox(
                    expanded = typeMenuExpanded,
                    onExpandedChange = { typeMenuExpanded = !typeMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedType.titleAr,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("نوع المشروع") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false }
                    ) {
                        ProjectType.entries.forEach { pt ->
                            DropdownMenuItem(
                                text = { Text(pt.titleAr) },
                                onClick = {
                                    selectedType = pt
                                    entryFile = pt.defaultEntry
                                    runtimeVersion = when (pt) {
                                        ProjectType.PHP_APP -> "8.4"
                                        ProjectType.NODE_SOCKETIO -> "20"
                                        ProjectType.STATIC_HTML -> "HTML5"
                                        else -> "3.12"
                                    }
                                    typeMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = versionMenuExpanded,
                    onExpandedChange = { versionMenuExpanded = !versionMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = runtimeVersion,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("إصدار بيئة التشغيل") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = versionMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = versionMenuExpanded,
                        onDismissRequest = { versionMenuExpanded = false }
                    ) {
                        availableVersions.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v) },
                                onClick = {
                                    runtimeVersion = v
                                    versionMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = entryFile,
                    onValueChange = { entryFile = it },
                    label = { Text("ملف البداية (Entry File)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("السماح بالوصول عبر الشبكة المحلية (LAN)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "الوضع الافتراضي 127.0.0.1 فقط للحماية",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = allowLan, onCheckedChange = { allowLan = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portText.toIntOrNull() ?: suggestedPort
                    if (name.isNotBlank()) {
                        onSave(name.trim(), port, selectedType, runtimeVersion, entryFile.trim(), allowLan)
                    }
                },
                modifier = Modifier.testTag("save_site_dialog_btn")
            ) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}
