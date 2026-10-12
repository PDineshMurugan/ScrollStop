package com.scrollcounter.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.os.Build
import com.scrollcounter.app.widget.ScrollCounterWidget
import com.scrollcounter.app.widget.ScrollAnalyticsWidget
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.scrollcounter.app.BuildConfig
import com.scrollcounter.app.updater.UpdateManager
import com.scrollcounter.app.updater.UpdateStatus
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scrollcounter.app.data.DailyRecord
import com.scrollcounter.app.data.LimitMode
import com.scrollcounter.app.data.UserSettings
import com.scrollcounter.app.service.ScrollAccessibilityService

// --- Ultra-Minimalist Monochrome + Deep Zinc Palette ---
private val BgOled = Color(0xFF000000)
private val SurfaceDark = Color(0xFF0D0D10)
private val SurfaceCard = Color(0xFF131317)
private val SurfaceCardSecondary = Color(0xFF1A1A20)
private val BorderSubtle = Color(0xFF222228)
private val BorderActive = Color(0xFF3B3B44)

private val TextPrimary = Color(0xFFF4F4F5)
private val TextSecondary = Color(0xFF71717A)
private val TextTertiary = Color(0xFF52525B)

private val AccentGreen = Color(0xFF10B981)
private val AccentAmber = Color(0xFFF59E0B)
private val AccentRed = Color(0xFFEF4444)

private val InstaGradient = listOf(Color(0xFF833AB4), Color(0xFFFD1D1D), Color(0xFFFCB045))
private val YtColor = Color(0xFFFF0000)

enum class MainTab {
    TODAY,
    ANALYTICS,
    SETTINGS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScrollStopTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun ScrollStopTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = TextPrimary,
        secondary = TextSecondary,
        background = BgOled,
        surface = SurfaceDark,
        onPrimary = BgOled,
        onBackground = TextPrimary,
        onSurface = TextPrimary
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = ScrollCounterApp.instance.preferencesManager
    val settings by prefs.settings.collectAsState()

    var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityPermission(context)) }
    var isOverlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isBatteryIgnored by remember { mutableStateOf(checkBatteryOptimization(context)) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val updateStatus by UpdateManager.status.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showWidgetGuideDialog by remember { mutableStateOf(false) }
    var showWidgetChooserDialog by remember { mutableStateOf(false) }

    // Check for updates automatically in the background
    LaunchedEffect(Unit) {
        UpdateManager.checkForUpdates(force = false)
    }

    DisposableEffect(Unit) {
        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = checkAccessibilityPermission(context)
                isOverlayEnabled = Settings.canDrawOverlays(context)
                isBatteryIgnored = checkBatteryOptimization(context)
            }
        }
        val lifecycle = (context as ComponentActivity).lifecycle
        lifecycle.addObserver(lifecycleObserver)
        onDispose { lifecycle.removeObserver(lifecycleObserver) }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = SurfaceCard,
            title = {
                Text("Reset Today's Stats?", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            },
            text = {
                Text("This resets your Reel and Short counters back to zero for today.", color = TextSecondary, fontSize = 14.sp)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        prefs.resetTodayCounts()
                        showResetConfirm = false
                    }
                ) {
                    Text("Reset", color = AccentRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    if (showUpdateDialog && updateStatus is UpdateStatus.UpdateAvailable) {
        val release = (updateStatus as UpdateStatus.UpdateAvailable).release
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            containerColor = SurfaceCard,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Update Available: v${release.versionName}",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "A new release of ScrollStop is available on GitHub.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    if (release.body.isNotBlank()) {
                        Text(
                            text = "Release Notes:",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = release.body.take(300),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 6
                        )
                    }
                    Text(
                        text = "Installed: v${BuildConfig.VERSION_NAME} ➔ Latest: v${release.versionName}",
                        color = AccentGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        UpdateManager.openBrowser(context, release.downloadUrl.ifEmpty { release.htmlUrl })
                        showUpdateDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = BgOled)
                ) {
                    Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download APK", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("Later", color = TextSecondary)
                }
            }
        )
    }

    if (showWidgetChooserDialog) {
        AlertDialog(
            onDismissRequest = { showWidgetChooserDialog = false },
            containerColor = SurfaceCard,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Widgets,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Add Home Screen Widget",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Choose your preferred widget style to pin to your home screen:",
                        color = TextSecondary,
                        fontSize = 12.5.sp
                    )

                    // Option 1: 2x2 Quick Counter
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceDark)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                            .clickable {
                                showWidgetChooserDialog = false
                                requestPinWidget(
                                    context = context,
                                    providerClass = ScrollCounterWidget::class.java,
                                    onShowGuide = { showWidgetGuideDialog = true }
                                )
                            }
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Quick Counter",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(TextPrimary)
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "2 × 2",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BgOled
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Clean & minimal: Live Reels & Shorts scroll count and watch time side-by-side.",
                            fontSize = 11.5.sp,
                            color = TextSecondary
                        )
                    }

                    // Option 2: 3x4 Weekly Analytics
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceDark)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                            .clickable {
                                showWidgetChooserDialog = false
                                requestPinWidget(
                                    context = context,
                                    providerClass = ScrollAnalyticsWidget::class.java,
                                    onShowGuide = { showWidgetGuideDialog = true }
                                )
                            }
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Weekly Analytics",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AccentGreen)
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "3 × 4",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BgOled
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Full dashboard: Live scroll counts plus 7-day activity bar chart and weekly totals.",
                            fontSize = 11.5.sp,
                            color = TextSecondary
                        )
                    }

                    // Manual guide shortcut link
                    Text(
                        text = "Don't see pin prompt? Tap here for manual instructions",
                        fontSize = 11.5.sp,
                        color = TextTertiary,
                        modifier = Modifier
                            .clickable {
                                showWidgetChooserDialog = false
                                showWidgetGuideDialog = true
                            }
                            .padding(top = 2.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showWidgetChooserDialog = false }) {
                    Text("Close", color = TextSecondary)
                }
            }
        )
    }

    if (showWidgetGuideDialog) {
        AlertDialog(
            onDismissRequest = { showWidgetGuideDialog = false },
            containerColor = SurfaceCard,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Widgets,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Home Screen Widgets",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "How to add ScrollStop widgets to your home screen:",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "1. Long press any empty space on your home screen.\n2. Tap 'Widgets' from the menu.\n3. Locate 'ScrollStop' to see both widgets:\n   • Scroll Counter (2×2) — Clean count & time alone\n   • Weekly Analytics (3×4) — Counters + 7-day activity bars\n4. Drag your preferred widget to your screen!",
                        color = TextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showWidgetGuideDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = BgOled)
                ) {
                    Text("Got it", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    var currentTab by remember { mutableStateOf(MainTab.TODAY) }

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        containerColor = BgOled,
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 0.8.dp, color = BorderSubtle, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                NavigationBarItem(
                    selected = currentTab == MainTab.TODAY,
                    onClick = { currentTab = MainTab.TODAY },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "Today",
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "Today",
                            fontSize = 11.sp,
                            fontWeight = if (currentTab == MainTab.TODAY) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BgOled,
                        selectedTextColor = TextPrimary,
                        indicatorColor = TextPrimary,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )

                NavigationBarItem(
                    selected = currentTab == MainTab.ANALYTICS,
                    onClick = { currentTab = MainTab.ANALYTICS },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = "Analytics",
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "Analytics",
                            fontSize = 11.sp,
                            fontWeight = if (currentTab == MainTab.ANALYTICS) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BgOled,
                        selectedTextColor = TextPrimary,
                        indicatorColor = TextPrimary,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )

                NavigationBarItem(
                    selected = currentTab == MainTab.SETTINGS,
                    onClick = { currentTab = MainTab.SETTINGS },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "Settings",
                            fontSize = 11.sp,
                            fontWeight = if (currentTab == MainTab.SETTINGS) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BgOled,
                        selectedTextColor = TextPrimary,
                        indicatorColor = TextPrimary,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )
            }
        }
    ) { paddingValues ->
        when (currentTab) {
            MainTab.TODAY -> {
                TodayTab(
                    modifier = Modifier.padding(paddingValues),
                    settings = settings,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    isOverlayEnabled = isOverlayEnabled,
                    isBatteryIgnored = isBatteryIgnored,
                    updateStatus = updateStatus,
                    onOpenAccessibility = { openAccessibilitySettings(context) },
                    onOpenOverlay = { openOverlaySettings(context) },
                    onOpenAppInfo = { openAppInfo(context) },
                    onRequestBattery = { requestBatteryOptimizationExemption(context) },
                    onOpenUpdateDialog = { showUpdateDialog = true },
                    onResetClick = { showResetConfirm = true },
                    onNavigateToAnalytics = { currentTab = MainTab.ANALYTICS },
                    onNavigateToSettings = { currentTab = MainTab.SETTINGS },
                    onShowWidgetChooser = { showWidgetChooserDialog = true },
                    onToggleInstagram = { prefs.updateInstagramEnabled(it) },
                    onToggleYouTube = { prefs.updateYouTubeEnabled(it) }
                )
            }
            MainTab.ANALYTICS -> {
                AnalyticsTab(
                    modifier = Modifier.padding(paddingValues),
                    settings = settings,
                    onShowWidgetChooser = { showWidgetChooserDialog = true }
                )
            }
            MainTab.SETTINGS -> {
                SettingsTab(
                    modifier = Modifier.padding(paddingValues),
                    settings = settings,
                    updateStatus = updateStatus,
                    isBatteryIgnored = isBatteryIgnored,
                    onModeChange = { prefs.updateEnforceLimits(it) },
                    onLimitModeSelected = { prefs.updateLimitMode(it) },
                    onToggleInstagram = { prefs.updateInstagramEnabled(it) },
                    onInstagramScrollLimitChange = { prefs.updateInstagramScrollLimit(it) },
                    onInstagramTimeLimitChange = { prefs.updateInstagramTimeLimit(it) },
                    onToggleYouTube = { prefs.updateYouTubeEnabled(it) },
                    onYouTubeScrollLimitChange = { prefs.updateYouTubeScrollLimit(it) },
                    onYouTubeTimeLimitChange = { prefs.updateYouTubeTimeLimit(it) },
                    onCloseAppOnLimitChange = { prefs.updateCloseAppOnLimit(it) },
                    onShowBlockingOverlayChange = { prefs.updateShowBlockingOverlay(it) },
                    onRequestBattery = { requestBatteryOptimizationExemption(context) },
                    onCheckUpdates = {
                        if (updateStatus is UpdateStatus.UpdateAvailable) {
                            showUpdateDialog = true
                        } else {
                            coroutineScope.launch {
                                UpdateManager.checkForUpdates(force = true)
                            }
                        }
                    },
                    onOpenUrl = { url -> UpdateManager.openBrowser(context, url) },
                    onShowWidgetChooser = { showWidgetChooserDialog = true }
                )
            }
        }
    }
}

// ==========================================
// --- TAB 1: TODAY / FOCUS SCREEN ---
// ==========================================
@Composable
fun TodayTab(
    modifier: Modifier = Modifier,
    settings: UserSettings,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    isBatteryIgnored: Boolean,
    updateStatus: UpdateStatus,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onRequestBattery: () -> Unit,
    onOpenUpdateDialog: () -> Unit,
    onResetClick: () -> Unit,
    onNavigateToAnalytics: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onShowWidgetChooser: () -> Unit,
    onToggleInstagram: (Boolean) -> Unit,
    onToggleYouTube: (Boolean) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Minimalist Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ScrollStop",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    letterSpacing = (-0.5).sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isAccessibilityEnabled && isOverlayEnabled) AccentGreen else AccentAmber
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isAccessibilityEnabled && isOverlayEnabled) "Active • Tracking Reels & Shorts" else "Action needed to count",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )
                }
            }

            IconButton(
                onClick = onResetClick,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(SurfaceDark)
                    .border(1.dp, BorderSubtle, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset Stats",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Permissions Alert (if setup missing)
        if (!isAccessibilityEnabled || !isOverlayEnabled) {
            MissingPermissionsBanner(
                accessibilityGranted = isAccessibilityEnabled,
                overlayGranted = isOverlayEnabled,
                onOpenAccessibility = onOpenAccessibility,
                onOpenOverlay = onOpenOverlay,
                onOpenAppInfo = onOpenAppInfo
            )
        }

        // Battery Banner (if not ignored)
        if (!isBatteryIgnored && isAccessibilityEnabled && isOverlayEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                    .clickable { onRequestBattery() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = AccentAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Allow Unrestricted Battery",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Prevents missing counts on Samsung & Xiaomi",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AccentAmber.copy(alpha = 0.15f))
                        .border(0.8.dp, AccentAmber.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "Allow",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentAmber,
                        maxLines = 1
                    )
                }
            }
        }

        // Update banner
        AnimatedVisibility(visible = updateStatus is UpdateStatus.UpdateAvailable) {
            val release = (updateStatus as? UpdateStatus.UpdateAvailable)?.release
            if (release != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AccentGreen.copy(alpha = 0.12f))
                        .border(1.dp, AccentGreen.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .clickable { onOpenUpdateDialog() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "ScrollStop v${release.versionName} Available",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Tap to review & download",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AccentGreen.copy(alpha = 0.15f))
                            .border(0.8.dp, AccentGreen.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "Update",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentGreen,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // --- HERO GIANT TOTAL COUNT CARD ---
        TodayHeroCountCard(settings = settings)

        // Platform Active Toggles & Quick Counts
        Text(
            text = "PLATFORM TRACKING",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextTertiary,
            letterSpacing = 1.sp
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
        ) {
            // Instagram Quick Row
            QuickPlatformToggleRow(
                name = "Instagram Reels",
                accent = InstaGradient,
                count = settings.instagramTodayCount,
                minutes = settings.instagramSecondsToday / 60,
                enabled = settings.instagramEnabled,
                onToggle = onToggleInstagram
            )

            HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)

            // YouTube Quick Row
            QuickPlatformToggleRow(
                name = "YouTube Shorts",
                accent = listOf(YtColor, YtColor),
                count = settings.youtubeTodayCount,
                minutes = settings.youtubeSecondsToday / 60,
                enabled = settings.youtubeEnabled,
                onToggle = onToggleYouTube
            )
        }

        // Quick Navigation Shortcuts
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .clickable { onNavigateToAnalytics() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.BarChart,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Analytics", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("7-Day Trends ➔", fontSize = 11.sp, color = TextSecondary)
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .clickable { onNavigateToSettings() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Settings", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Limits & Modes ➔", fontSize = 11.sp, color = TextSecondary)
                }
            }
        }

        // Quick Widget Banner
        QuickWidgetBanner(onAddWidget = onShowWidgetChooser)

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// --- Hero Giant Count Card (Focal Point of Home Screen) ---
@Composable
fun TodayHeroCountCard(settings: UserSettings) {
    val totalCount = settings.instagramTodayCount + settings.youtubeTodayCount
    val totalMinutes = (settings.instagramSecondsToday + settings.youtubeSecondsToday) / 60

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(22.dp))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tag Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (totalCount > 0) AccentAmber else AccentGreen)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "TOTAL TODAY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextTertiary,
                    letterSpacing = 1.2.sp
                )
            }

            Text(
                text = "${totalMinutes}m spent",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary
            )
        }

        // Big Hero Number
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Text(
                text = "$totalCount",
                fontSize = 68.sp,
                fontWeight = FontWeight.Black,
                color = TextPrimary,
                letterSpacing = (-2.5).sp,
                lineHeight = 72.sp
            )
            Text(
                text = if (totalCount == 1) "reel or short scrolled" else "reels & shorts doomscrolled",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary
            )
        }

        // Side-by-side platform split pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Reels
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceCard)
                    .border(0.8.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(InstaGradient))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Reels",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )
                    Text(
                        text = "${settings.instagramTodayCount}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }

            // Shorts
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceCard)
                    .border(0.8.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(YtColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Shorts",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )
                    Text(
                        text = "${settings.youtubeTodayCount}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }
        }

        // Limit Progress Bar (if limits enabled)
        if (settings.enforceLimits) {
            val isScrollLimit = settings.limitMode == LimitMode.SCROLLS
            val totalLimit = if (isScrollLimit) {
                (if (settings.instagramEnabled) settings.instagramScrollLimit else 0) +
                (if (settings.youtubeEnabled) settings.youtubeScrollLimit else 0)
            } else {
                (if (settings.instagramEnabled) settings.instagramTimeLimitMinutes else 0) +
                (if (settings.youtubeEnabled) settings.youtubeTimeLimitMinutes else 0)
            }

            val currentMetric = if (isScrollLimit) totalCount else totalMinutes
            val progress = if (totalLimit > 0) (currentMetric.toFloat() / totalLimit).coerceIn(0f, 1f) else 0f
            val isExceeded = totalLimit > 0 && currentMetric >= totalLimit

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceCard)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isExceeded) "Daily Limit Reached!" else "Daily Limit Progress",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isExceeded) AccentRed else if (progress > 0.8f) AccentAmber else TextSecondary
                    )
                    Text(
                        text = if (isScrollLimit) "$currentMetric / $totalLimit scrolls" else "$currentMetric / $totalLimit min",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(SurfaceDark)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(
                                if (isExceeded) AccentRed
                                else if (progress > 0.8f) AccentAmber
                                else AccentGreen
                            )
                    )
                }
            }
        }

        // Mindful insight status
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceCardSecondary.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            val statusText = when {
                totalCount == 0 -> "Zen mode 🧘 Zero doomscrolling today. Keep it up!"
                totalCount < 30 -> "Mindful usage. You're staying in control."
                totalCount < 80 -> "Moderate scrolling. Be mindful of your time."
                else -> "High scrolling alert! Time to put down the phone."
            }
            Text(
                text = statusText,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = if (totalCount >= 80) AccentAmber else TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

// --- Quick Platform Toggle Row ---
@Composable
fun QuickPlatformToggleRow(
    name: String,
    accent: List<Color>,
    count: Int,
    minutes: Int,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(accent))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Text("$count scrolls • ${minutes}m today", fontSize = 11.5.sp, color = TextSecondary)
            }
        }

        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BgOled,
                checkedTrackColor = TextPrimary,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceCardSecondary,
                uncheckedBorderColor = BorderSubtle
            )
        )
    }
}

// ==========================================
// --- TAB 2: ANALYTICS SCREEN ---
// ==========================================
@Composable
fun AnalyticsTab(
    modifier: Modifier = Modifier,
    settings: UserSettings,
    onShowWidgetChooser: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        Column {
            Text(
                text = "Analytics",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                letterSpacing = (-0.5).sp
            )
            Text(
                text = "7-day activity patterns & app distribution",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Last 7 Days Interactive Chart
        WeeklyAnalyticsCard(weeklyRecords = settings.weeklyRecords)

        // Platform Distribution Card
        PlatformComparisonCard(settings = settings)

        // 7-Day Summary Stats Grid
        WeeklySummaryGrid(settings = settings)

        // Widget Banner
        QuickWidgetBanner(onAddWidget = onShowWidgetChooser)

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// --- Platform Distribution Card (Reels vs Shorts Proportions) ---
@Composable
fun PlatformComparisonCard(settings: UserSettings) {
    val instaCount = settings.instagramTodayCount
    val ytCount = settings.youtubeTodayCount
    val totalCount = (instaCount + ytCount).coerceAtLeast(1)
    val instaPercent = ((instaCount.toFloat() / totalCount) * 100).toInt()
    val ytPercent = 100 - instaPercent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PLATFORM DISTRIBUTION",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextTertiary,
                letterSpacing = 1.sp
            )
            Text(
                text = "Today",
                fontSize = 11.5.sp,
                color = TextSecondary
            )
        }

        // Proportional Split Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(SurfaceCardSecondary)
        ) {
            if (instaCount > 0 || ytCount > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(instaCount.toFloat().coerceAtLeast(0.01f))
                        .background(Brush.linearGradient(InstaGradient))
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(ytCount.toFloat().coerceAtLeast(0.01f))
                        .background(YtColor)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SurfaceCardSecondary)
                )
            }
        }

        // Detail Rows
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(InstaGradient))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Instagram Reels",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Text(
                        text = "$instaCount scrolls ($instaPercent%) • ${settings.instagramSecondsToday / 60}m",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(YtColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "YouTube Shorts",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Text(
                        text = "$ytCount scrolls ($ytPercent%) • ${settings.youtubeSecondsToday / 60}m",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

// --- Weekly Summary 4-Box Grid ---
@Composable
fun WeeklySummaryGrid(settings: UserSettings) {
    val totalWeeklyScrolls = settings.weeklyRecords.sumOf { it.totalCount }
    val totalWeeklyMinutes = settings.weeklyRecords.sumOf { it.totalMinutes }
    val avgDailyScrolls = if (settings.weeklyRecords.isNotEmpty()) totalWeeklyScrolls / settings.weeklyRecords.size else 0
    val topDay = settings.weeklyRecords.maxByOrNull { it.totalCount }?.dayLabel ?: "—"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "7-DAY SUMMARY",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextTertiary,
            letterSpacing = 1.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AnalyticsSummaryPill(
                modifier = Modifier.weight(1f),
                label = "Total Scrolls",
                value = "$totalWeeklyScrolls",
                caption = "Last 7 days"
            )
            AnalyticsSummaryPill(
                modifier = Modifier.weight(1f),
                label = "Total Time",
                value = "${totalWeeklyMinutes}m",
                caption = "Active watching"
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AnalyticsSummaryPill(
                modifier = Modifier.weight(1f),
                label = "Daily Average",
                value = "$avgDailyScrolls",
                caption = "Scrolls / day"
            )
            AnalyticsSummaryPill(
                modifier = Modifier.weight(1f),
                label = "Peak Day",
                value = topDay,
                caption = "Highest activity"
            )
        }
    }
}

@Composable
fun AnalyticsSummaryPill(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    caption: String
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = label, fontSize = 11.sp, color = TextSecondary, maxLines = 1)
        Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
        Text(text = caption, fontSize = 10.5.sp, color = TextTertiary, maxLines = 1)
    }
}

// ==========================================
// --- TAB 3: SETTINGS SCREEN ---
// ==========================================
@Composable
fun SettingsTab(
    modifier: Modifier = Modifier,
    settings: UserSettings,
    updateStatus: UpdateStatus,
    isBatteryIgnored: Boolean,
    onModeChange: (Boolean) -> Unit,
    onLimitModeSelected: (LimitMode) -> Unit,
    onToggleInstagram: (Boolean) -> Unit,
    onInstagramScrollLimitChange: (Int) -> Unit,
    onInstagramTimeLimitChange: (Int) -> Unit,
    onToggleYouTube: (Boolean) -> Unit,
    onYouTubeScrollLimitChange: (Int) -> Unit,
    onYouTubeTimeLimitChange: (Int) -> Unit,
    onCloseAppOnLimitChange: (Boolean) -> Unit,
    onShowBlockingOverlayChange: (Boolean) -> Unit,
    onRequestBattery: () -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onShowWidgetChooser: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        Column {
            Text(
                text = "Settings",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                letterSpacing = (-0.5).sp
            )
            Text(
                text = "Limits, mindful enforcement & customization",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Mode Segmented Switch
        ModeSelector(
            enforceLimits = settings.enforceLimits,
            onModeChange = onModeChange
        )

        // Limit Criteria
        AnimatedVisibility(visible = settings.enforceLimits) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LimitTypeSelector(
                    currentMode = settings.limitMode,
                    onModeSelected = onLimitModeSelected
                )
            }
        }

        // Platforms & Limits Config
        Text(
            text = "PLATFORMS & LIMITS",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextTertiary,
            letterSpacing = 1.sp
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
        ) {
            PlatformRow(
                name = "Instagram Reels",
                accent = InstaGradient,
                enabled = settings.instagramEnabled,
                count = settings.instagramTodayCount,
                activeSeconds = settings.instagramSecondsToday,
                scrollLimit = settings.instagramScrollLimit,
                timeLimitMinutes = settings.instagramTimeLimitMinutes,
                enforceLimits = settings.enforceLimits,
                limitMode = settings.limitMode,
                onToggle = onToggleInstagram,
                onScrollLimitChange = onInstagramScrollLimitChange,
                onTimeLimitChange = onInstagramTimeLimitChange
            )

            HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)

            PlatformRow(
                name = "YouTube Shorts",
                accent = listOf(YtColor, YtColor),
                enabled = settings.youtubeEnabled,
                count = settings.youtubeTodayCount,
                activeSeconds = settings.youtubeSecondsToday,
                scrollLimit = settings.youtubeScrollLimit,
                timeLimitMinutes = settings.youtubeTimeLimitMinutes,
                enforceLimits = settings.enforceLimits,
                limitMode = settings.limitMode,
                onToggle = onToggleYouTube,
                onScrollLimitChange = onYouTubeScrollLimitChange,
                onTimeLimitChange = onYouTubeTimeLimitChange
            )
        }

        // Enforcement Behavior
        if (settings.enforceLimits) {
            Text(
                text = "ENFORCEMENT BEHAVIOR",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextTertiary,
                letterSpacing = 1.sp
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            ) {
                MinimalToggleRow(
                    title = "Return to Home",
                    subtitle = "Closes app automatically when limit is hit",
                    checked = settings.closeAppOnLimit,
                    onCheckedChange = onCloseAppOnLimitChange
                )
                HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)
                MinimalToggleRow(
                    title = "Mindful Break Screen",
                    subtitle = "Full-screen pause notice before exit",
                    checked = settings.showBlockingOverlay,
                    onCheckedChange = onShowBlockingOverlayChange
                )
            }
        }

        // Battery Optimization
        if (!isBatteryIgnored) {
            Text(
                text = "DEVICE OPTIMIZATION",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextTertiary,
                letterSpacing = 1.sp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                    .clickable { onRequestBattery() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("Allow Unrestricted Battery", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text("Prevents background killing on Samsung & Xiaomi", fontSize = 11.5.sp, color = TextSecondary)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AccentAmber.copy(alpha = 0.15f))
                        .border(0.8.dp, AccentAmber.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text("Configure", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentAmber)
                }
            }
        }

        // Widgets
        Text(
            text = "HOME SCREEN WIDGETS",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextTertiary,
            letterSpacing = 1.sp
        )

        QuickWidgetBanner(onAddWidget = onShowWidgetChooser)

        // Community & Updates
        Text(
            text = "COMMUNITY & UPDATES",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextTertiary,
            letterSpacing = 1.sp
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
        ) {
            CommunityRow(
                title = "ScrollStop v${BuildConfig.VERSION_NAME}",
                subtitle = when (updateStatus) {
                    is UpdateStatus.Checking -> "Checking GitHub for updates..."
                    is UpdateStatus.UpdateAvailable -> "v${(updateStatus as UpdateStatus.UpdateAvailable).release.versionName} ready • Tap to install"
                    is UpdateStatus.UpToDate -> "Up to date • Build ${BuildConfig.VERSION_CODE}"
                    is UpdateStatus.Error -> "Tap to check for updates"
                    is UpdateStatus.Idle -> "Build ${BuildConfig.VERSION_CODE} • Tap to check"
                },
                badgeText = when (updateStatus) {
                    is UpdateStatus.Checking -> "Checking"
                    is UpdateStatus.UpdateAvailable -> "Update"
                    is UpdateStatus.UpToDate -> "Latest"
                    else -> "Check"
                },
                badgeColor = when (updateStatus) {
                    is UpdateStatus.UpdateAvailable -> AccentGreen
                    is UpdateStatus.UpToDate -> TextTertiary
                    else -> AccentAmber
                },
                icon = Icons.Default.SystemUpdate,
                onClick = onCheckUpdates
            )

            HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)

            CommunityRow(
                title = "GitHub Repository",
                subtitle = "Star the project & view source",
                badgeText = "GitHub",
                badgeColor = TextSecondary,
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                onClick = { onOpenUrl(UpdateManager.GITHUB_REPO_URL) }
            )

            HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)

            CommunityRow(
                title = "Report a Bug",
                subtitle = "Found an issue? Open on GitHub",
                badgeText = "Issues",
                badgeColor = AccentAmber,
                icon = Icons.Default.BugReport,
                onClick = { onOpenUrl(UpdateManager.GITHUB_BUG_REPORT_URL) }
            )

            HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)

            CommunityRow(
                title = "Request a Feature",
                subtitle = "Suggest new platforms & ideas",
                badgeText = "Ideas",
                badgeColor = TextPrimary,
                icon = Icons.Default.Lightbulb,
                onClick = { onOpenUrl(UpdateManager.GITHUB_FEATURE_REQUEST_URL) }
            )
        }

        // Support Creator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceDark)
                .border(1.dp, AccentAmber.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .clickable { onOpenUrl(UpdateManager.BUY_ME_A_CHAI_URL) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(AccentAmber.copy(alpha = 0.15f))
                        .border(1.dp, AccentAmber.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalCafe,
                        contentDescription = "Buy Me a Chai",
                        tint = AccentAmber,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Buy Dinesh a Chai",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Support Creator • Free & ad-free",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentAmber)
                    .clickable { onOpenUrl(UpdateManager.BUY_ME_A_CHAI_URL) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Support",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = BgOled
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("☕", fontSize = 11.sp)
                }
            }
        }

        // Clean Footer Signature
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "ScrollStop v${BuildConfig.VERSION_NAME} • 100% Offline & Private",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextTertiary,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Built with ❤️ by Dinesh Murugan P",
                fontSize = 10.5.sp,
                color = TextTertiary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun CommunityRow(
    title: String,
    subtitle: String,
    badgeText: String,
    badgeColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(SurfaceCardSecondary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeColor.copy(alpha = 0.15f))
                    .border(0.8.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// --- Hero Stats Grid ---
@Composable
fun HeroMetricsGrid(settings: UserSettings) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val instaMinutes = settings.instagramSecondsToday / 60
        val ytMinutes = settings.youtubeSecondsToday / 60

        val instaLimitRatio = if (settings.enforceLimits) {
            when (settings.limitMode) {
                LimitMode.SCROLLS -> if (settings.instagramScrollLimit > 0) settings.instagramTodayCount.toFloat() / settings.instagramScrollLimit else 0f
                LimitMode.TIME_MINUTES -> if (settings.instagramTimeLimitMinutes > 0) (settings.instagramSecondsToday / 60f) / settings.instagramTimeLimitMinutes else 0f
            }
        } else 0f

        val ytLimitRatio = if (settings.enforceLimits) {
            when (settings.limitMode) {
                LimitMode.SCROLLS -> if (settings.youtubeScrollLimit > 0) settings.youtubeTodayCount.toFloat() / settings.youtubeScrollLimit else 0f
                LimitMode.TIME_MINUTES -> if (settings.youtubeTimeLimitMinutes > 0) (settings.youtubeSecondsToday / 60f) / settings.youtubeTimeLimitMinutes else 0f
            }
        } else 0f

        // Reels Stat Box
        StatBox(
            modifier = Modifier.weight(1f),
            label = "REELS",
            badgeGradient = InstaGradient,
            count = settings.instagramTodayCount,
            timeMinutes = instaMinutes,
            limitRatio = instaLimitRatio,
            enforceLimits = settings.enforceLimits,
            limitText = if (settings.enforceLimits) {
                if (settings.limitMode == LimitMode.SCROLLS) "/ ${settings.instagramScrollLimit}" else "/ ${settings.instagramTimeLimitMinutes}m"
            } else ""
        )

        // Shorts Stat Box
        StatBox(
            modifier = Modifier.weight(1f),
            label = "SHORTS",
            badgeGradient = listOf(YtColor, YtColor),
            count = settings.youtubeTodayCount,
            timeMinutes = ytMinutes,
            limitRatio = ytLimitRatio,
            enforceLimits = settings.enforceLimits,
            limitText = if (settings.enforceLimits) {
                if (settings.limitMode == LimitMode.SCROLLS) "/ ${settings.youtubeScrollLimit}" else "/ ${settings.youtubeTimeLimitMinutes}m"
            } else ""
        )
    }
}

enum class AnalyticsMetric {
    SCROLLS,
    MINUTES
}

@Composable
fun WeeklyAnalyticsCard(weeklyRecords: List<DailyRecord>) {
    var selectedMetric by remember { mutableStateOf(AnalyticsMetric.SCROLLS) }
    var selectedIndex by remember(weeklyRecords) {
        val todayIdx = weeklyRecords.indexOfFirst { it.isToday }.takeIf { it >= 0 } ?: (weeklyRecords.size - 1)
        mutableStateOf(todayIdx.coerceAtLeast(0))
    }

    val totalScrolls = weeklyRecords.sumOf { it.totalCount }
    val totalMinutes = weeklyRecords.sumOf { it.totalMinutes }
    val avgPerDay = if (weeklyRecords.isNotEmpty()) {
        if (selectedMetric == AnalyticsMetric.SCROLLS) totalScrolls / weeklyRecords.size
        else totalMinutes / weeklyRecords.size
    } else 0

    val maxMetricValue = weeklyRecords.maxOfOrNull {
        if (selectedMetric == AnalyticsMetric.SCROLLS) it.totalCount else it.totalMinutes
    }?.coerceAtLeast(1) ?: 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header with Metric Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "LAST 7 DAYS ACTIVITY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextTertiary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (selectedMetric == AnalyticsMetric.SCROLLS) "$totalScrolls scrolls • Avg $avgPerDay/day" else "${totalMinutes}m total • Avg ${avgPerDay}m/day",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Compact Metric toggle: Scrolls vs Minutes
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selectedMetric == AnalyticsMetric.SCROLLS) SurfaceCardSecondary else Color.Transparent)
                        .clickable { selectedMetric = AnalyticsMetric.SCROLLS }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Scrolls",
                        fontSize = 11.sp,
                        fontWeight = if (selectedMetric == AnalyticsMetric.SCROLLS) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selectedMetric == AnalyticsMetric.SCROLLS) TextPrimary else TextSecondary
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selectedMetric == AnalyticsMetric.MINUTES) SurfaceCardSecondary else Color.Transparent)
                        .clickable { selectedMetric = AnalyticsMetric.MINUTES }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Time",
                        fontSize = 11.sp,
                        fontWeight = if (selectedMetric == AnalyticsMetric.MINUTES) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selectedMetric == AnalyticsMetric.MINUTES) TextPrimary else TextSecondary
                    )
                }
            }
        }

        // Bar Chart area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            weeklyRecords.forEachIndexed { index, record ->
                val isSelected = index == selectedIndex
                val value = if (selectedMetric == AnalyticsMetric.SCROLLS) record.totalCount else record.totalMinutes
                val fillRatio = (value.toFloat() / maxMetricValue.toFloat()).coerceIn(0f, 1f)

                val animatedRatio by animateFloatAsState(
                    targetValue = fillRatio,
                    animationSpec = tween(durationMillis = 400),
                    label = "barHeight"
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { selectedIndex = index },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    // Value label on top
                    Text(
                        text = if (value > 0) (if (selectedMetric == AnalyticsMetric.SCROLLS) "$value" else "${value}m") else "",
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) TextPrimary else TextTertiary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // Vertical Bar Capsule
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height(84.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) SurfaceCardSecondary.copy(alpha = 0.8f) else SurfaceCard.copy(alpha = 0.6f)
                            )
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = if (isSelected) TextPrimary.copy(alpha = 0.5f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        if (value > 0) {
                            val instaPart = if (selectedMetric == AnalyticsMetric.SCROLLS) record.instagramCount else (record.instagramSeconds / 60)
                            val ytPart = if (selectedMetric == AnalyticsMetric.SCROLLS) record.youtubeCount else (record.youtubeSeconds / 60)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(animatedRatio.coerceAtLeast(0.08f))
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            instaPart > 0 && ytPart > 0 -> Brush.verticalGradient(
                                                listOf(YtColor, Color(0xFFFD1D1D), Color(0xFF833AB4))
                                            )
                                            ytPart > 0 -> Brush.verticalGradient(listOf(YtColor, Color(0xFFB91C1C)))
                                            else -> Brush.verticalGradient(InstaGradient)
                                        }
                                    )
                            )
                        } else {
                            // Empty day indicator (subtle 4dp dot)
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 6.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(TextTertiary.copy(alpha = 0.4f))
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Day of Week Label (e.g. "Mon")
                    Text(
                        text = record.dayLabel,
                        fontSize = 11.sp,
                        fontWeight = if (record.isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            record.isToday -> AccentGreen
                            isSelected -> TextPrimary
                            else -> TextSecondary
                        }
                    )

                    // Dot for today
                    if (record.isToday) {
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(3.dp)
                                .clip(CircleShape)
                                .background(AccentGreen)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(5.dp))
                    }
                }
            }
        }

        // Selected Day Details Banner
        val selectedRecord = weeklyRecords.getOrNull(selectedIndex)
        if (selectedRecord != null) {
            HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header Row: Day Label on left, Day Total on right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedRecord.isToday) "Today (${selectedRecord.dayLabel})" else "${selectedRecord.dayLabel} (${selectedRecord.date})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selectedRecord.isToday) AccentGreen else TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = "${selectedRecord.totalCount} scrolls • ${selectedRecord.totalMinutes}m",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Symmetrical Platform Cards (Reels & Shorts side-by-side)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Instagram Reels Card
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceCardSecondary)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Clean Badge Pill (Replacing awkward dot)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xFFFD1D1D).copy(alpha = 0.15f))
                                .border(0.8.dp, Color(0xFFFCB045).copy(alpha = 0.4f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 7.dp, vertical = 2.5.dp)
                        ) {
                            Text(
                                text = "REELS",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFCB045)
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        // Count
                        Text(
                            text = "${selectedRecord.instagramCount} reels",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Time (Consistently below count)
                        Text(
                            text = "${selectedRecord.instagramSeconds / 60}m active",
                            fontSize = 11.5.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // YouTube Shorts Card
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceCardSecondary)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Clean Badge Pill (Replacing awkward dot)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(YtColor.copy(alpha = 0.15f))
                                .border(0.8.dp, YtColor.copy(alpha = 0.4f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 7.dp, vertical = 2.5.dp)
                        ) {
                            Text(
                                text = "SHORTS",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = YtColor
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        // Count
                        Text(
                            text = "${selectedRecord.youtubeCount} shorts",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Time (Consistently below count)
                        Text(
                            text = "${selectedRecord.youtubeSeconds / 60}m active",
                            fontSize = 11.5.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatBox(
    modifier: Modifier = Modifier,
    label: String,
    badgeGradient: List<Color>,
    count: Int,
    timeMinutes: Int,
    limitRatio: Float,
    enforceLimits: Boolean,
    limitText: String
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(badgeGradient))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
            }

            if (limitText.isNotEmpty()) {
                Text(
                    text = limitText,
                    fontSize = 11.sp,
                    color = TextTertiary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "$count",
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                letterSpacing = (-1).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (label.contains("SHORT", ignoreCase = true)) "shorts" else "reels",
                fontSize = 12.sp,
                color = TextTertiary,
                modifier = Modifier.padding(bottom = 6.dp),
                maxLines = 1
            )
        }

        Text(
            text = "${timeMinutes}m active today",
            fontSize = 12.sp,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (enforceLimits) {
            val progress = limitRatio.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(SurfaceCardSecondary)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(if (progress >= 1f) AccentRed else TextPrimary)
                )
            }
        }
    }
}

// --- Segmented Mode Switcher ---
@Composable
fun ModeSelector(
    enforceLimits: Boolean,
    onModeChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
            .padding(3.dp)
    ) {
        val selectedModifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(9.dp))
            .background(SurfaceCardSecondary)
            .clickable { onModeChange(false) }
            .padding(vertical = 10.dp)

        val unselectedModifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(9.dp))
            .clickable { onModeChange(false) }
            .padding(vertical = 10.dp)

        // Count Only tab
        Box(
            modifier = if (!enforceLimits) selectedModifier else Modifier
                .weight(1f)
                .clip(RoundedCornerShape(9.dp))
                .clickable { onModeChange(false) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Count Only",
                fontSize = 13.sp,
                fontWeight = if (!enforceLimits) FontWeight.SemiBold else FontWeight.Normal,
                color = if (!enforceLimits) TextPrimary else TextSecondary
            )
        }

        // Enforce Limits tab
        Box(
            modifier = if (enforceLimits) Modifier
                .weight(1f)
                .clip(RoundedCornerShape(9.dp))
                .background(SurfaceCardSecondary)
                .clickable { onModeChange(true) }
                .padding(vertical = 10.dp)
            else Modifier
                .weight(1f)
                .clip(RoundedCornerShape(9.dp))
                .clickable { onModeChange(true) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Enforce Limits",
                fontSize = 13.sp,
                fontWeight = if (enforceLimits) FontWeight.SemiBold else FontWeight.Normal,
                color = if (enforceLimits) TextPrimary else TextSecondary
            )
        }
    }
}

// --- Limit Type Selector (Scrolls vs Minutes) ---
@Composable
fun LimitTypeSelector(
    currentMode: LimitMode,
    onModeSelected: (LimitMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "LIMIT CRITERIA",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextTertiary,
            letterSpacing = 1.sp
        )

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                .padding(2.dp)
        ) {
            val isScrolls = currentMode == LimitMode.SCROLLS

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isScrolls) SurfaceCardSecondary else Color.Transparent)
                    .clickable { onModeSelected(LimitMode.SCROLLS) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Scrolls",
                    fontSize = 12.sp,
                    fontWeight = if (isScrolls) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isScrolls) TextPrimary else TextSecondary
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (!isScrolls) SurfaceCardSecondary else Color.Transparent)
                    .clickable { onModeSelected(LimitMode.TIME_MINUTES) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Time (min)",
                    fontSize = 12.sp,
                    fontWeight = if (!isScrolls) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (!isScrolls) TextPrimary else TextSecondary
                )
            }
        }
    }
}

// --- Platform Row ---
@Composable
fun PlatformRow(
    name: String,
    accent: List<Color>,
    enabled: Boolean,
    count: Int,
    activeSeconds: Int,
    scrollLimit: Int,
    timeLimitMinutes: Int,
    enforceLimits: Boolean,
    limitMode: LimitMode,
    onToggle: (Boolean) -> Unit,
    onScrollLimitChange: (Int) -> Unit,
    onTimeLimitChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(accent))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val activeMins = activeSeconds / 60
                    Text(
                        text = "$count scrolls • ${activeMins}m today",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = BgOled,
                    checkedTrackColor = TextPrimary,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = SurfaceCardSecondary,
                    uncheckedBorderColor = BorderSubtle
                )
            )
        }

        // Stepper limit row (only when platform is enabled and limits are enforced)
        if (enabled && enforceLimits) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceCard)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val label = if (limitMode == LimitMode.SCROLLS) "Scroll Limit" else "Time Limit"
                val valueText = if (limitMode == LimitMode.SCROLLS) "$scrollLimit scrolls" else "$timeLimitMinutes min"

                Text(label, fontSize = 13.sp, color = TextSecondary)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Minus button
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(SurfaceCardSecondary)
                            .clickable {
                                if (limitMode == LimitMode.SCROLLS) {
                                    if (scrollLimit > 5) onScrollLimitChange(scrollLimit - 5)
                                } else {
                                    if (timeLimitMinutes > 5) onTimeLimitChange(timeLimitMinutes - 5)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("-", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = valueText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    // Plus button
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(SurfaceCardSecondary)
                            .clickable {
                                if (limitMode == LimitMode.SCROLLS) {
                                    onScrollLimitChange(scrollLimit + 5)
                                } else {
                                    onTimeLimitChange(timeLimitMinutes + 5)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// --- Quick Home Screen Widget Banner ---
@Composable
fun QuickWidgetBanner(
    onAddWidget: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .clickable { onAddWidget() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(SurfaceCardSecondary)
                    .border(1.dp, BorderSubtle, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Widgets,
                    contentDescription = "Widget",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Track on Home Screen",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Live counters • Resize for 7D analytics",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(TextPrimary)
                .clickable { onAddWidget() }
                .padding(horizontal = 11.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Add Widget",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = BgOled,
                maxLines = 1
            )
        }
    }
}

// --- Minimalist Toggle Row ---
@Composable
fun MinimalToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BgOled,
                checkedTrackColor = TextPrimary,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceCardSecondary,
                uncheckedBorderColor = BorderSubtle
            )
        )
    }
}

// --- Missing Permissions Banner (Ultra-compact) ---
@Composable
fun MissingPermissionsBanner(
    accessibilityGranted: Boolean,
    overlayGranted: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    var showRestrictedDialog by remember { mutableStateOf(false) }

    if (showRestrictedDialog) {
        AlertDialog(
            onDismissRequest = { showRestrictedDialog = false },
            containerColor = SurfaceCard,
            title = {
                Text(
                    text = "Fix 'App was denied access'",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "On Android 13, 14 & 15, Android automatically blocks permissions for sideloaded APKs and shows \"App was denied access\". You can unlock it in 10 seconds:",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceDark)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "1. Tap 'Open App Info' below.",
                            color = TextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "2. Tap the ⋮ (3 dots) in the top-right corner.",
                            color = TextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "3. Tap 'Allow restricted settings' and confirm with fingerprint or PIN.",
                            color = TextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "4. Return here and enable 'Display Over Other Apps' & 'Accessibility'.",
                            color = TextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestrictedDialog = false
                        onOpenAppInfo()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = BgOled)
                ) {
                    Text("Open App Info", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestrictedDialog = false }) {
                    Text("Got it", color = TextSecondary)
                }
            }
        )
    }

    var showAccessibilityDisclosure by remember { mutableStateOf(false) }

    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            containerColor = SurfaceCard,
            title = {
                Text(
                    text = "Accessibility Service Disclosure",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "To track scrolls and enforce screen limits, ScrollStop uses the Android Accessibility Service API.",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceDark)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "• Detection: Identifies when Instagram Reels or YouTube Shorts are actively displayed on screen.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "• Wellbeing Friction: Counts swipe interactions to display the HUD and alert when daily limits are met.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "• 100% Privacy Guarantee: Operates strictly locally on your device. Never reads passwords, personal messages, banking data, or screen content.",
                            color = AccentGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAccessibilityDisclosure = false
                        onOpenAccessibility()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = BgOled)
                ) {
                    Text("Agree & Continue", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDisclosure = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(1.dp, AccentAmber.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = AccentAmber,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Setup required to count scrolls",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!accessibilityGranted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceCard)
                    .clickable { showAccessibilityDisclosure = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = "Accessibility Service",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "ScrollStop Service ➔ Turn ON",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AccentAmber.copy(alpha = 0.15f))
                        .border(0.8.dp, AccentAmber.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "Enable",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentAmber,
                        maxLines = 1
                    )
                }
            }
        }

        if (!overlayGranted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceCard)
                    .clickable { onOpenOverlay() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = "Display Over Other Apps",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Required for on-screen counter badge",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AccentAmber.copy(alpha = 0.15f))
                        .border(0.8.dp, AccentAmber.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "Enable",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentAmber,
                        maxLines = 1
                    )
                }
            }
        }

        // Quick App Info helper if restricted setting on Android 13, 14, 15
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceCardSecondary.copy(alpha = 0.6f))
                .clickable { showRestrictedDialog = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Seeing 'App was denied access' or 'Restricted setting'?",
                fontSize = 11.5.sp,
                color = AccentAmber,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Fix ➔",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1
            )
        }
    }
}

// --- Helpers ---
fun checkAccessibilityPermission(context: Context): Boolean {
    val service = "${context.packageName}/${ScrollAccessibilityService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(service, ignoreCase = true)
}

fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}

fun openOverlaySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}

fun openAppInfo(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}

fun checkBatteryOptimization(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
}

fun requestBatteryOptimizationExemption(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}

fun requestPinWidget(
    context: Context,
    providerClass: Class<out AppWidgetProvider> = ScrollCounterWidget::class.java,
    onShowGuide: () -> Unit
) {
    val appWidgetManager = AppWidgetManager.getInstance(context)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
        val provider = ComponentName(context, providerClass)
        val successIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val successPendingIntent = PendingIntent.getActivity(
            context,
            0,
            successIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            val pinned = appWidgetManager.requestPinAppWidget(provider, null, successPendingIntent)
            if (!pinned) {
                onShowGuide()
            }
        } catch (_: Exception) {
            onShowGuide()
        }
    } else {
        onShowGuide()
    }
}
