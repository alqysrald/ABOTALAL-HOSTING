package com.example.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.compat.DeviceCompatibilityProfile
import com.example.core.compat.PermissionHealthSnapshot
import com.example.core.database.AppLogEntity
import com.example.core.database.SecuritySettingsEntity
import com.example.ui.components.SectionHeader
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RoyalBluePrimary
import com.example.ui.theme.StatusOkGreen
import com.example.ui.theme.StatusWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecurityLogsScreen(
    settings: SecuritySettingsEntity?,
    logs: List<AppLogEntity>,
    deviceProfile: DeviceCompatibilityProfile? = null,
    permissionHealth: PermissionHealthSnapshot? = null,
    legacyStoragePermissions: Array<String> = emptyArray(),
    onRefreshPermissions: () -> Unit = {},
    onPersistSafTree: (Uri) -> Unit = {},
    onOpenAllFilesSettings: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onSavePin: (Boolean, String?) -> Unit,
    onUpdateToggles: (Boolean, Boolean, Boolean) -> Unit,
    onClearLogs: () -> Unit
) {
    val current = settings ?: SecuritySettingsEntity()
    var pinEnabled by remember(current.adminPinEnabled) { mutableStateOf(current.adminPinEnabled) }
    var newPin by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }
    var showAllFilesRationaleDialog by remember { mutableStateOf(false) }

    val safTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            onPersistSafTree(uri)
        }
    }

    val legacyPermsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        onRefreshPermissions()
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onRefreshPermissions()
    }

    val filteredLogs = remember(logs, selectedFilter) {
        if (selectedFilter == "ALL") logs else logs.filter { it.level == selectedFilter }
    }
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionHeader(
                title = "الأمان وحماية لوحة الإدارة",
                subtitle = "عزل المشاريع، تشفير الرموز، ومنع فتح المنافذ غير المصرح به"
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = RoyalBluePrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("سياسات الحماية النشطة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("قصر الاستماع الافتراضي على 127.0.0.1", style = MaterialTheme.typography.bodyMedium)
                            Text("عدم فتح منافذ خارجية تلقائياً", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = current.localhostOnlyByDefault,
                            onCheckedChange = {
                                onUpdateToggles(it, current.requireApprovalBeforeShare, current.logAllRequests)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("طلب موافقة المستخدم قبل المشاركة عبر الإنترنت", style = MaterialTheme.typography.bodyMedium)
                            Text("حماية إجبارية قبل ربط أي نفق خارجي", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = current.requireApprovalBeforeShare,
                            onCheckedChange = {
                                onUpdateToggles(current.localhostOnlyByDefault, it, current.logAllRequests)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("حماية واجهة الإدارة برمز PIN", style = MaterialTheme.typography.bodyMedium)
                            Text("يُحفظ الرمز مشفراً بخوارزمية SHA-256 + Salt", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = pinEnabled,
                            onCheckedChange = {
                                pinEnabled = it
                                if (!it) onSavePin(false, null)
                            }
                        )
                    }

                    if (pinEnabled) {
                        OutlinedTextField(
                            value = newPin,
                            onValueChange = { if (it.length <= 8) newPin = it.filter { ch -> ch.isDigit() } },
                            label = { Text("تعيين رمز PIN (4-8 أرقام)") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("admin_pin_input")
                        )
                        Button(
                            onClick = {
                                if (newPin.length >= 4) {
                                    onSavePin(true, newPin)
                                    newPin = ""
                                }
                            },
                            modifier = Modifier.testTag("save_admin_pin_btn")
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("تفعيل وحفظ رمز PIN")
                        }
                    }
                }
            }
        }

        if (deviceProfile != null && permissionHealth != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("compat_permissions_center_card"),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, RoyalBluePrimary.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = RoyalBluePrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "مركز التوافق والصلاحيات (Android 6–17 / W^X / SAF)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "النظام: Android ${deviceProfile.androidRelease} (API ${deviceProfile.apiLevel}) • targetSdk=${deviceProfile.targetSdk} • ABI: ${deviceProfile.primaryAbi}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = RoyalBluePrimary
                        )
                        Text(
                            text = "الذاكرة: ${deviceProfile.availRamMb}/${deviceProfile.totalRamMb} MB • المساحة الحرة: ${deviceProfile.freeStorageBytes / (1024 * 1024)} MB",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        PermissionRowItem(
                            label = if (deviceProfile.canExecuteInAppData) {
                                "سياسة W^X و SELinux: تنفيذ من بيانات التطبيق مسموح (API < 29)"
                            } else {
                                "سياسة W^X و SELinux: صارمة — التشغيل الأصلي عبر APK nativeLibraryDir فقط"
                            },
                            granted = true
                        )
                        PermissionRowItem(
                            label = "استراتيجية التخزين: ${permissionHealth.storageStrategy.labelAr}",
                            granted = permissionHealth.isolatedSitesWritable
                        )
                        PermissionRowItem(
                            label = "الخدمة الأمامية: ${deviceProfile.fgsStrategy.labelAr}",
                            granted = true
                        )
                        PermissionRowItem(
                            label = "مجلدات SAF المرتبطة (SD / USB / OTG): ${permissionHealth.persistedSafTreesCount}",
                            granted = permissionHealth.persistedSafTreesCount > 0
                        )
                        if (permissionHealth.persistedSafLabels.isNotEmpty()) {
                            Text(
                                text = "المجلدات المصرح بها: ${permissionHealth.persistedSafLabels.joinToString(" • ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PermissionRowItem(
                            label = if (permissionHealth.notificationsGranted) "الإشعارات مفعّلة لخدمة الخادم الأمامية" else "إذن الإشعارات غير ممنوح (موصى به لـ Android 13+)",
                            granted = permissionHealth.notificationsGranted
                        )
                        PermissionRowItem(
                            label = if (permissionHealth.vpnConsentGranted) "موافقة VPN DNS المحلي جاهزة" else "موافقة VPN DNS تُطلب عند تفعيل النطاقات المحلية",
                            granted = permissionHealth.vpnConsentGranted
                        )

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (permissionHealth.requiresLegacyRuntimeStorage && legacyStoragePermissions.isNotEmpty()) {
                                Button(
                                    onClick = { legacyPermsLauncher.launch(legacyStoragePermissions) },
                                    modifier = Modifier.testTag("legacy_storage_perm_btn")
                                ) {
                                    Icon(Icons.Filled.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("طلب إذن التخزين (API 23–29)")
                                }
                            }
                            OutlinedButton(
                                onClick = { safTreeLauncher.launch(null) },
                                modifier = Modifier.testTag("saf_tree_perm_btn")
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("ربط مجلد (SAF / SD / USB OTG)")
                            }
                            if (permissionHealth.supportsManageAllFiles) {
                                OutlinedButton(
                                    onClick = { showAllFilesRationaleDialog = true },
                                    modifier = Modifier.testTag("manage_all_files_btn")
                                ) {
                                    Icon(Icons.Filled.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (permissionHealth.allFilesAccessGranted) "الوصول الشامل مفعل ✓" else "إدارة جميع الملفات (اختياري)")
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissionHealth.notificationsGranted) {
                                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        onOpenNotificationSettings()
                                    }
                                },
                                modifier = Modifier.testTag("notif_perm_btn")
                            ) {
                                Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("إعدادات الإشعارات")
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "سجل الأخطاء والعمليات الأمنية",
                subtitle = "إجمالي السجلات: ${filteredLogs.size}",
                trailing = {
                    IconButton(
                        onClick = onClearLogs,
                        modifier = Modifier.testTag("clear_logs_btn")
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "مسح السجل", tint = CrimsonRed)
                    }
                }
            )
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "ALL" to "الكل",
                    "ERROR" to "الأخطاء",
                    "SECURITY" to "الأمان",
                    "WARN" to "تحذيرات",
                    "INFO" to "عمليات"
                ).forEach { (code, label) ->
                    FilterChip(
                        selected = selectedFilter == code,
                        onClick = { selectedFilter = code },
                        label = { Text(label) }
                    )
                }
            }
        }

        if (filteredLogs.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        text = "لا توجد سجلات مطابقة للفلتر المحدد.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(filteredLogs, key = { it.id }) { entry ->
                val badgeColor = when (entry.level) {
                    "ERROR" -> CrimsonRed
                    "SECURITY" -> RoyalBluePrimary
                    "WARN" -> StatusWarning
                    else -> StatusOkGreen
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "[${entry.level}] ${entry.source}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = badgeColor,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = dateFmt.format(Date(entry.timestamp)),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(entry.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showAllFilesRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showAllFilesRationaleDialog = false },
            title = { Text("توضيح إذن الوصول لجميع الملفات (Android 11+)") },
            text = {
                Text(
                    "يستخدم التطبيق إطار عمل Storage Access Framework (SAF) كوسيلة أساسية آمنة دون حاجة لصلاحيات واسعة.\n\n" +
                        "إذا رغبت باستعراض مجلدات نظامية كاملة خارج نطاق SAF في Android 11 والإصدارات الأحدث، يمكنك منح إذن «الوصول لجميع الملفات» اختيارياً من إعدادات النظام.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(onClick = {
                    showAllFilesRationaleDialog = false
                    onOpenAllFilesSettings()
                }) { Text("فتح إعدادات النظام") }
            },
            dismissButton = {
                TextButton(onClick = { showAllFilesRationaleDialog = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun PermissionRowItem(label: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (granted) StatusOkGreen else StatusWarning,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
