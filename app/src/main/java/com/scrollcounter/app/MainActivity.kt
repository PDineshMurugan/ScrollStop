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

    Scaffold(
        containerColor = BgOled
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // --- Minimalist Header ---
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
                            text = if (isAccessibilityEnabled && isOverlayEnabled) "Active" else "Action needed",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )
                    }
                }

                IconButton(
                    onClick = { showResetConfirm = true },
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

            // --- Permissions Alert (Only shown if setup is missing) ---
            if (!isAccessibilityEnabled || !isOverlayEnabled) {
                MissingPermissionsBanner(
                    accessibilityGranted = isAccessibilityEnabled,
                    overlayGranted = isOverlayEnabled,
                    onOpenAccessibility = { openAccessibilitySettings(context) },
                    onOpenOverlay = { openOverlaySettings(context) },
                    onOpenAppInfo = { openAppInfo(context) }
                )
            }

            // --- Samsung / Android Battery Exemption (Only shown if optimized) ---
            if (!isBatteryIgnored && isAccessibilityEnabled && isOverlayEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                        .clickable { requestBatteryOptimizationExemption(context) }
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
                                text = "Prevents missing counts on Samsung",
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

            // --- Update Available Notification Banner ---
            AnimatedVisibility(visible = updateStatus is UpdateStatus.UpdateAvailable) {
                val release = (updateStatus as? UpdateStatus.UpdateAvailable)?.release
                if (release != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(AccentGreen.copy(alpha = 0.12f))
                            .border(1.dp, AccentGreen.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            .clickable { showUpdateDialog = true }
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

            // --- Today's Hero Stats ---
            HeroMetricsGrid(settings = settings)

            // --- Last 7 Days Weekly Activity Bar Chart ---
            WeeklyAnalyticsCard(weeklyRecords = settings.weeklyRecords)

            // --- Quick Home Screen Widget Banner ---
            QuickWidgetBanner(
                onAddWidget = {
                    showWidgetChooserDialog = true
                }
            )

            // --- Mode Segmented Switch (Count vs Enforce) ---
            ModeSelector(
                enforceLimits = settings.enforceLimits,
                onModeChange = { prefs.updateEnforceLimits(it) }
            )

            // --- Limits Config (Only if limits are enabled) ---
            AnimatedVisibility(visible = settings.enforceLimits) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LimitTypeSelector(
                        currentMode = settings.limitMode,
                        onModeSelected = { prefs.updateLimitMode(it) }
                    )
                }
            }

            // --- Platforms Section ---
            Text(
                text = "PLATFORMS",
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
                // Instagram Reels Row
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
                    onToggle = { prefs.updateInstagramEnabled(it) },
                    onScrollLimitChange = { prefs.updateInstagramScrollLimit(it) },
                    onTimeLimitChange = { prefs.updateInstagramTimeLimit(it) }
                )

                HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)

                // YouTube Shorts Row
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
                    onToggle = { prefs.updateYouTubeEnabled(it) },
                    onScrollLimitChange = { prefs.updateYouTubeScrollLimit(it) },
                    onTimeLimitChange = { prefs.updateYouTubeTimeLimit(it) }
                )
            }

            // --- Enforcement Settings (Only when limits are enabled) ---
            if (settings.enforceLimits) {
                Text(
                    text = "BEHAVIOR",
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
                        onCheckedChange = { prefs.updateCloseAppOnLimit(it) }
                    )
                    HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)
                    MinimalToggleRow(
                        title = "Mindful Break Screen",
                        subtitle = "Full-screen pause notice before exit",
                        checked = settings.showBlockingOverlay,
                        onCheckedChange = { prefs.updateShowBlockingOverlay(it) }
                    )
                }
            }

            // --- Community & Updates Section ---
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
                // 1. Version & Update check row
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
                    onClick = {
                        if (updateStatus is UpdateStatus.UpdateAvailable) {
                            showUpdateDialog = true
                        } else {
                            coroutineScope.launch {
                                UpdateManager.checkForUpdates(force = true)
                            }
                        }
                    }
                )

                HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)

                // 2. GitHub Repository
                CommunityRow(
                    title = "GitHub Repository",
                    subtitle = "Star the project & view source",
                    badgeText = "GitHub",
                    badgeColor = TextSecondary,
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = {
                        UpdateManager.openBrowser(context, UpdateManager.GITHUB_REPO_URL)
                    }
                )

                HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)

                // 3. Report Bug / Issue
                CommunityRow(
                    title = "Report a Bug",
                    subtitle = "Found an issue? Open on GitHub",
                    badgeText = "Issues",
                    badgeColor = AccentAmber,
                    icon = Icons.Default.BugReport,
                    onClick = {
                        UpdateManager.openBrowser(context, UpdateManager.GITHUB_BUG_REPORT_URL)
                    }
                )

                HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)

                // 4. Request Feature
                CommunityRow(
                    title = "Request a Feature",
                    subtitle = "Suggest new platforms & ideas",
                    badgeText = "Ideas",
                    badgeColor = TextPrimary,
                    icon = Icons.Default.Lightbulb,
                    onClick = {
                        UpdateManager.openBrowser(context, UpdateManager.GITHUB_FEATURE_REQUEST_URL)
                    }
                )

                HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)

                // 5. Home Screen Widget
                CommunityRow(
                    title = "Home Screen Widget",
                    subtitle = "Quick Counter (2×2) or Weekly Analytics (3×4)",
                    badgeText = "2 Styles",
                    badgeColor = AccentGreen,
                    icon = Icons.Default.Widgets,
                    onClick = {
                        showWidgetChooserDialog = true
                    }
                )
            }

            // --- Support Creator (Buy Me a Chai) Banner ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .border(1.dp, AccentAmber.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .clickable { UpdateManager.openBrowser(context, UpdateManager.BUY_ME_A_CHAI_URL) }
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
                        .clickable { UpdateManager.openBrowser(context, UpdateManager.BUY_ME_A_CHAI_URL) }
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
                        Text(
                            text = "☕",
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // --- Clean Footer Signature ---
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
                    .clickable { onOpenAccessibility() }
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

            // Quick App Info helper if restricted setting
            var showRestrictedDialog by remember { mutableStateOf(false) }

            if (showRestrictedDialog) {
                AlertDialog(
                    onDismissRequest = { showRestrictedDialog = false },
                    containerColor = SurfaceCard,
                    title = {
                        Text(
                            text = "Unblock 'Restricted setting'",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Android 13+ restricts accessibility for sideloaded apps. You can unlock it in 10 seconds:",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "1. Tap 'Open App Info' below.\n2. Tap the ⋮ (3 dots) in the top-right corner.\n3. Tap 'Allow restricted settings' and confirm.\n4. Return here and enable ScrollStop Service.",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
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
                    text = "Restricted setting blocking you?",
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
