package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.database.DatabaseInstanceEntity
import com.example.core.model.DbEngineType
import com.example.core.model.EngineInfo
import com.example.core.model.EngineType
import com.example.core.model.SqlQueryResult
import com.example.core.model.SqliteTableInfo
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.EngineStatusChip
import com.example.ui.components.SectionHeader
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RoyalBluePrimary
import com.example.ui.theme.StatusOkGreen

@Composable
fun DatabasesScreen(
    databases: List<DatabaseInstanceEntity>,
    engines: List<EngineInfo>,
    inspectedTables: List<SqliteTableInfo>,
    lastSqlResult: SqlQueryResult?,
    onCreateDatabase: (String, DbEngineType, String, String, String) -> Unit,
    onDeleteDatabase: (DatabaseInstanceEntity) -> Unit,
    onUpdatePrivileges: (DatabaseInstanceEntity, String, String?, String) -> Unit,
    onInspectTables: (DatabaseInstanceEntity) -> Unit,
    onRunSql: (DatabaseInstanceEntity, String) -> Unit,
    onImportSql: (DatabaseInstanceEntity, Uri) -> Unit,
    onExportSql: (DatabaseInstanceEntity, Uri) -> Unit,
    onImportPhpMyAdminZip: (Uri) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var activeDbForSql by remember { mutableStateOf<DatabaseInstanceEntity?>(null) }
    var activeDbForPrivs by remember { mutableStateOf<DatabaseInstanceEntity?>(null) }
    var pendingSqlImportDb by remember { mutableStateOf<DatabaseInstanceEntity?>(null) }
    var pendingSqlExportDb by remember { mutableStateOf<DatabaseInstanceEntity?>(null) }

    val importSqlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val db = pendingSqlImportDb
        if (uri != null && db != null) onImportSql(db, uri)
    }

    val exportSqlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/sql")) { uri ->
        val db = pendingSqlExportDb
        if (uri != null && db != null) onExportSql(db, uri)
    }

    val pmaZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportPhpMyAdminZip(uri)
    }

    val pmaEngine = engines.firstOrNull { it.type == EngineType.PHPMYADMIN }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.testTag("fab_create_db"),
                containerColor = RoyalBluePrimary,
                contentColor = PureWhite
            ) {
                Icon(Icons.Filled.Add, contentDescription = "إنشاء قاعدة بيانات")
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
                    title = "إدارة قواعد البيانات",
                    subtitle = "SQLite المدمجة و MariaDB و PostgreSQL وصلاحيات المستخدمين"
                )
            }

            // phpMyAdmin Integration Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, RoyalBluePrimary.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("واجهة phpMyAdmin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (pmaEngine != null) EngineStatusChip(status = pmaEngine.status)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = pmaEngine?.verificationDetails ?: "تتطلب استيراد أرشيف phpMyAdmin وربطه بمحرك MySQL/MariaDB.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { pmaZipLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                            modifier = Modifier.testTag("import_pma_zip_btn")
                        ) {
                            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("استيراد أرشيف phpMyAdmin (.zip)")
                        }
                    }
                }
            }

            if (databases.isEmpty()) {
                item {
                    EmptyStatePanel(
                        icon = Icons.Filled.Storage,
                        title = "لا توجد قواعد بيانات بعد",
                        description = "أنشئ قاعدة بيانات SQLite حقيقية مع مستخدم وصلاحيات، وأنشئ الجداول أو استورد ملفات .sql مباشرة.",
                        actionButton = {
                            Button(onClick = { showCreateDialog = true }) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("إنشاء قاعدة بيانات")
                            }
                        }
                    )
                }
            } else {
                items(databases, key = { it.id }) { db ->
                    val typeTitle = DbEngineType.entries.firstOrNull { it.name == db.engineType }?.titleAr ?: db.engineType
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
                                    Text(db.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        "$typeTitle • المستخدم: ${db.username} (${db.privileges})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteDatabase(db) },
                                    modifier = Modifier.testTag("delete_db_${db.id}")
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "حذف القاعدة", tint = CrimsonRed)
                                }
                            }

                            Text(
                                text = db.storageTarget,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = RoyalBluePrimary
                            )
                            Spacer(Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        onInspectTables(db)
                                        activeDbForSql = db
                                    },
                                    modifier = Modifier.testTag("open_sql_console_${db.id}")
                                ) {
                                    Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("الجداول و SQL")
                                }
                                OutlinedButton(
                                    onClick = { activeDbForPrivs = db },
                                    modifier = Modifier.testTag("db_privs_${db.id}")
                                ) {
                                    Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("الصلاحيات")
                                }
                                OutlinedButton(
                                    onClick = {
                                        pendingSqlImportDb = db
                                        importSqlLauncher.launch(arrayOf("*/*"))
                                    },
                                    modifier = Modifier.testTag("import_sql_${db.id}")
                                ) {
                                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("استيراد SQL")
                                }
                                OutlinedButton(
                                    onClick = {
                                        pendingSqlExportDb = db
                                        exportSqlLauncher.launch("${db.name}.sql")
                                    },
                                    modifier = Modifier.testTag("export_sql_${db.id}")
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("تصدير SQL")
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
        CreateDatabaseDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, type, user, pass, privs ->
                onCreateDatabase(name, type, user, pass, privs)
                showCreateDialog = false
            }
        )
    }

    activeDbForSql?.let { db ->
        SqlConsoleDialog(
            db = db,
            tables = inspectedTables,
            result = lastSqlResult,
            onRunQuery = { sql -> onRunSql(db, sql) },
            onDismiss = { activeDbForSql = null }
        )
    }

    activeDbForPrivs?.let { db ->
        UpdatePrivilegesDialog(
            db = db,
            onDismiss = { activeDbForPrivs = null },
            onSave = { user, pass, privs ->
                onUpdatePrivileges(db, user, pass, privs)
                activeDbForPrivs = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateDatabaseDialog(
    onDismiss: () -> Unit,
    onCreate: (String, DbEngineType, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var engine by remember { mutableStateOf(DbEngineType.SQLITE) }
    var username by remember { mutableStateOf("abotalal_user") }
    var password by remember { mutableStateOf("") }
    var privileges by remember { mutableStateOf("ALL") }
    var engineExpanded by remember { mutableStateOf(false) }
    var privExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إنشاء قاعدة بيانات جديدة", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم قاعدة البيانات") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("db_name_input")
                )
                ExposedDropdownMenuBox(expanded = engineExpanded, onExpandedChange = { engineExpanded = !engineExpanded }) {
                    OutlinedTextField(
                        value = engine.titleAr,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("نوع المحرك") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
                        DbEngineType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.titleAr) }, onClick = { engine = t; engineExpanded = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("اسم المستخدم") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("كلمة المرور (تُحفظ مشفرة)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(expanded = privExpanded, onExpandedChange = { privExpanded = !privExpanded }) {
                    OutlinedTextField(
                        value = privileges,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("الصلاحيات") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = privExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = privExpanded, onDismissRequest = { privExpanded = false }) {
                        listOf("ALL", "READ_WRITE", "READ_ONLY").forEach { p ->
                            DropdownMenuItem(text = { Text(p) }, onClick = { privileges = p; privExpanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name, engine, username, password, privileges) },
                modifier = Modifier.testTag("confirm_create_db_btn")
            ) { Text("إنشاء") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun SqlConsoleDialog(
    db: DatabaseInstanceEntity,
    tables: List<SqliteTableInfo>,
    result: SqlQueryResult?,
    onRunQuery: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var sqlText by remember {
        mutableStateOf("SELECT name, type FROM sqlite_master WHERE type='table';")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إدارة الجداول و SQL — ${db.name}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("الجداول الموجودة (${tables.size}):", fontWeight = FontWeight.Bold)
                if (tables.isEmpty()) {
                    Text("لا توجد جداول بعد. يمكنك إنشاء جدول عبر SQL أدناه.", style = MaterialTheme.typography.bodySmall)
                } else {
                    tables.forEach { t ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.TableChart, contentDescription = null, modifier = Modifier.size(16.dp), tint = RoyalBluePrimary)
                                    Spacer(Modifier.width(6.dp))
                                    Text("${t.name} (${t.rowCount} صف)", fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp)
                                }
                                TextButton(onClick = { sqlText = "SELECT * FROM \"${t.name}\" LIMIT 50;" }) {
                                    Text("استعراض", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            sqlText = "CREATE TABLE IF NOT EXISTS users (\n  id INTEGER PRIMARY KEY AUTOINCREMENT,\n  username TEXT NOT NULL,\n  created_at TEXT DEFAULT CURRENT_TIMESTAMP\n);"
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) { Text("قالب CREATE TABLE", fontSize = 11.sp) }
                }

                OutlinedTextField(
                    value = sqlText,
                    onValueChange = { sqlText = it },
                    label = { Text("أمر SQL") },
                    textStyle = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp),
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("sql_query_input")
                )

                Button(
                    onClick = { onRunQuery(sqlText) },
                    modifier = Modifier.fillMaxWidth().testTag("run_sql_btn")
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("تنفيذ SQL")
                }

                if (result != null) {
                    Text(
                        text = "${result.message} (${result.executionTimeMs}ms)",
                        color = if (result.isError) CrimsonRed else StatusOkGreen,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (result.columns.isNotEmpty()) {
                        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            Text(
                                text = result.columns.joinToString(" | "),
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            result.rows.take(25).forEach { r ->
                                Text(
                                    text = r.joinToString(" | "),
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdatePrivilegesDialog(
    db: DatabaseInstanceEntity,
    onDismiss: () -> Unit,
    onSave: (String, String?, String) -> Unit
) {
    var user by remember { mutableStateOf(db.username) }
    var pass by remember { mutableStateOf("") }
    var priv by remember { mutableStateOf(db.privileges) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("صلاحيات قاعدة البيانات: ${db.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("اسم المستخدم") }, singleLine = true)
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("كلمة مرور جديدة (اتركها فارغة للإبقاء)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = priv,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("مستوى الصلاحية") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("ALL", "READ_WRITE", "READ_ONLY").forEach { p ->
                            DropdownMenuItem(text = { Text(p) }, onClick = { priv = p; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(user, pass.ifBlank { null }, priv) }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
