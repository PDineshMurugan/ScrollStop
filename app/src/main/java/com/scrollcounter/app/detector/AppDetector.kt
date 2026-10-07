package com.scrollcounter.app.detector

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

enum class TargetPlatform {
    INSTAGRAM_REELS,
    YOUTUBE_SHORTS,
    NONE
}

data class DetectionResult(
    val platform: TargetPlatform,
    val isNewContentScrolled: Boolean,
    val currentSignature: String? = null
)

data class ValidationResult(
    val isNewContentScrolled: Boolean,
    val shouldRetry: Boolean = false,
    val isStillInTarget: Boolean = true,
    val signature: String? = null
)

class AppDetector {

    private var lastInstagramSignature: String? = null
    private var lastInstagramCountTime: Long = 0L
    private var isInstagramBaselinePending: Boolean = true

    private var lastYouTubeSignature: String? = null
    private var lastYouTubeCountTime: Long = 0L
    private var isYouTubeBaselinePending: Boolean = true

    companion object {
        const val TAG = "ScrollCounter"
        const val PACKAGE_INSTAGRAM = "com.instagram.android"
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"

        // Minimum time between distinct video signature transitions (280ms)
        private const val MIN_DISTINCT_SIGNATURE_INTERVAL_MS = 280L
    }

    // ==========================================
    // PLATFORM DETECTION
    // ==========================================

    fun detectPlatform(packageName: String, rootNode: AccessibilityNodeInfo): TargetPlatform {
        if (packageName == PACKAGE_INSTAGRAM) {
            val scan = scanInstagram(rootNode)
            return if (scan.isReels) TargetPlatform.INSTAGRAM_REELS else TargetPlatform.NONE
        }
        if (packageName == PACKAGE_YOUTUBE) {
            val scan = scanYouTube(rootNode)
            return if (scan.isShorts) TargetPlatform.YOUTUBE_SHORTS else TargetPlatform.NONE
        }
        return TargetPlatform.NONE
    }

    fun isWaitingBaseline(): Boolean = isInstagramBaselinePending || isYouTubeBaselinePending

    fun isInstagramReelsActive(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        return scanInstagram(rootNode).isReels
    }

    fun isYouTubeShortsActive(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        return scanYouTube(rootNode).isShorts
    }

    // ==========================================
    // BASELINE ESTABLISHMENT
    // ==========================================

    fun establishBaseline(platform: TargetPlatform, rootNode: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        when (platform) {
            TargetPlatform.INSTAGRAM_REELS -> {
                val scan = scanInstagram(rootNode)
                lastInstagramSignature = scan.signature
                lastInstagramCountTime = now
                isInstagramBaselinePending = scan.signature == null
                Log.d(TAG, "[Instagram] Baseline established: ${scan.signature}")
            }
            TargetPlatform.YOUTUBE_SHORTS -> {
                val scan = scanYouTube(rootNode)
                lastYouTubeSignature = scan.signature
                lastYouTubeCountTime = now
                isYouTubeBaselinePending = scan.signature == null
                Log.d(TAG, "[YouTube] Baseline established: ${scan.signature}")
            }
            TargetPlatform.NONE -> {}
        }
    }

    // ==========================================
    // VALIDATION SCAN (Run once after settling)
    // ==========================================

    fun validateTransition(
        platform: TargetPlatform,
        rootNode: AccessibilityNodeInfo,
        isRetry: Boolean
    ): ValidationResult {
        val now = System.currentTimeMillis()

        when (platform) {
            TargetPlatform.INSTAGRAM_REELS -> {
                val scan = scanInstagram(rootNode)
                if (!scan.isReels) {
                    return ValidationResult(isNewContentScrolled = false, isStillInTarget = false)
                }
                // If comments or share sheet is open, user is scrolling comments -> never count!
                if (scan.isCommentsOrShareOpen) {
                    return ValidationResult(isNewContentScrolled = false, isStillInTarget = true)
                }

                val signature = scan.signature
                if (signature == null) {
                    // Identity not yet visible in accessibility tree
                    if (!isRetry) {
                        return ValidationResult(isNewContentScrolled = false, shouldRetry = true, isStillInTarget = true)
                    }
                    // After retry, still no identity -> avoid phantom +1
                    return ValidationResult(isNewContentScrolled = false, shouldRetry = false, isStillInTarget = true)
                }

                // If baseline was still pending (e.g. video was slow to load upon entering):
                if (isInstagramBaselinePending) {
                    lastInstagramSignature = signature
                    lastInstagramCountTime = 0L // Allow immediate subsequent swipe
                    isInstagramBaselinePending = false
                    Log.d(TAG, "[Instagram] Baseline resolved on initial Reel: $signature")
                    return ValidationResult(isNewContentScrolled = false, isStillInTarget = true, signature = signature)
                }

                val lastSig = lastInstagramSignature
                if (signature != lastSig) {
                    val elapsed = now - lastInstagramCountTime
                    if (elapsed >= MIN_DISTINCT_SIGNATURE_INTERVAL_MS) {
                        lastInstagramSignature = signature
                        lastInstagramCountTime = now
                        Log.i(TAG, "[Instagram] Validated Reel scroll ($elapsed ms): $signature")
                        return ValidationResult(isNewContentScrolled = true, isStillInTarget = true, signature = signature)
                    }
                }
                return ValidationResult(isNewContentScrolled = false, isStillInTarget = true, signature = signature)
            }

            TargetPlatform.YOUTUBE_SHORTS -> {
                val scan = scanYouTube(rootNode)
                if (!scan.isShorts) {
                    return ValidationResult(isNewContentScrolled = false, isStillInTarget = false)
                }
                if (scan.isCommentsOpen) {
                    return ValidationResult(isNewContentScrolled = false, isStillInTarget = true)
                }

                val signature = scan.signature
                if (signature == null) {
                    if (!isRetry) {
                        return ValidationResult(isNewContentScrolled = false, shouldRetry = true, isStillInTarget = true)
                    }
                    return ValidationResult(isNewContentScrolled = false, shouldRetry = false, isStillInTarget = true)
                }

                if (isYouTubeBaselinePending) {
                    lastYouTubeSignature = signature
                    lastYouTubeCountTime = 0L
                    isYouTubeBaselinePending = false
                    Log.d(TAG, "[YouTube] Baseline resolved on initial Short: $signature")
                    return ValidationResult(isNewContentScrolled = false, isStillInTarget = true, signature = signature)
                }

                val lastSig = lastYouTubeSignature
                if (signature != lastSig) {
                    val elapsed = now - lastYouTubeCountTime
                    if (elapsed >= MIN_DISTINCT_SIGNATURE_INTERVAL_MS) {
                        lastYouTubeSignature = signature
                        lastYouTubeCountTime = now
                        Log.i(TAG, "[YouTube] Validated Short scroll ($elapsed ms): $signature")
                        return ValidationResult(isNewContentScrolled = true, isStillInTarget = true, signature = signature)
                    }
                }
                return ValidationResult(isNewContentScrolled = false, isStillInTarget = true, signature = signature)
            }

            TargetPlatform.NONE -> {
                return ValidationResult(isNewContentScrolled = false, isStillInTarget = false)
            }
        }
    }

    // ==========================================
    // INSTAGRAM TREE SCANNER
    // ==========================================

    private data class InstagramScan(
        val isReels: Boolean,
        val isCommentsOrShareOpen: Boolean,
        val signature: String?
    )

    private fun scanInstagram(rootNode: AccessibilityNodeInfo): InstagramScan {
        var isFeedTabSelected = false
        var isReelsTabSelected = false
        var isSearchTabSelected = false
        var isProfileTabSelected = false
        var hasClipsViewer = false
        var hasInboxList = false
        var isCommentsOrShareOpen = false

        var authorFound: String? = null
        var audioFound: String? = null
        var captionFound: String? = null

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 16) return

            val resId = node.viewIdResourceName.orEmpty()
            val desc = node.contentDescription?.toString().orEmpty().trim()
            val text = node.text?.toString().orEmpty().trim()

            // Skip irrelevant large subtrees early to save battery and IPC calls
            if (resId.contains("stories_tray", true) ||
                resId.contains("feed_recycler", true) ||
                resId.contains("thread_recycler", true)
            ) {
                if (resId.contains("thread_recycler", true)) hasInboxList = true
                return
            }

            // 1. Navigation bar tabs
            if (resId.contains("clips_tab", true) || desc.equals("Reels", true) || desc.contains("Reels", true) || text.equals("Reels", true)) {
                if (node.isSelected) isReelsTabSelected = true
                for (c in 0 until node.childCount) {
                    if (node.getChild(c)?.isSelected == true) isReelsTabSelected = true
                }
            }
            if (resId.contains("feed_tab", true) || desc.equals("Home", true)) {
                if (node.isSelected) isFeedTabSelected = true
                for (c in 0 until node.childCount) {
                    if (node.getChild(c)?.isSelected == true) isFeedTabSelected = true
                }
            }
            if (resId.contains("search_tab", true) || desc.equals("Search and explore", true) || desc.equals("Search", true)) {
                if (node.isSelected) isSearchTabSelected = true
                for (c in 0 until node.childCount) {
                    if (node.getChild(c)?.isSelected == true) isSearchTabSelected = true
                }
            }
            if (resId.contains("profile_tab", true) || desc.equals("Profile", true)) {
                if (node.isSelected) isProfileTabSelected = true
                for (c in 0 until node.childCount) {
                    if (node.getChild(c)?.isSelected == true) isProfileTabSelected = true
                }
            }

            // 2. Direct Messages Inbox detection
            if (resId.contains("inbox_refreshable", true) ||
                resId.contains("direct_inbox", true) ||
                resId.contains("direct_quick_snap", true)
            ) {
                hasInboxList = true
            }

            // 3. Fullscreen clips/reels viewer container
            if (resId.contains("clips_video_container", true) ||
                resId.contains("clips_item_container", true) ||
                resId.contains("layout_clips_viewer", true) ||
                resId.contains("clips_viewpager", true) ||
                resId.contains("clips_swipe_refresh_layout", true) ||
                resId.contains("reel_viewer", true) ||
                resId.contains("unified_video_container", true) ||
                resId.contains("clips_author", true) ||
                resId.contains("clips_action_bar", true) ||
                resId.contains("clips_like_button", true) ||
                resId.contains("clips_comment_button", true) ||
                resId.contains("clips_share_button", true) ||
                desc.contains("Reel by ", true) ||
                desc.contains("Like reel", true)
            ) {
                hasClipsViewer = true
            }

            // 4. Comments or share sheet open
            if (resId.contains("comment_composer", true) ||
                resId.contains("layout_comment_thread", true) ||
                resId.contains("bottom_sheet_container", true) ||
                resId.contains("share_sheet", true) ||
                resId.contains("clips_comments", true) ||
                resId.contains("comments_recycler_view", true) ||
                resId.contains("direct_share_sheet", true)
            ) {
                isCommentsOrShareOpen = true
                return
            }

            // 5. Collect video signature components (Author, Audio, Caption)
            if (authorFound == null) {
                if (desc.startsWith("Reel by ", ignoreCase = true) || desc.contains("Reel by ", ignoreCase = true)) {
                    val creator = if (desc.startsWith("Reel by ", ignoreCase = true)) {
                        desc.substring(8).split(".")[0].trim()
                    } else {
                        desc.substringAfter("Reel by ").split(".")[0].trim()
                    }
                    if (creator.isNotEmpty()) authorFound = creator
                } else if (desc.contains("profile picture", true)) {
                    val authorName = desc.replace("profile picture", "", ignoreCase = true)
                        .replace("Profile picture of", "", ignoreCase = true)
                        .replace("'s", "", ignoreCase = true)
                        .trim()
                    if (authorName.length in 2..40 && !authorName.contains(" ")) {
                        authorFound = authorName
                    }
                } else if (resId.contains("clips_author", true) ||
                    resId.contains("clips_user_name", true) ||
                    resId.contains("author_name", true) ||
                    resId.contains("clips_user", true) ||
                    resId.contains("user_name", true)
                ) {
                    val key = text.ifEmpty { desc }
                    if (key.isNotEmpty()) authorFound = key
                }
            }

            if (captionFound == null) {
                if (resId.contains("clips_caption", true) ||
                    resId.contains("caption_text_view", true) ||
                    resId.contains("clips_subtitles", true)
                ) {
                    val key = text.ifEmpty { desc }
                    if (key.length >= 3) {
                        captionFound = key.take(35)
                    }
                }
            }

            if (audioFound == null) {
                if (resId.contains("audio_title", true) ||
                    resId.contains("music_title", true) ||
                    resId.contains("audio_track", true) ||
                    desc.contains("Original audio", true) ||
                    desc.contains("Audio by", true) ||
                    text.contains("Original audio", true) ||
                    text.contains("Audio by", true)
                ) {
                    val key = text.ifEmpty { desc }
                    if (key.isNotEmpty()) audioFound = key
                }
            }

            // Early exit once clips viewer and at least 2 distinct components are discovered
            if (hasClipsViewer && authorFound != null && (captionFound != null || audioFound != null)) {
                return
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1)
            }
        }

        traverse(rootNode, 0)

        val isNonReelsSection = !hasClipsViewer && (isFeedTabSelected || isSearchTabSelected || isProfileTabSelected || hasInboxList)
        val isReels = (hasClipsViewer || isReelsTabSelected) && !isNonReelsSection

        val signature = when {
            authorFound != null && captionFound != null -> "$authorFound::$captionFound"
            authorFound != null && audioFound != null -> "$authorFound::$audioFound"
            authorFound != null -> authorFound
            captionFound != null -> captionFound
            audioFound != null -> audioFound
            else -> null
        }

        return InstagramScan(isReels, isCommentsOrShareOpen, signature)
    }

    // ==========================================
    // YOUTUBE TREE SCANNER
    // ==========================================

    private data class YouTubeScan(
        val isShorts: Boolean,
        val isCommentsOpen: Boolean,
        val signature: String?
    )

    private fun scanYouTube(rootNode: AccessibilityNodeInfo): YouTubeScan {
        var isShortsTabSelected = false
        var hasShortsContainer = false
        var isNormalWatchPlayer = false
        var isCommentsOpen = false

        val candidates = mutableListOf<String>()

        val screenBounds = Rect()
        rootNode.getBoundsInScreen(screenBounds)
        val hasScreenBounds = screenBounds.height() > 0
        val screenHeight = screenBounds.height().coerceAtLeast(1000)
        val topLimit = (screenHeight * 0.10).toInt()
        val bottomLimit = (screenHeight * 0.90).toInt()
        val nodeBounds = Rect()

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 14) return

            val resId = node.viewIdResourceName.orEmpty()
            val desc = node.contentDescription?.toString().orEmpty().trim()
            val text = node.text?.toString().orEmpty().trim()

            // 1. Shorts bottom bar tab
            if (node.isSelected && (desc.equals("Shorts", true) || text.equals("Shorts", true))) {
                isShortsTabSelected = true
            }

            // 2. Shorts video container
            if (resId.contains("reel_recycler", true) ||
                resId.contains("reel_watch_fragment", true) ||
                resId.contains("reel_player", true) ||
                resId.contains("reel_watch_player", true) ||
                resId.contains("shorts_container", true) ||
                resId.contains("shorts_player", true) ||
                resId.contains("reel_time_bar", true) ||
                resId.contains("reel_scrim", true)
            ) {
                hasShortsContainer = true
            }

            // 3. Distinguish normal long-form player vs Shorts
            if ((resId.endsWith(":id/watch_player", true) || resId.contains("watch_media_player", true)) &&
                !resId.contains("reel", true)
            ) {
                isNormalWatchPlayer = true
            }

            // 4. Comments panel open
            if (resId.contains("engagement_panel", true) ||
                resId.contains("comment", true) ||
                resId.contains("bottom_sheet_container", true) ||
                resId.contains("panel_header", true) ||
                resId.contains("description_panel", true)
            ) {
                isCommentsOpen = true
                return
            }

            // 5. Collect signature components (Channel, Title, Sound)
            if (candidates.size < 3) {
                node.getBoundsInScreen(nodeBounds)
                val centerY = nodeBounds.centerY()
                val inCenter = if (hasScreenBounds) centerY in topLimit..bottomLimit else true
                if (inCenter) {
                    if (desc.contains("@") && (desc.startsWith("Subscribe", true) || desc.startsWith("Go to channel", true))) {
                        if (!candidates.contains(desc)) candidates.add(desc)
                    } else if (text.startsWith("@") && text.length > 1) {
                        if (!candidates.contains(text)) candidates.add(text)
                    } else if (resId.contains("channel_name", true) || resId.contains("reel_channel_name", true)) {
                        val key = text.ifEmpty { desc }
                        if (key.isNotEmpty() && !candidates.contains(key)) candidates.add(key)
                    } else if (resId.contains("reel_player_title", true) ||
                        resId.contains("video_title", true) ||
                        resId.contains("title_text", true)
                    ) {
                        val key = text.ifEmpty { desc }
                        if (key.length >= 3) {
                            val snippet = key.take(35)
                            if (!candidates.contains(snippet)) candidates.add(snippet)
                        }
                    } else if (desc.contains("using this sound", true) || desc.contains("original sound", true)) {
                        if (!candidates.contains(desc)) candidates.add(desc)
                    }
                }
            }

            if (candidates.size >= 2 && (hasShortsContainer || isShortsTabSelected)) {
                return
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1)
            }
        }

        traverse(rootNode, 0)

        val isShorts = (isShortsTabSelected || hasShortsContainer) && !isNormalWatchPlayer
        val signature = if (candidates.isNotEmpty()) candidates.joinToString("::") else null

        return YouTubeScan(isShorts, isCommentsOpen, signature)
    }

    // ==========================================
    // LEGACY / UNIT-TEST COMPATIBILITY API
    // ==========================================

    fun analyzeEvent(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo?): DetectionResult {
        val pkg = event.packageName?.toString().orEmpty()
        val nodeToScan = rootNode ?: event.source ?: return DetectionResult(TargetPlatform.NONE, false)

        val platform = detectPlatform(pkg, nodeToScan)
        if (platform == TargetPlatform.NONE) {
            return DetectionResult(TargetPlatform.NONE, false)
        }

        val now = System.currentTimeMillis()

        when (platform) {
            TargetPlatform.INSTAGRAM_REELS -> {
                val scan = scanInstagram(nodeToScan)
                if (!scan.isReels) return DetectionResult(TargetPlatform.NONE, false)
                if (scan.isCommentsOrShareOpen) return DetectionResult(platform, isNewContentScrolled = false)

                val signature = scan.signature
                if (isInstagramBaselinePending) {
                    lastInstagramSignature = signature
                    lastInstagramCountTime = 0L
                    isInstagramBaselinePending = false
                    return DetectionResult(platform, isNewContentScrolled = false, currentSignature = signature)
                }

                if (signature != null && signature != lastInstagramSignature) {
                    if (now - lastInstagramCountTime >= MIN_DISTINCT_SIGNATURE_INTERVAL_MS) {
                        lastInstagramSignature = signature
                        lastInstagramCountTime = now
                        return DetectionResult(platform, isNewContentScrolled = true, currentSignature = signature)
                    }
                }
                return DetectionResult(platform, isNewContentScrolled = false, currentSignature = signature)
            }

            TargetPlatform.YOUTUBE_SHORTS -> {
                val scan = scanYouTube(nodeToScan)
                if (!scan.isShorts) return DetectionResult(TargetPlatform.NONE, false)
                if (scan.isCommentsOpen) return DetectionResult(platform, isNewContentScrolled = false)

                val signature = scan.signature
                val isScrollEvent = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED

                if (isYouTubeBaselinePending && !isScrollEvent) {
                    lastYouTubeSignature = signature
                    lastYouTubeCountTime = 0L
                    isYouTubeBaselinePending = false
                    return DetectionResult(platform, isNewContentScrolled = false, currentSignature = signature)
                }

                if (signature != null && signature != lastYouTubeSignature) {
                    if (now - lastYouTubeCountTime >= MIN_DISTINCT_SIGNATURE_INTERVAL_MS) {
                        lastYouTubeSignature = signature
                        lastYouTubeCountTime = now
                        isYouTubeBaselinePending = false
                        return DetectionResult(platform, isNewContentScrolled = true, currentSignature = signature)
                    }
                } else if (isScrollEvent && (now - lastYouTubeCountTime >= 600L)) {
                    lastYouTubeSignature = signature
                    lastYouTubeCountTime = now
                    isYouTubeBaselinePending = false
                    return DetectionResult(platform, isNewContentScrolled = true, currentSignature = signature)
                }

                return DetectionResult(platform, isNewContentScrolled = false, currentSignature = signature)
            }

            TargetPlatform.NONE -> return DetectionResult(TargetPlatform.NONE, false)
        }
    }

    fun resetState() {
        lastInstagramSignature = null
        lastInstagramCountTime = 0L
        isInstagramBaselinePending = true

        lastYouTubeSignature = null
        lastYouTubeCountTime = 0L
        isYouTubeBaselinePending = true
    }
}
