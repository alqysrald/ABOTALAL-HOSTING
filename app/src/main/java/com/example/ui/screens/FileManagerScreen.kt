package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.core.database.SiteEntity
import com.example.core.model.FileEntry
import com.example.core.model.TransferProgress
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.SectionHeader
import com.example.ui.components.formatBytes
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RoyalBluePrimary
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FileManagerScreen(
    sites: List<SiteEntity>,
    selectedSite: SiteEntity?,
    currentDir: File?,
    entries: List<FileEntry>,
    clipboardFile: Pair<String, Boolean>?,
    transferProgress: TransferProgress?,
    onSelectSite: (SiteEntity) -> Unit,
    onNavigateInto: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onRenameEntry: (FileEntry, String) -> Unit,
    onDeleteEntry: (FileEntry) -> Unit,
    onSetClipboard: (FileEntry, Boolean) -> Unit,
    onPasteClipboard: () -> Unit,
    onUploadFiles: (List<Uri>) -> Unit,
    onImportZip: (Uri) -> Unit,
    onExtractLocalZip: (FileEntry) -> Unit,
    onCompressEntry: (FileEntry) -> Unit,
    onUpdatePermissions: (FileEntry, String) -> Unit,
    onDismissProgress: () -> Unit,
    onLoadFileText: suspend (String) -> Result<String>,
    onSaveFileText: (String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var renamingEntry by remember { mutableStateOf<FileEntry?>(null) }
    var chmodEntry by remember { mutableStateOf<FileEntry?>(null) }
    var editingFileEntry by remember { mutableStateOf<FileEntry?>(null) }
    var editingContent by remember { mutableStateOf("") }

    val multiFileUploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onUploadFiles(uris)
    }

    val zipImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportZip(uri)
    }

    LaunchedEffect(sites) {
        if (selectedSite == null && sites.isNotEmpty()) {
            onSelectSite(sites.first())
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionHeader(
                    title = "مدير الملفات المعزول",
                    subtitle = "رفع متعدد، ضغط/فك ZIP مع نسبة التقدم، محرر كود، وصلاحيات Unix"
                )
            }

            if (sites.isEmpty()) {
                item {
                    EmptyStatePanel(
                        icon = Icons.Filled.Folder,
                        title = "لا توجد مواقع لإدارة ملفاتها",
                        description = "أنشئ موقعاً أولاً من قسم المواقع لفتح مجلده المعزول وإدارة ملفاته."
                    )
                }
            } else {
                // Site Selector Row
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
                                onClick = { onSelectSite(site) },
                                label = { Text(site.name) }
                            )
                        }
                    }
                }

                // Action Toolbar
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { multiFileUploadLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.testTag("fm_upload_files_btn")
                        ) {
                            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("رفع ملفات")
                        }
                        OutlinedButton(
                            onClick = { zipImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                            modifier = Modifier.testTag("fm_import_zip_btn")
                        ) {
                            Icon(Icons.Filled.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("استيراد وفك ZIP")
                        }
                        OutlinedButton(
                            onClick = { showNewFolderDialog = true },
                            modifier = Modifier.testTag("fm_new_folder_btn")
                        ) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("مجلد")
                        }
                        OutlinedButton(
                            onClick = { showNewFileDialog = true },
                            modifier = Modifier.testTag("fm_new_file_btn")
                        ) {
                            Icon(Icons.Filled.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("ملف")
                        }
                        if (clipboardFile != null) {
                            Button(
                                onClick = onPasteClipboard,
                                colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed)
                            ) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("لصق هنا")
                            }
                        }
                    }
                }

                // Live Upload / Extract / Compress Progress Card
                if (transferProgress != null) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(transferProgress.operationLabel, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${(transferProgress.progress * 100).toInt()}%",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { transferProgress.progress },
                                    modifier = Modifier.fillMaxWidth().height(6.dp)
                                )
                                Text(
                                    text = "${transferProgress.currentFileName} (${formatBytes(transferProgress.processedBytes)} / ${formatBytes(transferProgress.totalBytes)})",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily)
                                )
                                if (transferProgress.error != null) {
                                    Text(transferProgress.error, color = CrimsonRed, style = MaterialTheme.typography.bodySmall)
                                }
                                if (transferProgress.isCompleted) {
                                    TextButton(onClick = onDismissProgress) { Text("إخفاء") }
                                }
                            }
                        }
                    }
                }

                // Current Path Bar
                item {
                    val rootPath = selectedSite?.rootFolder ?: ""
                    val relPath = currentDir?.absolutePath?.removePrefix(rootPath)?.ifBlank { "/" } ?: "/"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onNavigateUp,
                                enabled = relPath != "/",
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "المجلد الأعلى")
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${selectedSite?.name ?: ""}$relPath",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (entries.isEmpty()) {
                    item {
                        EmptyStatePanel(
                            icon = Icons.Filled.Description,
                            title = "المجلد فارغ",
                            description = "استخدم زر \"رفع ملفات\" أو \"استيراد وفك ZIP\" لإضافة ملفات المشروع."
                        )
                    }
                } else {
                    items(entries, key = { it.path }) { entry ->
                        var menuOpen by remember { mutableStateOf(false) }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDirectory) {
                                        onNavigateInto(entry.path)
                                    } else {
                                        scope.launch {
                                            val res = onLoadFileText(entry.path)
                                            res.onSuccess { text ->
                                                editingContent = text
                                                editingFileEntry = entry
                                            }
                                        }
                                    }
                                },
                            shape = MaterialTheme.shapes.small,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when {
                                        entry.isDirectory -> Icons.Filled.Folder
                                        entry.name.endsWith(".zip", true) -> Icons.Filled.Archive
                                        else -> Icons.Filled.Description
                                    },
                                    contentDescription = entry.name,
                                    tint = if (entry.isDirectory) RoyalBluePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "${if (entry.isDirectory) "مجلد" else formatBytes(entry.sizeBytes)} • ${entry.permissionsOctal} (${entry.permissionsSymbolic})",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Box {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "خيارات الملف")
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        if (!entry.isDirectory) {
                                            DropdownMenuItem(
                                                text = { Text("تحرير الكود / النص") },
                                                onClick = {
                                                    menuOpen = false
                                                    scope.launch {
                                                        onLoadFileText(entry.path).onSuccess {
                                                            editingContent = it
                                                            editingFileEntry = entry
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                        if (entry.name.endsWith(".zip", true)) {
                                            DropdownMenuItem(
                                                text = { Text("فك الضغط هنا") },
                                                onClick = { menuOpen = false; onExtractLocalZip(entry) }
                                            )
                                        } else {
                                            DropdownMenuItem(
                                                text = { Text("ضغط إلى ZIP") },
                                                onClick = { menuOpen = false; onCompressEntry(entry) }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("نسخ") },
                                            onClick = { menuOpen = false; onSetClipboard(entry, false) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("قص / نقل") },
                                            onClick = { menuOpen = false; onSetClipboard(entry, true) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("إعادة التسمية") },
                                            onClick = { menuOpen = false; renamingEntry = entry }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("صلاحيات Unix (chmod)") },
                                            onClick = { menuOpen = false; chmodEntry = entry }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("حذف", color = CrimsonRed) },
                                            onClick = { menuOpen = false; onDeleteEntry(entry) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        SingleNameDialog(
            title = "إنشاء مجلد جديد",
            label = "اسم المجلد",
            initial = "",
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { onCreateFolder(it); showNewFolderDialog = false }
        )
    }

    if (showNewFileDialog) {
        SingleNameDialog(
            title = "إنشاء ملف جديد",
            label = "اسم الملف (مثال: config.php)",
            initial = "",
            onDismiss = { showNewFileDialog = false },
            onConfirm = { onCreateFile(it); showNewFileDialog = false }
        )
    }

    renamingEntry?.let { entry ->
        SingleNameDialog(
            title = "إعادة تسمية ${entry.name}",
            label = "الاسم الجديد",
            initial = entry.name,
            onDismiss = { renamingEntry = null },
            onConfirm = { onRenameEntry(entry, it); renamingEntry = null }
        )
    }

    chmodEntry?.let { entry ->
        SingleNameDialog(
            title = "صلاحيات Unix (chmod) — ${entry.name}",
            label = "الوضع الثماني (مثال: 755 أو 644)",
            initial = entry.permissionsOctal.takeIf { it != "---" } ?: "644",
            onDismiss = { chmodEntry = null },
            onConfirm = { onUpdatePermissions(entry, it); chmodEntry = null }
        )
    }

    editingFileEntry?.let { entry ->
        Dialog(
            onDismissRequest = { editingFileEntry = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "${editingContent.lines().size} سطر • ${editingContent.length} حرف",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editingFileEntry = null }) { Text("إغلاق") }
                            Button(
                                onClick = {
                                    onSaveFileText(entry.path, editingContent)
                                    editingFileEntry = null
                                }
                            ) {
                                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("حفظ")
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editingContent,
                        onValueChange = { editingContent = it },
                        textStyle = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 13.sp),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun SingleNameDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("تأكيد") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
