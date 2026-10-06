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

        // Minimum time between distinct video signature transitions (280ms).
        private const val MIN_DISTINCT_SIGNATURE_INTERVAL_MS = 280L

        // Cooldown when gesture occurs on the same video (1200ms).
        // Completely eliminates double-counting caused by fling and snap animations!
        private const val SAME_VIDEO_SWIPE_COOLDOWN_MS = 1200L

        // Cooldown between fallback (anonymous) scroll counts (700ms).
        private const val ANONYMOUS_SCROLL_COOLDOWN_MS = 700L

        // Grace period to adopt a late-loading signature after a scroll
        private const val ANONYMOUS_ADOPTION_WINDOW_MS = 950L
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

    fun isInstagramReelsActive(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        return scanInstagram(rootNode).isReels
    }

    fun isYouTubeShortsActive(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        return scanYouTube(rootNode).isShorts
    }

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

            // Skip irrelevant large subtrees early to save battery and binder calls
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
                    resId.contains("author_name", true)
                ) {
                    val key = text.ifEmpty { desc }
                    if (key.isNotEmpty()) authorFound = key
                }
            }

            if (captionFound == null) {
                if (resId.contains("clips_caption", true) ||
                    resId.contains("caption_text_view", true)
                ) {
                    val key = text.ifEmpty { desc }
                    if (key.length >= 3) {
                        captionFound = key.take(30)
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
                setLastSignature(signature)
                setLastCountTime(0L) // Set to 0 so the first user swipe is never blocked
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
                return DetectionResult(platform, isNewContentScrolled = false, currentSignature = null)
            }
        }

        // 2. Check if a recent anonymous scroll can now adopt this incoming signature
        if (signature != null && getIsAnonymousPending() && (now - getLastAnonymousTime() < ANONYMOUS_ADOPTION_WINDOW_MS)) {
            setLastSignature(signature)
            setIsAnonymousPending(false)
            Log.d(TAG, "[$platform] Adopted signature for recent anonymous scroll: $signature")
            return DetectionResult(platform, isNewContentScrolled = false, currentSignature = signature)
        }

        // 3. New video confirmed via signature change (distinct video content)
        if (signature != null && signature != lastSig) {
            val timeSinceLast = now - getLastCountTime()
            if (timeSinceLast >= MIN_DISTINCT_SIGNATURE_INTERVAL_MS) {
                setLastSignature(signature)
                setLastCountTime(now)
                setIsAnonymousPending(false)
                Log.i(TAG, "[$platform] New video confirmed via signature change ($timeSinceLast ms): $signature")
                return DetectionResult(platform, isNewContentScrolled = true, currentSignature = signature)
            } else {
                return DetectionResult(platform, isNewContentScrolled = false, currentSignature = lastSig)
            }
        }

        // 4. Physical scroll gesture (handles consecutive reels by the same creator, shared audio, or delayed rendering)
        if (isScrollEvent) {
            val timeSinceLast = now - getLastCountTime()
            val requiredCooldown = if (signature != null && signature == lastSig) SAME_VIDEO_SWIPE_COOLDOWN_MS else ANONYMOUS_SCROLL_COOLDOWN_MS
            if (timeSinceLast >= requiredCooldown) {
                setLastCountTime(now)
                setIsAnonymousPending(true)
                setLastAnonymousTime(now)
                if (signature != null) {
                    setLastSignature(signature)
                }
                Log.i(TAG, "[$platform] Scroll counted via gesture ($timeSinceLast ms, sig=$signature)")
                return DetectionResult(platform, isNewContentScrolled = true, currentSignature = signature)
            }
        }

        return DetectionResult(platform, isNewContentScrolled = false, currentSignature = signature ?: lastSig)
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
