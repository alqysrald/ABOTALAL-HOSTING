package com.example.ui.screens

import android.net.Uri
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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.database.RuntimeConfigEntity
import com.example.core.model.AndroidPackageSpec
import com.example.core.model.DownloadTaskState
import com.example.core.model.EngineInfo
import com.example.core.model.EngineStatus
import com.example.core.model.EngineType
import com.example.core.model.PackageInstallState
import com.example.core.model.PythonPackageInfo
import com.example.core.model.PythonVerificationReport
import com.example.ui.components.DownloadTaskProgressCard
import com.example.ui.components.EngineStatusChip
import com.example.ui.components.SectionHeader
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RoyalBluePrimary
import com.example.ui.theme.StatusOkGreen
import com.example.ui.theme.StatusWarning

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnginesScreen(
    engines: List<EngineInfo>,
    runtimeConfigs: List<RuntimeConfigEntity>,
    packageCatalog: List<AndroidPackageSpec>,
    downloadTaskState: DownloadTaskState?,
    isSyncingCatalog: Boolean,
    lastSyncMessage: String?,
    phpLoadedModules: List<String>,
    installedPythonPackages: List<PythonPackageInfo>,
    pythonVerificationReport: PythonVerificationReport?,
    lastPipOutput: String,
    pypiInspectionResult: PythonPackageInfo?,
    onRefreshEngines: () -> Unit,
    onSyncRepositories: () -> Unit,
    onInstallPackageSpec: (AndroidPackageSpec) -> Unit,
    onImportLocalPackageSpec: (AndroidPackageSpec, Uri) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (DownloadTaskState) -> Unit,
    onDismissDownload: () -> Unit,
    onStartEngineService: (EngineType, String) -> Unit,
    onStopEngineService: (EngineType, String) -> Unit,
    onRestartEngineService: (EngineType, String) -> Unit,
    onSavePhpConfig: (String, String, List<String>) -> Unit,
    onApplyComprehensivePhpPreset: (String) -> Unit,
    onProbePhpModules: (String) -> Unit,
    onImportPhpZip: (String, Uri) -> Unit,
    onSavePythonRequirements: (String, String) -> Unit,
    onInspectPypiPackage: (String) -> Unit,
    onPipInstallPackage: (String, String) -> Unit,
    onPipUninstallPackage: (String, String) -> Unit,
    onPipUpgradePackage: (String, String) -> Unit,
    onInstallPythonRequirementsTxt: (String) -> Unit,
    onRefreshPythonPackages: (String) -> Unit,
    onRunPythonFullVerification: (String) -> Unit,
    onSaveNodePackageJson: (String, String) -> Unit
) {
    var inspectedEngine by remember { mutableStateOf<EngineInfo?>(null) }
    var selectedPhpVersion by remember { mutableStateOf("8.4") }
    var selectedPythonVersion by remember { mutableStateOf("3.12") }
    var selectedNodeVersion by remember { mutableStateOf("20") }
    var pipPackageInput by remember { mutableStateOf("flask") }
    var pendingLocalPkg by remember { mutableStateOf<AndroidPackageSpec?>(null) }

    val phpConfigs = runtimeConfigs.filter { it.engineType == EngineType.PHP.name }
    val pythonConfigs = runtimeConfigs.filter { it.engineType == EngineType.PYTHON.name }
    val nodeConfigs = runtimeConfigs.filter { it.engineType == EngineType.NODEJS.name }

    val activePhpConfig = phpConfigs.firstOrNull { it.version == selectedPhpVersion }
    var phpIniDraft by remember(selectedPhpVersion, activePhpConfig?.configText) {
        mutableStateOf(activePhpConfig?.configText ?: "")
    }
    var phpExtensionsDraft by remember(selectedPhpVersion, activePhpConfig?.extensionsCsv) {
        mutableStateOf(
            activePhpConfig?.extensionsCsv?.split(',')?.filter { it.isNotBlank() }?.toSet()
                ?: setOf("mysqli", "pdo_mysql", "mbstring", "curl", "gd", "fileinfo", "zip", "openssl")
        )
    }

    val activePyConfig = pythonConfigs.firstOrNull { it.version == selectedPythonVersion }
    var pyReqDraft by remember(selectedPythonVersion, activePyConfig?.configText) {
        mutableStateOf(activePyConfig?.configText ?: "")
    }

    val activeNodeConfig = nodeConfigs.firstOrNull { it.version == selectedNodeVersion }
    var nodePkgDraft by remember(selectedNodeVersion, activeNodeConfig?.configText) {
        mutableStateOf(activeNodeConfig?.configText ?: "")
    }

    val phpZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportPhpZip(selectedPhpVersion, uri)
        }
    }

    val localPkgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val pkg = pendingLocalPkg
        if (uri != null && pkg != null) {
            onImportLocalPackageSpec(pkg, uri)
        }
    }

    val allPhpExtensions = listOf(
        "mysqli", "pdo_mysql", "mbstring", "curl", "gd", "fileinfo", "zip", "openssl", "pdo_sqlite", "sqlite3", "intl"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionHeader(
                title = "محرك الاستضافة الموحد",
                subtitle = "فحص حقيقي للمحركات المثبتة، التحكم بالخدمة، والتحقق الفعلي من التشغيل",
                trailing = {
                    Row {
                        IconButton(
                            onClick = onSyncRepositories,
                            enabled = !isSyncingCatalog,
                            modifier = Modifier.testTag("engines_sync_repo_btn")
                        ) {
                            if (isSyncingCatalog) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.CloudSync, contentDescription = "مزامنة المستودعات", tint = RoyalBluePrimary)
                            }
                        }
                        IconButton(
                            onClick = onRefreshEngines,
                            modifier = Modifier.testTag("engines_refresh_btn")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "إعادة فحص المحركات", tint = RoyalBluePrimary)
                        }
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

        items(engines, key = { it.type.name }) { engine ->
            val supportsServiceControl = engine.type in listOf(
                EngineType.PHP,
                EngineType.MYSQL_MARIADB,
                EngineType.POSTGRESQL,
                EngineType.PHPMYADMIN
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = engine.type.titleAr,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${engine.type.titleEn} • ABI: ${engine.architecture}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        EngineStatusChip(status = engine.status)
                    }

                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = engine.verificationDetails,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (engine.binaryPath != null) {
                        Text(
                            text = "المسار: ${engine.binaryPath}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = RoyalBluePrimary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(Modifier.size(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (engine.status == EngineStatus.NOT_INSTALLED || engine.status == EngineStatus.ERROR) {
                            val matchingSpec = packageCatalog.firstOrNull { it.engineType == engine.type }
                            Button(
                                onClick = {
                                    if (matchingSpec != null && matchingSpec.isAvailableForInstall) {
                                        onInstallPackageSpec(matchingSpec)
                                    } else {
                                        inspectedEngine = engine
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed),
                                modifier = Modifier.testTag("install_engine_${engine.type.name}")
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (matchingSpec?.isAvailableForInstall == true) "تنزيل وتثبيت" else "تثبيت / المتطلبات")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { inspectedEngine = engine },
                                modifier = Modifier.testTag("inspect_engine_${engine.type.name}")
                            ) {
                                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("التفاصيل")
                            }
                        }

                        if (supportsServiceControl && engine.status != EngineStatus.NOT_INSTALLED) {
                            if (engine.status == EngineStatus.RUNNING) {
                                OutlinedButton(
                                    onClick = { onStopEngineService(engine.type, selectedPhpVersion) }
                                ) {
                                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp), tint = CrimsonRed)
                                    Spacer(Modifier.width(4.dp))
                                    Text("إيقاف")
                                }
                            } else {
                                Button(
                                    onClick = { onStartEngineService(engine.type, selectedPhpVersion) }
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("تشغيل")
                                }
                            }
                            OutlinedButton(
                                onClick = { onRestartEngineService(engine.type, selectedPhpVersion) }
                            ) {
                                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("إعادة تشغيل")
                            }
                        }
                    }
                }
            }
        }

        // Unified Package Manager Section
        item {
            Spacer(Modifier.size(6.dp))
            SectionHeader(
                title = "مدير الحزم الموحّد (Android Bionic)",
                subtitle = lastSyncMessage ?: "يعرض الإصدارات المتاحة فعلياً لمعمارية الجهاز فقط مع الحجم والتبعيات وحالة التحقق"
            )
        }

        items(packageCatalog, key = { it.id }) { spec ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${spec.programName} — v${spec.version}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${spec.repositoryName} • ABI: ${spec.abi} • Android ${spec.minAndroidApi}+ • الحجم: ${spec.approxSizeLabel}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = when (spec.installState) {
                                PackageInstallState.VERIFIED -> StatusOkGreen.copy(alpha = 0.14f)
                                PackageInstallState.FAILED -> CrimsonRed.copy(alpha = 0.14f)
                                else -> RoyalBluePrimary.copy(alpha = 0.12f)
                            }
                        ) {
                            Text(
                                text = spec.installState.labelAr,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = when (spec.installState) {
                                    PackageInstallState.VERIFIED -> StatusOkGreen
                                    PackageInstallState.FAILED -> CrimsonRed
                                    else -> RoyalBluePrimary
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (spec.dependencies.isNotEmpty()) {
                        Text(
                            text = "التبعيات: ${spec.dependencies.take(6).joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = spec.verificationSummary.ifBlank { spec.androidCompatibilityNote },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onInstallPackageSpec(spec) },
                            enabled = spec.isAvailableForInstall,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (spec.isAvailableForInstall) "تنزيل وتثبيت" else "يتطلب مزامنة")
                        }
                        OutlinedButton(
                            onClick = {
                                pendingLocalPkg = spec
                                localPkgLauncher.launch(arrayOf("*/*"))
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("استيراد محلي (.deb/.zip)")
                        }
                    }
                }
            }
        }

        // PHP Multi-Version & Comprehensive Project Compatibility Manager
        item {
            Spacer(Modifier.size(6.dp))
            SectionHeader(
                title = "إدارة PHP متعددة الإصدارات والإضافات",
                subtitle = "إدارة php.ini وبيئات PHP المستقلة لكل موقع وفحص الإضافات المحملة فعلياً"
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, RoyalBluePrimary.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("8.4", "8.3", "8.2", "8.1").forEach { ver ->
                            FilterChip(
                                selected = selectedPhpVersion == ver,
                                onClick = { selectedPhpVersion = ver },
                                label = { Text("PHP $ver") }
                            )
                        }
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { onApplyComprehensivePhpPreset(selectedPhpVersion) },
                            modifier = Modifier.testTag("apply_social_preset_btn")
                        ) {
                            Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("تطبيق إعدادات التوافق الشاملة للمشاريع")
                        }
                        OutlinedButton(
                            onClick = { onProbePhpModules(selectedPhpVersion) },
                            modifier = Modifier.testTag("probe_php_modules_btn")
                        ) {
                            Icon(Icons.Filled.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("فحص الإضافات المحملة (php -m)")
                        }
                    }

                    if (phpLoadedModules.isNotEmpty()) {
                        Text(
                            text = "الإضافات المحملة فعلياً (${phpLoadedModules.size}): ${phpLoadedModules.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = StatusOkGreen
                        )
                    }

                    Text("الإضافات المفعلة في الإعدادات (Extensions):", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        allPhpExtensions.forEach { ext ->
                            val enabled = phpExtensionsDraft.contains(ext)
                            FilterChip(
                                selected = enabled,
                                onClick = {
                                    val updated = if (enabled) phpExtensionsDraft - ext else phpExtensionsDraft + ext
                                    phpExtensionsDraft = updated
                                    val baseLines = phpIniDraft.lines().filterNot { it.trim().startsWith("extension=") }
                                    val extLines = updated.sorted().map { "extension=$it.so" }
                                    phpIniDraft = (baseLines + extLines).joinToString("\n")
                                },
                                label = { Text(ext, fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = phpIniDraft,
                        onValueChange = { phpIniDraft = it },
                        label = { Text("محتوى php.ini (PHP $selectedPhpVersion)") },
                        textStyle = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp),
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth().testTag("php_ini_editor")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSavePhpConfig(selectedPhpVersion, phpIniDraft, phpExtensionsDraft.toList()) },
                            modifier = Modifier.testTag("save_php_ini_btn")
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("حفظ php.ini")
                        }
                        OutlinedButton(
                            onClick = { phpZipLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.testTag("import_php_zip_btn")
                        ) {
                            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("استيراد حزمة PHP")
                        }
                    }
                }
            }
        }

        // Node.js & Socket.io Manager
        item {
            SectionHeader(
                title = "إدارة Node.js و Socket.io (الوقت الحقيقي)",
                subtitle = "تجهيز خوادم الدردشة والإشعارات الفورية والتطبيقات المعتمدة على package.json"
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("22", "20", "18").forEach { ver ->
                            FilterChip(
                                selected = selectedNodeVersion == ver,
                                onClick = { selectedNodeVersion = ver },
                                label = { Text("Node.js $ver LTS") }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = nodePkgDraft,
                        onValueChange = { nodePkgDraft = it },
                        label = { Text("package.json (Node.js $selectedNodeVersion)") },
                        textStyle = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp),
                        minLines = 5,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onSaveNodePackageJson(selectedNodeVersion, nodePkgDraft) },
                        modifier = Modifier.testTag("save_node_pkg_btn")
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("حفظ package.json")
                    }
                }
            }
        }

        // Python Manager (Core Mandatory Rebuild: ARM64, pip, PyPI compatibility check, venv, 7-step test suite)
        item {
            Spacer(Modifier.size(6.dp))
            SectionHeader(
                title = "إدارة Python و pip والمكتبات (ARM64 Android)",
                subtitle = "تثبيت وترقية وحذف الحزم وفحص توافق PyPI واختبار التشغيل السباعي الإلزامي"
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, RoyalBluePrimary.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("3.13", "3.12", "3.11", "3.10", "3.9").forEach { ver ->
                            FilterChip(
                                selected = selectedPythonVersion == ver,
                                onClick = {
                                    selectedPythonVersion = ver
                                    onRefreshPythonPackages(ver)
                                },
                                label = { Text("Python $ver") }
                            )
                        }
                    }

                    // PyPI Compatibility & Real pip Install Controls
                    OutlinedTextField(
                        value = pipPackageInput,
                        onValueChange = { pipPackageInput = it },
                        label = { Text("اسم مكتبة PyPI / pip (مثال: flask أو fastapi)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("pip_package_input")
                    )

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { onInspectPypiPackage(pipPackageInput) },
                            modifier = Modifier.testTag("inspect_pypi_btn")
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("فحص توافق PyPI")
                        }
                        Button(
                            onClick = { onPipInstallPackage(selectedPythonVersion, pipPackageInput) },
                            modifier = Modifier.testTag("pip_install_btn")
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("pip install")
                        }
                        OutlinedButton(
                            onClick = { onInstallPythonRequirementsTxt(selectedPythonVersion) },
                            modifier = Modifier.testTag("pip_install_reqs_btn")
                        ) {
                            Text("تثبيت requirements.txt")
                        }
                        OutlinedButton(
                            onClick = { onRefreshPythonPackages(selectedPythonVersion) }
                        ) {
                            Text("تحديث القائمة")
                        }
                    }

                    pypiInspectionResult?.let { info ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = (if (info.isPurePythonWheel) StatusOkGreen else StatusWarning).copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "${info.name} ${info.version} (${if (info.isPurePythonWheel) "Pure Python / Android متوافق" else "يتطلب ترجمة C/C++"})",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (info.isPurePythonWheel) StatusOkGreen else CrimsonRed
                                )
                                Text(info.compatibilityNote, style = MaterialTheme.typography.labelSmall)
                                if (info.summary.isNotBlank()) {
                                    Text(info.summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    if (installedPythonPackages.isNotEmpty()) {
                        Text(
                            text = "المكتبات المثبتة (${installedPythonPackages.size}):",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        installedPythonPackages.take(20).forEach { pkg ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${pkg.name} == ${pkg.version}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { onPipUpgradePackage(selectedPythonVersion, pkg.name) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Filled.Upgrade, contentDescription = "ترقية ${pkg.name}", tint = RoyalBluePrimary, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = { onPipUninstallPackage(selectedPythonVersion, pkg.name) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "حذف ${pkg.name}", tint = CrimsonRed, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }

                    if (lastPipOutput.isNotBlank()) {
                        Text(
                            text = lastPipOutput.takeLast(1200),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Mandatory 7-step Real Python Verification Suite
                    Button(
                        onClick = { onRunPythonFullVerification(selectedPythonVersion) },
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalBluePrimary),
                        modifier = Modifier.fillMaxWidth().testTag("run_python_7step_test_btn")
                    ) {
                        Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("تشغيل اختبار Python الكامل (7 خطوات تحقق)")
                    }

                    pythonVerificationReport?.let { report ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "تقرير فحص Python ${report.pythonVersion} (${report.abi}): ${report.steps.count { it.passed }}/7",
                                    fontWeight = FontWeight.Bold,
                                    color = if (report.allPassed) StatusOkGreen else CrimsonRed
                                )
                                report.steps.forEach { step ->
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(
                                            imageVector = if (step.passed) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                            contentDescription = null,
                                            tint = if (step.passed) StatusOkGreen else CrimsonRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Column {
                                            Text("${step.stepNumber}. ${step.title}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                            Text(step.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Text("إضافة قوالب أطر العمل:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Flask" to "flask>=3.0.0\ngunicorn>=22.0.0",
                            "Django" to "django>=5.0\ngunicorn>=22.0.0",
                            "FastAPI" to "fastapi>=0.115.0\nuvicorn>=0.30.0"
                        ).forEach { (fw, deps) ->
                            OutlinedButton(
                                onClick = {
                                    val lines = pyReqDraft.lines().toMutableList()
                                    deps.lines().forEach { dep ->
                                        if (lines.none { it.startsWith(dep.substringBefore(">")) }) lines.add(dep)
                                    }
                                    pyReqDraft = lines.joinToString("\n").trim()
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(fw, fontSize = 12.sp)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = pyReqDraft,
                        onValueChange = { pyReqDraft = it },
                        label = { Text("requirements.txt (Python $selectedPythonVersion)") },
                        textStyle = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp),
                        minLines = 5,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth().testTag("python_req_editor")
                    )

                    Button(
                        onClick = { onSavePythonRequirements(selectedPythonVersion, pyReqDraft) },
                        modifier = Modifier.testTag("save_python_req_btn")
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("حفظ requirements.txt")
                    }
                }
            }
        }
    }

    inspectedEngine?.let { engine ->
        AlertDialog(
            onDismissRequest = { inspectedEngine = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Build, contentDescription = null, tint = RoyalBluePrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(engine.type.titleAr)
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EngineStatusChip(status = engine.status)
                    Text("المعمارية المطلوبة: ${engine.architecture}", fontWeight = FontWeight.Bold)
                    Text("المنفذ الافتراضي: ${if (engine.port == 0) "مدمج (بدون منفذ TCP)" else engine.port}")
                    Text("نتيجة الفحص الفعلي:", fontWeight = FontWeight.Bold)
                    Text(engine.verificationDetails, style = MaterialTheme.typography.bodySmall)
                    Text("متطلبات التشغيل والتكامل:", fontWeight = FontWeight.Bold)
                    Text(engine.requirementNotice, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onRefreshEngines()
                    inspectedEngine = null
                }) { Text("إعادة الفحص") }
            },
            dismissButton = {
                TextButton(onClick = { inspectedEngine = null }) { Text("إغلاق") }
            }
        )
    }
}
