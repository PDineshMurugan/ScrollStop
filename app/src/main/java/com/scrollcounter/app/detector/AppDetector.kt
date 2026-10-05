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

class AppDetector {

    private var lastInstagramSignature: String? = null
    private var lastInstagramCountTime: Long = 0L
    private var isInstagramAnonymousPending: Boolean = false
    private var lastInstagramAnonymousTime: Long = 0L
    private var isInstagramBaselinePending: Boolean = true

    private var lastYouTubeSignature: String? = null
    private var lastYouTubeCountTime: Long = 0L
    private var isYouTubeAnonymousPending: Boolean = false
    private var lastYouTubeAnonymousTime: Long = 0L
    private var isYouTubeBaselinePending: Boolean = true

    companion object {
        const val TAG = "ScrollCounter"
        const val PACKAGE_INSTAGRAM = "com.instagram.android"
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"

        // Minimum time between distinct video signature transitions (240ms).
        // Allows rapid swiping (up to 4 videos/sec) while completely eliminating duplicate
        // events/flicker from the same swipe.
        private const val MIN_DISTINCT_SIGNATURE_INTERVAL_MS = 240L

        // Minimum interval between fallback (non-signature) scroll counts (500ms).
        private const val MIN_FALLBACK_SCROLL_INTERVAL_MS = 500L

        // Grace period (850ms) to adopt a late-loading signature after an anonymous scroll
        // without double counting.
        private const val ANONYMOUS_ADOPTION_WINDOW_MS = 850L
    }

    fun analyzeEvent(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo?): DetectionResult {
        val pkg = event.packageName?.toString().orEmpty()

        if (pkg == PACKAGE_INSTAGRAM) {
            return analyzeInstagram(event, rootNode)
        }
        if (pkg == PACKAGE_YOUTUBE) {
            return analyzeYouTube(event, rootNode)
        }

        return DetectionResult(TargetPlatform.NONE, false)
    }

    // ==========================================
    // INSTAGRAM REELS (Single-pass, ultra-fast)
    // ==========================================

    fun isWaitingBaseline(): Boolean = isInstagramBaselinePending || isYouTubeBaselinePending

    private data class InstagramScan(
        val isReels: Boolean,
        val isCommentsOrShareOpen: Boolean,
        val signature: String?
    )

    private fun analyzeInstagram(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo?): DetectionResult {
        val nodeToScan = rootNode ?: event.source ?: return DetectionResult(TargetPlatform.NONE, false)

        val scan = scanInstagram(nodeToScan)
        Log.d(TAG, "[Instagram] isReels=${scan.isReels}, isComments=${scan.isCommentsOrShareOpen}, sig=${scan.signature}")
        if (!scan.isReels) {
            return DetectionResult(TargetPlatform.NONE, false)
        }

        // If user is reading comments or has opened the share/remix sheet, DO NOT count scrolls!
        if (scan.isCommentsOrShareOpen) {
            return DetectionResult(
                platform = TargetPlatform.INSTAGRAM_REELS,
                isNewContentScrolled = false,
                currentSignature = lastInstagramSignature
            )
        }

        val now = System.currentTimeMillis()
        val signature = scan.signature

        return processPlatformTransition(
            platform = TargetPlatform.INSTAGRAM_REELS,
            event = event,
            signature = signature,
            now = now,
            getLastSignature = { lastInstagramSignature },
            setLastSignature = { lastInstagramSignature = it },
            getLastCountTime = { lastInstagramCountTime },
            setLastCountTime = { lastInstagramCountTime = it },
            getIsAnonymousPending = { isInstagramAnonymousPending },
            setIsAnonymousPending = { isInstagramAnonymousPending = it },
            getLastAnonymousTime = { lastInstagramAnonymousTime },
            setLastAnonymousTime = { lastInstagramAnonymousTime = it },
            getIsBaselinePending = { isInstagramBaselinePending },
            setIsBaselinePending = { isInstagramBaselinePending = it }
        )
    }

    private fun scanInstagram(rootNode: AccessibilityNodeInfo): InstagramScan {
        var isFeedTabSelected = false
        var isReelsTabSelected = false
        var hasClipsViewer = false
        var hasInboxList = false
        var hasHomeFeedIndicator = false
        var isCommentsOrShareOpen = false

        val candidates = mutableListOf<String>()

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 25) return

            val resId = node.viewIdResourceName.orEmpty()
            val desc = node.contentDescription?.toString().orEmpty().trim()
            val text = node.text?.toString().orEmpty().trim()

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

            // 2. Home Feed and Direct Messages Inbox detection (should NOT count as Reels)
            if (resId.contains("title_logo", true) ||
                desc.contains("Home Feed", true) ||
                desc.contains("reels tray container", true) ||
                resId.contains("reels_tray", true)
            ) {
                hasHomeFeedIndicator = true
            }

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
                resId.contains("unified_video_container", true)
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
            }

            // 5. Collect video signature components (Author, Audio, Caption) - ONLY if not on Home Feed
            if (!hasHomeFeedIndicator && candidates.size < 3) {
                // Check "Reel by creator" accessibility description
                if (desc.startsWith("Reel by ", ignoreCase = true)) {
                    val creator = desc.substring(8).split(".")[0].trim()
                    if (creator.isNotEmpty() && !candidates.contains(creator)) {
                        candidates.add(creator)
                    }
                }
                // Check avatar content description (e.g., "altyboston profile picture", "dosakallu_ profile picture")
                else if (desc.contains("profile picture", true)) {
                    val authorName = desc.replace("profile picture", "", ignoreCase = true)
                        .replace("Profile picture of", "", ignoreCase = true)
                        .replace("'s", "", ignoreCase = true)
                        .trim()
                    if (authorName.length in 2..40 && !authorName.contains(" ") && !candidates.contains(authorName)) {
                        candidates.add(authorName)
                    }
                }
                // Author view IDs (excluding home feed post profile names)
                else if (resId.contains("clips_author", true) ||
                    resId.contains("clips_user_name", true) ||
                    resId.contains("author_name", true)
                ) {
                    val key = text.ifEmpty { desc }
                    if (key.isNotEmpty() && !candidates.contains(key)) candidates.add(key)
                }
                // Audio track
                else if (resId.contains("audio_title", true) ||
                    resId.contains("music_title", true) ||
                    resId.contains("audio_track", true) ||
                    desc.contains("Original audio", true) ||
                    desc.contains("Audio by", true) ||
                    text.contains("Original audio", true) ||
                    text.contains("Audio by", true)
                ) {
                    val key = text.ifEmpty { desc }
                    if (key.isNotEmpty() && !candidates.contains(key)) candidates.add(key)
                }
                // Caption
                else if (resId.contains("clips_caption", true) ||
                    resId.contains("caption_text_view", true)
                ) {
                    val key = text.ifEmpty { desc }
                    if (key.length >= 4) {
                        val snippet = key.take(35)
                        if (!candidates.contains(snippet)) candidates.add(snippet)
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1)
            }
        }

        traverse(rootNode, 0)

        // Reels is active ONLY when NOT in Home feed or Direct inbox
        val isReels = !hasHomeFeedIndicator && !hasInboxList && (
            isReelsTabSelected || hasClipsViewer || (candidates.isNotEmpty() && !isFeedTabSelected)
        )
        val signature = if (candidates.isNotEmpty() && isReels) candidates.joinToString("::") else null

        return InstagramScan(isReels, isCommentsOrShareOpen, signature)
    }

    // ==========================================
    // YOUTUBE SHORTS (Single-pass, ultra-fast)
    // ==========================================

    private data class YouTubeScan(
        val isShorts: Boolean,
        val isCommentsOpen: Boolean,
        val signature: String?
    )

    private fun analyzeYouTube(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo?): DetectionResult {
        val nodeToScan = rootNode ?: event.source ?: return DetectionResult(TargetPlatform.NONE, false)

        val scan = scanYouTube(nodeToScan)
        Log.d(TAG, "[YouTube] isShorts=${scan.isShorts}, isComments=${scan.isCommentsOpen}, sig=${scan.signature}")
        if (!scan.isShorts) {
            return DetectionResult(TargetPlatform.NONE, false)
        }

        if (scan.isCommentsOpen) {
            return DetectionResult(
                platform = TargetPlatform.YOUTUBE_SHORTS,
                isNewContentScrolled = false,
                currentSignature = lastYouTubeSignature
            )
        }

        val now = System.currentTimeMillis()
        val signature = scan.signature

        return processPlatformTransition(
            platform = TargetPlatform.YOUTUBE_SHORTS,
            event = event,
            signature = signature,
            now = now,
            getLastSignature = { lastYouTubeSignature },
            setLastSignature = { lastYouTubeSignature = it },
            getLastCountTime = { lastYouTubeCountTime },
            setLastCountTime = { lastYouTubeCountTime = it },
            getIsAnonymousPending = { isYouTubeAnonymousPending },
            setIsAnonymousPending = { isYouTubeAnonymousPending = it },
            getLastAnonymousTime = { lastYouTubeAnonymousTime },
            setLastAnonymousTime = { lastYouTubeAnonymousTime = it },
            getIsBaselinePending = { isYouTubeBaselinePending },
            setIsBaselinePending = { isYouTubeBaselinePending = it }
        )
    }

    private fun scanYouTube(rootNode: AccessibilityNodeInfo): YouTubeScan {
        var isShortsTabSelected = false
        var hasShortsContainer = false
        var isNormalWatchPlayer = false
        var isCommentsOpen = false

        val candidates = mutableListOf<String>()

        val screenBounds = Rect()
        rootNode.getBoundsInScreen(screenBounds)
        val screenHeight = screenBounds.height().coerceAtLeast(1000)
        val topLimit = (screenHeight * 0.10).toInt()
        val bottomLimit = (screenHeight * 0.90).toInt()
        val nodeBounds = Rect()

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20) return

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
                resId.contains("comment_composer", true) ||
                resId.contains("comment_thread", true) ||
                resId.contains("comments_header", true) ||
                resId.contains("bottom_sheet_container", true) ||
                resId.contains("panel_header", true) ||
                resId.contains("description_panel", true)
            ) {
                isCommentsOpen = true
            }

            // 5. Collect signature components (Channel, Title, Sound)
            if (candidates.size < 3) {
                node.getBoundsInScreen(nodeBounds)
                val centerY = nodeBounds.centerY()
                if (centerY in topLimit..bottomLimit) {
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
    // STATE TRANSITION ENGINE
    // ==========================================

    private inline fun processPlatformTransition(
        platform: TargetPlatform,
        event: AccessibilityEvent,
        signature: String?,
        now: Long,
        getLastSignature: () -> String?,
        setLastSignature: (String?) -> Unit,
        getLastCountTime: () -> Long,
        setLastCountTime: (Long) -> Unit,
        getIsAnonymousPending: () -> Boolean,
        setIsAnonymousPending: (Boolean) -> Unit,
        getLastAnonymousTime: () -> Long,
        setLastAnonymousTime: (Long) -> Unit,
        getIsBaselinePending: () -> Boolean,
        setIsBaselinePending: (Boolean) -> Unit
    ): DetectionResult {
        val lastSig = getLastSignature()
        val isScrollEvent = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED

        // 1. Initial entry or baseline resolution (prevents phantom count jump on app open)
        if (getIsBaselinePending()) {
            if (signature != null) {
                // Initial video's signature has loaded cleanly
                setLastSignature(signature)
                setLastCountTime(0L) // Set to 0 so the first user swipe is never blocked by debounce
                setIsBaselinePending(false)
                Log.d(TAG, "[$platform] Initial baseline established: $signature (not counted)")
                return DetectionResult(platform, isNewContentScrolled = false, currentSignature = signature)
            } else if (isScrollEvent) {
                // User swiped even before the initial video's metadata arrived!
                setLastSignature(null)
                setLastCountTime(now)
                setIsBaselinePending(false)
                setIsAnonymousPending(true)
                setLastAnonymousTime(now)
                Log.i(TAG, "[$platform] User scrolled before baseline -> count scroll")
                return DetectionResult(platform, isNewContentScrolled = true, currentSignature = null)
            } else {
                // Still waiting on initial video without user scrolling
                return DetectionResult(platform, isNewContentScrolled = false, currentSignature = null)
            }
        }

        // 2. We have a non-null signature for the current view
        if (signature != null) {
            // Check if we recently performed an anonymous scroll fallback that can now adopt this signature
            if (getIsAnonymousPending() && (now - getLastAnonymousTime() < ANONYMOUS_ADOPTION_WINDOW_MS)) {
                setLastSignature(signature)
                setIsAnonymousPending(false)
                Log.d(TAG, "[$platform] Adopted signature for recent anonymous scroll: $signature")
                return DetectionResult(platform, isNewContentScrolled = false, currentSignature = signature)
            }

            // If signature is distinct from the previously counted video
            if (signature != lastSig) {
                val timeSinceLast = now - getLastCountTime()
                if (timeSinceLast >= MIN_DISTINCT_SIGNATURE_INTERVAL_MS) {
                    setLastSignature(signature)
                    setLastCountTime(now)
                    setIsAnonymousPending(false)
                    Log.i(TAG, "[$platform] New video confirmed via signature change ($timeSinceLast ms): $signature")
                    return DetectionResult(platform, isNewContentScrolled = true, currentSignature = signature)
                } else {
                    // Too fast (< 240ms debounce), do NOT overwrite lastSignature so subsequent event can count it
                    return DetectionResult(platform, isNewContentScrolled = false, currentSignature = lastSig)
                }
            } else {
                // signature == lastSig: Still on the same video (e.g. slow drag within same video, comments, etc.)
                return DetectionResult(platform, isNewContentScrolled = false, currentSignature = signature)
            }
        }

        // 3. Fallback when signature is null (pure video with zero text/metadata)
        if (isScrollEvent) {
            val timeSinceLast = now - getLastCountTime()
            if (timeSinceLast >= MIN_FALLBACK_SCROLL_INTERVAL_MS) {
                setLastCountTime(now)
                setIsAnonymousPending(true)
                setLastAnonymousTime(now)
                setLastSignature(null)
                Log.i(TAG, "[$platform] Fallback scroll counted ($timeSinceLast ms)")
                return DetectionResult(platform, isNewContentScrolled = true, currentSignature = null)
            }
        }

        return DetectionResult(platform, isNewContentScrolled = false, currentSignature = lastSig)
    }

    fun resetState() {
        lastInstagramSignature = null
        lastInstagramCountTime = 0L
        isInstagramAnonymousPending = false
        lastInstagramAnonymousTime = 0L
        isInstagramBaselinePending = true

        lastYouTubeSignature = null
        lastYouTubeCountTime = 0L
        isYouTubeAnonymousPending = false
        lastYouTubeAnonymousTime = 0L
        isYouTubeBaselinePending = true
    }
}
