package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.database.DatabaseInstanceEntity
import com.example.core.database.SiteEntity
import com.example.core.model.EngineInfo
import com.example.core.model.SystemMetrics
import com.example.ui.components.BrandMetricCard
import com.example.ui.components.EngineStatusChip
import com.example.ui.components.SectionHeader
import com.example.ui.components.formatBytes
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.DeepNavy
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RoyalBluePrimary
import com.example.ui.theme.StatusOkGreen
import java.util.Locale

@Composable
fun DashboardScreen(
    metrics: SystemMetrics,
    sites: List<SiteEntity>,
    runningSiteIds: Set<String>,
    databases: List<DatabaseInstanceEntity>,
    engines: List<EngineInfo>,
    onRefreshEngines: () -> Unit,
    onNavigateToSites: () -> Unit,
    onNavigateToEngines: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Server Status Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_hosting_banner),
                        contentDescription = stringResource(R.string.cd_banner),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        DeepNavy.copy(alpha = 0.94f),
                                        RoyalBluePrimary.copy(alpha = 0.84f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.app_brand_en),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = JetBrainsMonoFontFamily
                                    ),
                                    color = PureWhite.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = PureWhite
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (metrics.isServerRunning) StatusOkGreen else CrimsonRed
                            ) {
                                Text(
                                    text = if (metrics.isServerRunning) {
                                        stringResource(R.string.dashboard_active)
                                    } else {
                                        stringResource(R.string.dashboard_stopped)
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PureWhite,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = PureWhite.copy(alpha = 0.14f),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "عنوان الشبكة المحلي",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PureWhite.copy(alpha = 0.8f)
                                    )
                                    Text(
                                        text = metrics.localIpAddress,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontFamily = JetBrainsMonoFontFamily
                                        ),
                                        color = PureWhite
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = PureWhite.copy(alpha = 0.14f),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "معمارية المعالج",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PureWhite.copy(alpha = 0.8f)
                                    )
                                    Text(
                                        text = metrics.deviceAbi.ifBlank { "arm64-v8a" },
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontFamily = JetBrainsMonoFontFamily
                                        ),
                                        color = PureWhite
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onNavigateToSites,
                                modifier = Modifier.testTag("dashboard_manage_sites_btn"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PureWhite,
                                    contentColor = RoyalBluePrimary
                                )
                            ) {
                                Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("إدارة المواقع (${runningSiteIds.size}/${sites.size})")
                            }
                            Button(
                                onClick = onNavigateToEngines,
                                modifier = Modifier.testTag("dashboard_engines_btn"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CrimsonRed,
                                    contentColor = PureWhite
                                )
                            ) {
                                Icon(Icons.Filled.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("المحركات")
                            }
                        }
                    }
                }
            }
        }

        // System Telemetry Grid (Responsive 2 cols on phones, 3 cols on tablets)
        item {
            SectionHeader(
                title = "مراقبة موارد الجهاز الفعلية",
                subtitle = metrics.androidVersion
            )
        }

        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= 680.dp) 3 else 2
                val ramPercent = if (metrics.ramTotalMb > 0) {
                    metrics.ramUsedMb.toFloat() / metrics.ramTotalMb.toFloat()
                } else 0f
                val storagePercent = if (metrics.storageTotalBytes > 0) {
                    metrics.storageUsedBytes.toFloat() / metrics.storageTotalBytes.toFloat()
                } else 0f
                val cpuFrac = (metrics.cpuUsagePercent / 100f).coerceIn(0f, 1f)

                val cards = listOf<@Composable (Modifier) -> Unit>(
                    { mod ->
                        BrandMetricCard(
                            title = stringResource(R.string.dashboard_sites_count),
                            value = "${sites.size}",
                            subtitle = "يعمل حالياً: ${runningSiteIds.size}",
                            icon = Icons.Filled.Public,
                            accentColor = RoyalBluePrimary,
                            modifier = mod
                        )
                    },
                    { mod ->
                        BrandMetricCard(
                            title = stringResource(R.string.dashboard_dbs_count),
                            value = "${databases.size}",
                            subtitle = "SQLite & محركات الربط",
                            icon = Icons.Filled.Storage,
                            accentColor = ElectricBlue,
                            modifier = mod
                        )
                    },
                    { mod ->
                        BrandMetricCard(
                            title = stringResource(R.string.dashboard_connections),
                            value = "${metrics.activeConnections}",
                            subtitle = "مآخذ TCP نشطة الآن",
                            icon = Icons.Filled.Lan,
                            accentColor = if (metrics.activeConnections > 0) StatusOkGreen else RoyalBluePrimary,
                            modifier = mod
                        )
                    },
                    { mod ->
                        BrandMetricCard(
                            title = stringResource(R.string.dashboard_cpu),
                            value = String.format(Locale.US, "%.1f%%", metrics.cpuUsagePercent),
                            subtitle = "${Runtime.getRuntime().availableProcessors()} أنوية معالجة",
                            icon = Icons.Filled.Speed,
                            accentColor = CrimsonRed,
                            progress = cpuFrac,
                            modifier = mod
                        )
                    },
                    { mod ->
                        BrandMetricCard(
                            title = stringResource(R.string.dashboard_ram),
                            value = "${metrics.ramUsedMb} MB",
                            subtitle = "الإجمالي: ${metrics.ramTotalMb} MB",
                            icon = Icons.Filled.Memory,
                            accentColor = RoyalBluePrimary,
                            progress = ramPercent,
                            modifier = mod
                        )
                    },
                    { mod ->
                        BrandMetricCard(
                            title = stringResource(R.string.dashboard_storage),
                            value = formatBytes(metrics.storageFreeBytes),
                            subtitle = "متاح من ${formatBytes(metrics.storageTotalBytes)}",
                            icon = Icons.Filled.SdStorage,
                            accentColor = ElectricBlue,
                            progress = storagePercent,
                            modifier = mod
                        )
                    }
                )

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    cards.chunked(columns).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowItems.forEach { cardComposable ->
                                cardComposable(Modifier.weight(1f))
                            }
                            repeat(columns - rowItems.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // Real Engine Verification Status List
        item {
            SectionHeader(
                title = stringResource(R.string.dashboard_services),
                subtitle = "يتم التحقق الفعلي من المنافذ والملفات التنفيذية",
                trailing = {
                    IconButton(
                        onClick = onRefreshEngines,
                        modifier = Modifier.testTag("dashboard_refresh_engines_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "تحديث فحص المحركات",
                            tint = RoyalBluePrimary
                        )
                    }
                }
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    engines.forEachIndexed { idx, engine ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = engine.type.titleAr,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = engine.activeVersion,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            EngineStatusChip(status = engine.status)
                        }
                        if (idx < engines.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                    }
                }
            }
        }
    }
}
