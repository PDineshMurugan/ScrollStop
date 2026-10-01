package com.scrollcounter.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.scrollcounter.app.BuildConfig
import com.scrollcounter.app.updater.AppReleaseInfo
import com.scrollcounter.app.updater.UpdateManager
import com.scrollcounter.app.updater.UpdateStatus
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var showResetConfirm by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val updateStatus by UpdateManager.status.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }

    // Check for updates automatically in the background
    LaunchedEffect(Unit) {
        UpdateManager.checkForUpdates(force = false)
    }

    DisposableEffect(Unit) {
        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = checkAccessibilityPermission(context)
                isOverlayEnabled = Settings.canDrawOverlays(context)
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
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
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
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Tap to review & download the new APK",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                        Text(
                            text = "Update",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentGreen
                        )
                    }
                }
            }

            // --- Today's Hero Stats ---
            HeroMetricsGrid(settings = settings)

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
                        is UpdateStatus.UpToDate -> "Latest version installed • Build ${BuildConfig.VERSION_CODE}"
                        is UpdateStatus.Error -> "Tap to check for updates"
                        is UpdateStatus.Idle -> "Build ${BuildConfig.VERSION_CODE} • Tap to check for updates"
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

                // 2. Report Bug / Issue
                CommunityRow(
                    title = "Report a Bug",
                    subtitle = "Found an issue or false scroll? Open a GitHub issue",
                    badgeText = "Issues",
                    badgeColor = AccentAmber,
                    icon = Icons.Default.BugReport,
                    onClick = {
                        UpdateManager.openBrowser(context, UpdateManager.GITHUB_BUG_REPORT_URL)
                    }
                )

                HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)

                // 3. Request Feature
                CommunityRow(
                    title = "Request a Feature",
                    subtitle = "Suggest new platform support (TikTok, etc.)",
                    badgeText = "Ideas",
                    badgeColor = TextPrimary,
                    icon = Icons.Default.Lightbulb,
                    onClick = {
                        UpdateManager.openBrowser(context, UpdateManager.GITHUB_FEATURE_REQUEST_URL)
                    }
                )

                HorizontalDivider(color = BorderSubtle, thickness = 0.8.dp)

                // 4. GitHub Repository
                CommunityRow(
                    title = "GitHub Repository",
                    subtitle = "Star the project, inspect source, or contribute",
                    badgeText = "GitHub",
                    badgeColor = TextSecondary,
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = {
                        UpdateManager.openBrowser(context, UpdateManager.GITHUB_REPO_URL)
                    }
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
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
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1
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
                    color = badgeColor
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
                letterSpacing = (-1).sp
            )
            Text(
                text = if (label.contains("SHORT", ignoreCase = true)) "shorts" else "reels",
                fontSize = 12.sp,
                color = TextTertiary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        Text(
            text = "${timeMinutes}m active today",
            fontSize = 12.sp,
            color = TextSecondary
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(accent))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    val activeMins = activeSeconds / 60
                    Text(
                        text = "$count scrolls • ${activeMins}m today",
                        fontSize = 12.sp,
                        color = TextSecondary
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
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = TextSecondary)
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
                color = TextPrimary
            )
        }

        if (!accessibilityGranted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceCard)
                    .clickable { onOpenAccessibility() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Accessibility Service", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text("Installed apps ➔ ScrollStop Service ➔ Turn ON", fontSize = 11.sp, color = TextSecondary)
                }
                Text("Enable", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AccentAmber)
            }

            // Quick App Info helper if restricted setting
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Button greyed out ('Restricted setting')?",
                    fontSize = 11.sp,
                    color = TextTertiary
                )
                Text(
                    text = "App Info ➔ ⋮ ➔ Allow",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    modifier = Modifier.clickable { onOpenAppInfo() }
                )
            }
        }

        if (!overlayGranted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceCard)
                    .clickable { onOpenOverlay() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Display Over Other Apps", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text("Required for on-screen counter badge", fontSize = 11.sp, color = TextSecondary)
                }
                Text("Enable", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AccentAmber)
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
