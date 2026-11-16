package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.example.core.database.ProjectInstallEntity
import com.example.core.database.SiteEntity
import com.example.core.model.AndroidPackageSpec
import com.example.core.model.CheckSeverity
import com.example.core.model.DownloadTaskState
import com.example.core.model.EngineType
import com.example.core.model.ProjectAnalysisReport
import com.example.ui.components.DownloadTaskProgressCard
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.SectionHeader
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RoyalBluePrimary
import com.example.ui.theme.StatusOkGreen
import com.example.ui.theme.StatusWarning

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectInstallerScreen(
    sites: List<SiteEntity>,
    analysisReport: ProjectAnalysisReport?,
    installHistory: List<ProjectInstallEntity>,
    packageCatalog: List<AndroidPackageSpec>,
    downloadTaskState: DownloadTaskState?,
    isSyncingCatalog: Boolean,
    isOnline: Boolean,
    onAnalyzeSite: (SiteEntity) -> Unit,
    onImportZipAndAnalyze: (SiteEntity, Uri) -> Unit,
    onImportSafFolderAndAnalyze: (SiteEntity, Uri) -> Unit = { _, _ -> },
    onCreateBackup: (SiteEntity) -> Unit,
    onVerifyInstallation: (SiteEntity) -> Unit,
    onRestoreSafePermissions: (SiteEntity) -> Unit,
    onSyncRepositories: () -> Unit,
    onInstallMissingRequirement: (EngineType) -> Unit,
    onInstallPackageSpec: (AndroidPackageSpec, String?, String?) -> Unit,
    onImportLocalPackageSpec: (AndroidPackageSpec, Uri) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (DownloadTaskState) -> Unit,
    onDismissDownload: () -> Unit
) {
    var selectedSite by remember { mutableStateOf<SiteEntity?>(sites.firstOrNull()) }
    LaunchedEffect(sites) {
        if (selectedSite == null && sites.isNotEmpty()) selectedSite = sites.first()
    }

    var customUrlPkgDialog by remember { mutableStateOf<AndroidPackageSpec?>(null) }
    var pendingLocalPkg by remember { mutableStateOf<AndroidPackageSpec?>(null) }

    val projectZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val site = selectedSite
        if (uri != null && site != null) {
            onImportZipAndAnalyze(site, uri)
        }
    }

    val safFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val site = selectedSite
        if (uri != null && site != null) {
            onImportSafFolderAndAnalyze(site, uri)
        }
    }

    val pkgZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pkg = pendingLocalPkg
        if (uri != null && pkg != null) {
            onImportLocalPackageSpec(pkg, uri)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionHeader(
                title = "مثبّت المشاريع الذكي (Project Installer)",
                subtitle = "تحليل عام للمتطلبات (PHP / Python / Node.js / قواعد البيانات) والتحقق بعد التثبيت"
            )
        }

        if (sites.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Filled.FolderZip,
                    title = "أنشئ موقعاً أولاً لاستيراد المشروع",
                    description = "أنشئ موقعاً جديداً من تبويب المواقع ثم اختره هنا لرفع ملفات ZIP أو تحليل مجلده."
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
                            label = { Text(site.name) }
                        )
                    }
                }
            }

            item {
                val site = selectedSite!!
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, RoyalBluePrimary.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("المشروع المحدد: ${site.name}", fontWeight = FontWeight.Bold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { projectZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                                modifier = Modifier.testTag("installer_upload_zip_btn")
                            ) {
                                Icon(Icons.Filled.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("رفع ZIP وتحليل")
                            }
                            OutlinedButton(
                                onClick = { safFolderLauncher.launch(null) },
                                modifier = Modifier.testTag("installer_saf_folder_btn")
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("استيراد مجلد (SAF / SD / USB)")
                            }
                            OutlinedButton(
                                onClick = { onAnalyzeSite(site) },
                                modifier = Modifier.testTag("installer_analyze_existing_btn")
                            ) {
                                Icon(Icons.Filled.Analytics, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("تحليل المجلد الحالي")
                            }
                            OutlinedButton(
                                onClick = { onCreateBackup(site) },
                                modifier = Modifier.testTag("installer_backup_btn")
                            ) {
                                Icon(Icons.Filled.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("نسخة احتياطية")
                            }
                            OutlinedButton(
                                onClick = { onRestoreSafePermissions(site) },
                                modifier = Modifier.testTag("installer_safe_perms_btn")
                            ) {
                                Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("صلاحيات آمنة")
                            }
                        }
                    }
                }
            }

            // Generic Compatibility Report BEFORE Installation
            if (analysisReport != null) {
                item {
                    val site = selectedSite!!
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(
                            1.5.dp,
                            if (analysisReport.isFullyCompatible) StatusOkGreen else StatusWarning
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("تقرير التوافق قبل التثبيت", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = (if (analysisReport.isFullyCompatible) StatusOkGreen else CrimsonRed).copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = if (analysisReport.isFullyCompatible) "متوافق وجاهز" else "متطلبات ناقصة",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (analysisReport.isFullyCompatible) StatusOkGreen else CrimsonRed,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Text("نوع المشروع المكتشف: ${analysisReport.detectedScriptOrFramework}", fontWeight = FontWeight.SemiBold)
                            Text("اللغة الأساسية: ${analysisReport.primaryLanguage}")

                            val manifests = buildList {
                                if (analysisReport.hasComposerJson) add("composer.json")
                                if (analysisReport.hasPackageJson) add("package.json")
                                if (analysisReport.hasRequirementsTxt) add("requirements.txt")
                                if (analysisReport.hasHtaccess) add(".htaccess (${analysisReport.rewriteRulesCount} قاعدة)")
                            }
                            if (manifests.isNotEmpty()) {
                                Text(
                                    "ملفات التوصيف: ${manifests.joinToString(" • ")}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily)
                                )
                            }
                            if (analysisReport.detectedConfigFiles.isNotEmpty()) {
                                Text("ملفات الإعدادات: ${analysisReport.detectedConfigFiles.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (analysisReport.detectedDatabases.isNotEmpty()) {
                                Text("قواعد البيانات المطلوبة: ${analysisReport.detectedDatabases.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (analysisReport.requiredPhpExtensions.isNotEmpty()) {
                                Text("إضافات PHP: ${analysisReport.requiredPhpExtensions.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (analysisReport.detectedPythonPackages.isNotEmpty()) {
                                Text("مكتبات Python: ${analysisReport.detectedPythonPackages.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (analysisReport.detectedNodePackages.isNotEmpty()) {
                                Text("حزم Node.js: ${analysisReport.detectedNodePackages.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (analysisReport.detectedUploadDirs.isNotEmpty()) {
                                Text("مجلدات الرفع: ${analysisReport.detectedUploadDirs.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (analysisReport.requiresSocketIo) {
                                Text("خدمة إضافية: Node.js + Socket.io للوقت الحقيقي", style = MaterialTheme.typography.bodySmall, color = RoyalBluePrimary)
                            }

                            Spacer(Modifier.height(4.dp))
                            analysisReport.checks.forEach { chk ->
                                val (icon, color) = when (chk.severity) {
                                    CheckSeverity.OK -> Icons.Filled.CheckCircle to StatusOkGreen
                                    CheckSeverity.WARNING -> Icons.Filled.Warning to StatusWarning
                                    CheckSeverity.MISSING -> Icons.Filled.Error to CrimsonRed
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(chk.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                            Text(chk.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (chk.missingEngineType != null) {
                                        OutlinedButton(
                                            onClick = { onInstallMissingRequirement(chk.missingEngineType) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.padding(start = 26.dp)
                                        ) {
                                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("تثبيت ${chk.missingEngineType.titleAr}")
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = { onVerifyInstallation(site) },
                                modifier = Modifier.fillMaxWidth().testTag("run_post_install_test_btn")
                            ) {
                                Icon(Icons.Filled.Verified, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("تنفيذ اختبارات التشغيل واعتماد التثبيت")
                            }
                        }
                    }
                }
            }
        }

        // Automatic Requirements Installer
        item {
            SectionHeader(
                title = "مثبّت المتطلبات التلقائي (Verified Installer)",
                subtitle = if (isOnline) "متصل — مزامنة المستودع الرسمي وفحص SHA-256" else "وضع عدم الاتصال — المواقع المحلية تعمل بدون إنترنت",
                trailing = {
                    OutlinedButton(
                        onClick = onSyncRepositories,
                        enabled = !isSyncingCatalog,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("installer_sync_repos_btn")
                    ) {
                        if (isSyncingCatalog) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("مزامنة")
                    }
                }
            )
        }

        if (downloadTaskState != null) {
            item {
                DownloadTaskProgressCard(
                    task = downloadTaskState,
                    onCancel = onCancelDownload,
                    onRetry = onRetryDownload,
                    onDismiss = onDismissDownload
                )
            }
        }

        items(packageCatalog, key = { it.id }) { spec ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${spec.programName} (${spec.engineType.titleAr})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(spec.approxSizeLabel, style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily))
                    }
                    Text(
                        text = "الإصدار: ${spec.version} • ABI: ${spec.abi} • الحالة: ${spec.installState.labelAr}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (spec.verificationSummary.isNotBlank()) {
                        Text(spec.verificationSummary, style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (spec.isAvailableForInstall) {
                                    onInstallPackageSpec(spec, null, null)
                                } else {
                                    customUrlPkgDialog = spec
                                }
                            },
                            enabled = isOnline,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (spec.isAvailableForInstall) "تنزيل وتثبيت تلقائي" else "رابط HTTPS مخصص")
                        }
                        OutlinedButton(
                            onClick = {
                                pendingLocalPkg = spec
                                pkgZipLauncher.launch(arrayOf("*/*"))
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("استيراد محلي")
                        }
                    }
                }
            }
        }

        if (installHistory.isNotEmpty()) {
            item {
                SectionHeader(title = "سجل حالات تثبيت المشاريع")
            }
            items(installHistory, key = { it.id }) { rec ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${rec.projectName} — ${rec.detectedScript}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = rec.installStatus,
                                color = if (rec.installStatus == "VERIFIED") StatusOkGreen else CrimsonRed,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Text(rec.verificationDetails, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    customUrlPkgDialog?.let { pkg ->
        var url by remember { mutableStateOf(pkg.officialUrl) }
        var sha256 by remember { mutableStateOf(pkg.sha256) }
        AlertDialog(
            onDismissRequest = { customUrlPkgDialog = null },
            title = { Text("تنزيل وتحقق من سلامة ${pkg.engineType.titleAr}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أدخل رابط HTTPS لحزمة متوافقة مع ${pkg.abi} وبصمة SHA-256 (أو اضغط مزامنة لجلب المستودع الرسمي تلقائياً):", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("رابط الحزمة (https://...)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = sha256,
                        onValueChange = { sha256 = it },
                        label = { Text("بصمة SHA-256 (64 حرفاً)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onInstallPackageSpec(pkg, url.ifBlank { null }, sha256.ifBlank { null })
                    customUrlPkgDialog = null
                }) { Text("تنزيل وفحص") }
            },
            dismissButton = {
                TextButton(onClick = { customUrlPkgDialog = null }) { Text("إلغاء") }
            }
        )
    }
}
