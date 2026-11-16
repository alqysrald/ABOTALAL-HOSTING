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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.example.core.model.EngineInfo
import com.example.core.model.EngineStatus
import com.example.core.model.EngineType
import com.example.ui.components.EngineStatusChip
import com.example.ui.components.SectionHeader
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RoyalBluePrimary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnginesScreen(
    engines: List<EngineInfo>,
    runtimeConfigs: List<RuntimeConfigEntity>,
    onRefreshEngines: () -> Unit,
    onSavePhpConfig: (String, String, List<String>) -> Unit,
    onApplySocialScriptsPreset: (String) -> Unit,
    onImportPhpZip: (String, Uri) -> Unit,
    onSavePythonRequirements: (String, String) -> Unit,
    onSaveNodePackageJson: (String, String) -> Unit
) {
    var inspectedEngine by remember { mutableStateOf<EngineInfo?>(null) }
    var selectedPhpVersion by remember { mutableStateOf("8.4") }
    var selectedPythonVersion by remember { mutableStateOf("3.12") }
    var selectedNodeVersion by remember { mutableStateOf("20") }

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
                subtitle = "فحص حقيقي للمحركات المثبتة ومتطلبات التشغيل",
                trailing = {
                    IconButton(
                        onClick = onRefreshEngines,
                        modifier = Modifier.testTag("engines_refresh_btn")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "إعادة فحص المحركات", tint = RoyalBluePrimary)
                    }
                }
            )
        }

        items(engines, key = { it.type.name }) { engine ->
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (engine.status == EngineStatus.NOT_INSTALLED || engine.status == EngineStatus.ERROR) {
                            Button(
                                onClick = { inspectedEngine = engine },
                                colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed),
                                modifier = Modifier.testTag("install_engine_${engine.type.name}")
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("تثبيت / المتطلبات")
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
                    }
                }
            }
        }

        // PHP Multi-Version & Social Scripts (Sngine/WoWonder) Manager
        item {
            Spacer(Modifier.size(6.dp))
            SectionHeader(
                title = "إدارة PHP 8.4 ودعم السكريبتات الاجتماعية",
                subtitle = "إضافات Sngine و WoWonder وملف php.ini و mail() و allow_url_fopen"
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

                    OutlinedButton(
                        onClick = { onApplySocialScriptsPreset(selectedPhpVersion) },
                        modifier = Modifier.testTag("apply_social_preset_btn")
                    ) {
                        Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("تطبيق إعدادات Sngine / WoWonder التلقائية")
                    }

                    Text("الإضافات المفعلة (Extensions):", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
                            onClick = { phpZipLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
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
                subtitle = "تجهيز خوادم الدردشة والإشعارات الفورية لـ Sngine و WoWonder"
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

        // Python Multi-Version & Framework Manager
        item {
            Spacer(Modifier.size(6.dp))
            SectionHeader(
                title = "إدارة Python والمكتبات (pip / Frameworks)",
                subtitle = "تهيئة Flask و Django و FastAPI وملفات requirements.txt"
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
                        listOf("3.12", "3.11", "3.10").forEach { ver ->
                            FilterChip(
                                selected = selectedPythonVersion == ver,
                                onClick = { selectedPythonVersion = ver },
                                label = { Text("Python $ver") }
                            )
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
