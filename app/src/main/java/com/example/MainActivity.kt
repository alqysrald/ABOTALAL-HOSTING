package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DatabasesScreen
import com.example.ui.screens.EnginesScreen
import com.example.ui.screens.FileManagerScreen
import com.example.ui.screens.SecurityLogsScreen
import com.example.ui.screens.SitesScreen
import com.example.ui.screens.TunnelScreen
import com.example.ui.theme.AbotalalHostingTheme
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.DeepNavy
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RoyalBluePrimary
import com.example.ui.viewmodel.HostingViewModel

const val ROUTE_DASHBOARD = "dashboard"
const val ROUTE_SITES = "sites"
const val ROUTE_ENGINES = "engines"
const val ROUTE_DATABASES = "databases"
const val ROUTE_FILES = "files"
const val ROUTE_TUNNEL = "tunnel"
const val ROUTE_SECURITY = "security"

private data class NavDestinationItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AbotalalHostingTheme {
                AbotalalHostingRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbotalalHostingRoot(viewModel: HostingViewModel = viewModel()) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val sites by viewModel.sites.collectAsStateWithLifecycle()
    val runningSiteIds by viewModel.runningSiteIds.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val databases by viewModel.databases.collectAsStateWithLifecycle()
    val runtimeConfigs by viewModel.runtimeConfigs.collectAsStateWithLifecycle()
    val tunnelConfigs by viewModel.tunnelConfigs.collectAsStateWithLifecycle()
    val tunnelStates by viewModel.tunnelRuntimeStates.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val securitySettings by viewModel.securitySettings.collectAsStateWithLifecycle()
    val isAdminLocked by viewModel.isAdminLocked.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    val selectedSiteForFiles by viewModel.selectedSiteForFiles.collectAsStateWithLifecycle()
    val currentDir by viewModel.currentDir.collectAsStateWithLifecycle()
    val fileEntries by viewModel.fileEntries.collectAsStateWithLifecycle()
    val clipboardFile by viewModel.clipboardFile.collectAsStateWithLifecycle()
    val transferProgress by viewModel.transferProgress.collectAsStateWithLifecycle()
    val inspectedTables by viewModel.inspectedTables.collectAsStateWithLifecycle()
    val lastSqlResult by viewModel.lastSqlResult.collectAsStateWithLifecycle()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(statusBanner) {
        val msg = statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    if (isAdminLocked) {
        AdminLockOverlay(
            onUnlockAttempt = { pin -> viewModel.unlockAdmin(pin) }
        )
        return
    }

    val allDestinations = listOf(
        NavDestinationItem(ROUTE_DASHBOARD, stringResource(R.string.nav_dashboard), Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
        NavDestinationItem(ROUTE_SITES, stringResource(R.string.nav_sites), Icons.Filled.Language, Icons.Outlined.Language),
        NavDestinationItem(ROUTE_ENGINES, stringResource(R.string.nav_engines), Icons.Filled.Dns, Icons.Outlined.Dns),
        NavDestinationItem(ROUTE_DATABASES, stringResource(R.string.nav_databases), Icons.Filled.Storage, Icons.Outlined.Storage),
        NavDestinationItem(ROUTE_FILES, stringResource(R.string.nav_files), Icons.Filled.Folder, Icons.Outlined.Folder),
        NavDestinationItem(ROUTE_TUNNEL, stringResource(R.string.nav_tunnel), Icons.Filled.CloudUpload, Icons.Outlined.CloudUpload),
        NavDestinationItem(ROUTE_SECURITY, stringResource(R.string.nav_security), Icons.Filled.Security, Icons.Outlined.Security)
    )

    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route ?: ROUTE_DASHBOARD

    fun navigateSingleTop(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 700.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = DeepNavy,
                        titleContentColor = PureWhite,
                        actionIconContentColor = PureWhite
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        if (runningSiteIds.isNotEmpty()) PureWhite else CrimsonRed,
                                        RoundedCornerShape(50)
                                    )
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.app_brand_en),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily
                                    ),
                                    color = PureWhite.copy(alpha = 0.75f)
                                )
                            }
                        }
                    },
                    actions = {
                        if (!useRail) {
                            IconButton(
                                onClick = { navigateSingleTop(ROUTE_TUNNEL) },
                                modifier = Modifier.testTag("topbar_tunnel_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CloudUpload,
                                    contentDescription = stringResource(R.string.nav_tunnel),
                                    tint = if (currentRoute == ROUTE_TUNNEL) CrimsonRed else PureWhite
                                )
                            }
                            IconButton(
                                onClick = { navigateSingleTop(ROUTE_SECURITY) },
                                modifier = Modifier.testTag("topbar_security_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Security,
                                    contentDescription = stringResource(R.string.nav_security),
                                    tint = if (currentRoute == ROUTE_SECURITY) CrimsonRed else PureWhite
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        allDestinations.take(5).forEach { item ->
                            val selected = currentRoute == item.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateSingleTop(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label, maxLines = 1) },
                                modifier = Modifier.testTag("nav_${item.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Spacer(Modifier.height(8.dp))
                        allDestinations.forEach { item ->
                            val selected = currentRoute == item.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateSingleTop(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag("rail_${item.route}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_DASHBOARD
                    ) {
                        composable(ROUTE_DASHBOARD) {
                            DashboardScreen(
                                metrics = metrics,
                                sites = sites,
                                runningSiteIds = runningSiteIds,
                                databases = databases,
                                engines = engines,
                                onRefreshEngines = { viewModel.refreshEngines() },
                                onNavigateToSites = { navigateSingleTop(ROUTE_SITES) },
                                onNavigateToEngines = { navigateSingleTop(ROUTE_ENGINES) }
                            )
                        }

                        composable(ROUTE_SITES) {
                            SitesScreen(
                                sites = sites,
                                runningSiteIds = runningSiteIds,
                                logs = logs,
                                onCreateSite = { name, port, type, ver, entry, lan ->
                                    viewModel.createSite(name, port, type, ver, entry, lan)
                                },
                                onUpdateSite = { viewModel.updateSiteConfig(it) },
                                onStartSite = { site ->
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (!granted) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    viewModel.startSite(site)
                                },
                                onStopSite = { viewModel.stopSite(it) },
                                onDeleteSite = { viewModel.deleteSite(it) },
                                onOpenFilesForSite = { site ->
                                    viewModel.selectSiteForFiles(site)
                                    navigateSingleTop(ROUTE_FILES)
                                }
                            )
                        }

                        composable(ROUTE_ENGINES) {
                            EnginesScreen(
                                engines = engines,
                                runtimeConfigs = runtimeConfigs,
                                onRefreshEngines = { viewModel.refreshEngines() },
                                onSavePhpConfig = { ver, ini, exts -> viewModel.savePhpConfig(ver, ini, exts) },
                                onImportPhpZip = { ver, uri -> viewModel.importPhpRuntimeZip(ver, uri) },
                                onSavePythonRequirements = { ver, req -> viewModel.savePythonRequirements(ver, req) }
                            )
                        }

                        composable(ROUTE_DATABASES) {
                            DatabasesScreen(
                                databases = databases,
                                engines = engines,
                                inspectedTables = inspectedTables,
                                lastSqlResult = lastSqlResult,
                                onCreateDatabase = { name, type, user, pass, priv ->
                                    viewModel.createDatabase(name, type, user, pass, priv)
                                },
                                onDeleteDatabase = { viewModel.deleteDatabase(it) },
                                onUpdatePrivileges = { db, u, p, priv ->
                                    viewModel.updateDbUserPrivileges(db, u, p, priv)
                                },
                                onInspectTables = { viewModel.inspectDatabaseTables(it) },
                                onRunSql = { db, sql -> viewModel.runSqlQuery(db, sql) },
                                onImportSql = { db, uri -> viewModel.importSqlIntoDb(db, uri) },
                                onExportSql = { db, uri -> viewModel.exportSqlFromDb(db, uri) },
                                onImportPhpMyAdminZip = { uri -> viewModel.importPhpMyAdminZip(uri) }
                            )
                        }

                        composable(ROUTE_FILES) {
                            FileManagerScreen(
                                sites = sites,
                                selectedSite = selectedSiteForFiles,
                                currentDir = currentDir,
                                entries = fileEntries,
                                clipboardFile = clipboardFile,
                                transferProgress = transferProgress,
                                onSelectSite = { viewModel.selectSiteForFiles(it) },
                                onNavigateInto = { viewModel.navigateToDirectory(it) },
                                onNavigateUp = { viewModel.navigateUpDirectory() },
                                onCreateFolder = { viewModel.createFolder(it) },
                                onCreateFile = { viewModel.createFile(it) },
                                onRenameEntry = { e, n -> viewModel.renameFile(e, n) },
                                onDeleteEntry = { viewModel.deleteFile(it) },
                                onSetClipboard = { e, mv -> viewModel.setClipboard(e, mv) },
                                onPasteClipboard = { viewModel.pasteClipboard() },
                                onUploadFiles = { viewModel.uploadMultipleFiles(it) },
                                onImportZip = { viewModel.importAndExtractZip(it) },
                                onExtractLocalZip = { viewModel.extractLocalZip(it) },
                                onCompressEntry = { viewModel.compressEntryToZip(it) },
                                onUpdatePermissions = { e, mode -> viewModel.updateUnixPermissions(e, mode) },
                                onDismissProgress = { viewModel.dismissTransferProgress() },
                                onLoadFileText = { viewModel.loadFileText(it) },
                                onSaveFileText = { p, c -> viewModel.saveFileText(p, c) }
                            )
                        }

                        composable(ROUTE_TUNNEL) {
                            TunnelScreen(
                                sites = sites,
                                runningSiteIds = runningSiteIds,
                                tunnelConfigs = tunnelConfigs,
                                tunnelStates = tunnelStates,
                                onSaveTunnelConfig = { id, prov, ep, https, auth, u, p, cidr ->
                                    viewModel.saveAndPrepareTunnel(id, prov, ep, https, auth, u, p, cidr)
                                },
                                onStartTunnelWithApproval = { cfg, port ->
                                    viewModel.startTunnelWithApproval(cfg, port)
                                },
                                onStopTunnel = { viewModel.stopTunnel(it) }
                            )
                        }

                        composable(ROUTE_SECURITY) {
                            SecurityLogsScreen(
                                settings = securitySettings,
                                logs = logs,
                                onSavePin = { enabled, pin -> viewModel.saveSecurityPin(enabled, pin) },
                                onUpdateToggles = { localOnly, reqApp, logReq ->
                                    viewModel.updateSecurityToggles(localOnly, reqApp, logReq)
                                },
                                onClearLogs = { viewModel.clearLogs() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminLockOverlay(onUnlockAttempt: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DeepNavy
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = stringResource(R.string.cd_security_lock),
                        tint = RoyalBluePrimary,
                        modifier = Modifier.size(48.dp)
                    )
                    Text("لوحة إدارة استضافة أبوطلال محمية", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter { ch -> ch.isDigit() }; errorMsg = null },
                        label = { Text("أدخل رمز PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("unlock_pin_input")
                    )
                    if (errorMsg != null) {
                        Text(errorMsg!!, color = CrimsonRed, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            if (!onUnlockAttempt(pin)) {
                                errorMsg = "رمز PIN غير صحيح"
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("unlock_pin_btn")
                    ) {
                        Text("فتح لوحة الإدارة")
                    }
                }
            }
        }
    }
}
