package com.scrollcounter.app.detector

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
    private var lastInstagramScrollTime: Long = 0L

    private var lastYouTubeSignature: String? = null
    private var lastYouTubeScrollTime: Long = 0L

    companion object {
        const val TAG = "ScrollCounter"
        const val PACKAGE_INSTAGRAM = "com.instagram.android"
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"

        // Human minimum threshold between distinct video scrolls (650ms)
        // Completely eliminates multiple counts from swipe flings, inertial bounces, or micro-swipes
        private const val MIN_SCROLL_INTERVAL_MS = 650L
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

    private fun analyzeInstagram(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo?): DetectionResult {
        val isReels = isInstagramReelsActive(rootNode)
        if (!isReels) {
            return DetectionResult(TargetPlatform.NONE, false)
        }

        // If user is reading comments or has opened the share/remix sheet, DO NOT count scrolls!
        if (isInstagramCommentsOrShareOpen(rootNode)) {
            return DetectionResult(
                platform = TargetPlatform.INSTAGRAM_REELS,
                isNewContentScrolled = false,
                currentSignature = lastInstagramSignature
            )
        }

        val now = System.currentTimeMillis()
        var isNewReel = false
        val signature = extractInstagramSignature(rootNode)

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val isDistinct = signature != null && signature != lastInstagramSignature
            val isDebounced = now - lastInstagramScrollTime > MIN_SCROLL_INTERVAL_MS

            if (isDistinct || (signature == null && isDebounced)) {
                if (isDebounced) {
                    isNewReel = true
                    lastInstagramScrollTime = now
                    if (signature != null) lastInstagramSignature = signature
                    Log.i(TAG, "Reel scroll confirmed: $signature")
                }
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Also detect clean transitions when signature changes
            if (signature != null && signature != lastInstagramSignature) {
                if (now - lastInstagramScrollTime > MIN_SCROLL_INTERVAL_MS) {
                    isNewReel = true
                    lastInstagramSignature = signature
                    lastInstagramScrollTime = now
                    Log.i(TAG, "Reel signature changed: $signature")
                }
            }
        }

        return DetectionResult(
            platform = TargetPlatform.INSTAGRAM_REELS,
            isNewContentScrolled = isNewReel,
            currentSignature = signature ?: lastInstagramSignature
        )
    }

    fun isInstagramReelsActive(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false

        var isFeedTabSelected = false
        var isSearchTabSelected = false
        var isProfileTabSelected = false
        var isDirectTabSelected = false
        var isReelsTabSelected = false
        var hasClipsMarker = false

        // Reliable UI hierarchy scan (depth <= 12 for nested navigation bars)
        fun scan(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (depth > 12) return

            val resId = node.viewIdResourceName.orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()

            // Check bottom bar tabs
            if (node.isSelected) {
                if (resId.contains("feed_tab", true) || desc.equals("Home", true)) {
                    isFeedTabSelected = true
                } else if (resId.contains("clips_tab", true) || desc.equals("Reels", true)) {
                    isReelsTabSelected = true
                } else if (resId.contains("search_tab", true) || desc.contains("Search", true)) {
                    isSearchTabSelected = true
                } else if (resId.contains("profile_tab", true) || desc.equals("Profile", true)) {
                    isProfileTabSelected = true
                } else if (resId.contains("direct_tab", true) || desc.contains("Message", true)) {
                    isDirectTabSelected = true
                }
            }

            // Standalone Reels fullscreen viewer (opened from direct link or explore)
            if (resId.contains("clips_video_container", true) ||
                resId.contains("reel_viewer", true) ||
                resId.contains("clips_viewer", true)
            ) {
                hasClipsMarker = true
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                scan(child, depth + 1)
            }
        }

        scan(rootNode)

        // STRICT RULE: If the user is on Home Feed, Search, Profile, or Direct -> 100% NOT REELS!
        if (isFeedTabSelected || isSearchTabSelected || isProfileTabSelected || isDirectTabSelected) {
            return false
        }

        // Must be either on the Reels tab or in fullscreen Clips viewer
        return isReelsTabSelected || hasClipsMarker
    }

    private fun isInstagramCommentsOrShareOpen(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        var isOpen = false

        fun check(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (isOpen || depth > 8) return

            val resId = node.viewIdResourceName.orEmpty()
            if (resId.contains("comment_composer", true) ||
                resId.contains("layout_comment_thread", true) ||
                resId.contains("bottom_sheet_container", true) ||
                resId.contains("share_sheet", true) ||
                resId.contains("clips_comments", true)
            ) {
                isOpen = true
                return
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                check(child, depth + 1)
            }
        }

        check(rootNode)
        return isOpen
    }

    private fun extractInstagramSignature(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        val candidates = mutableListOf<String>()

        fun collect(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (depth > 8 || candidates.size >= 2) return

            val resId = node.viewIdResourceName.orEmpty()
            val text = node.text?.toString().orEmpty().trim()
            val desc = node.contentDescription?.toString().orEmpty().trim()

            if (resId.contains("clips_author", true) ||
                resId.contains("row_feed_photo_profile_name", true) ||
                resId.contains("audio_title", true)
            ) {
                val key = text.ifEmpty { desc }
                if (key.isNotEmpty() && !candidates.contains(key)) candidates.add(key)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collect(child, depth + 1)
            }
        }

        collect(rootNode)
        return if (candidates.isNotEmpty()) candidates.joinToString("::") else null
    }

    private fun analyzeYouTube(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo?): DetectionResult {
        val isShorts = isYouTubeShortsActive(rootNode)
        if (!isShorts) {
            return DetectionResult(TargetPlatform.NONE, false)
        }

        if (isYouTubeCommentsOpen(rootNode)) {
            return DetectionResult(
                platform = TargetPlatform.YOUTUBE_SHORTS,
                isNewContentScrolled = false,
                currentSignature = lastYouTubeSignature
            )
        }

        val now = System.currentTimeMillis()
        var isNewShort = false
        val signature = extractYouTubeSignature(rootNode)

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val isDistinct = signature != null && signature != lastYouTubeSignature
            val isDebounced = now - lastYouTubeScrollTime > MIN_SCROLL_INTERVAL_MS

            if (isDistinct || (signature == null && isDebounced)) {
                if (isDebounced) {
                    isNewShort = true
                    lastYouTubeScrollTime = now
                    if (signature != null) lastYouTubeSignature = signature
                    Log.i(TAG, "Short scroll confirmed: $signature")
                }
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (signature != null && signature != lastYouTubeSignature) {
                if (now - lastYouTubeScrollTime > MIN_SCROLL_INTERVAL_MS) {
                    isNewShort = true
                    lastYouTubeSignature = signature
                    lastYouTubeScrollTime = now
                    Log.i(TAG, "Short signature changed: $signature")
                }
            }
        }

        return DetectionResult(
            platform = TargetPlatform.YOUTUBE_SHORTS,
            isNewContentScrolled = isNewShort,
            currentSignature = signature ?: lastYouTubeSignature
        )
    }

    fun isYouTubeShortsActive(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false

        var isShortsTabSelected = false
        var isOtherTabSelected = false
        var hasShortsContainer = false
        var isNormalWatchPlayer = false

        fun scan(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (depth > 12) return

            val resId = node.viewIdResourceName.orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()

            if (node.isSelected) {
                if (desc.equals("Shorts", true)) {
                    isShortsTabSelected = true
                } else if (desc.equals("Home", true) || desc.contains("Subscriptions", true) || desc.contains("Library", true) || desc.equals("You", true)) {
                    isOtherTabSelected = true
                }
            }

            if (resId.contains("shorts_container", true) ||
                resId.contains("reel_recycler", true) ||
                resId.contains("reel_player_page_tree", true) ||
                resId.contains("shorts_shelf", true)
            ) {
                hasShortsContainer = true
            }

            if (resId.contains("watch_player", true) || resId.contains("watch_while_layout", true)) {
                isNormalWatchPlayer = true
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                scan(child, depth + 1)
            }
        }

        scan(rootNode)

        // Strict rejection: normal videos should never count as Shorts
        if (isOtherTabSelected && !hasShortsContainer) {
            return false
        }
        if (isNormalWatchPlayer && !hasShortsContainer) {
            return false
        }

        return isShortsTabSelected || hasShortsContainer
    }

    private fun isYouTubeCommentsOpen(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        var isOpen = false

        fun check(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (isOpen || depth > 8) return

            val resId = node.viewIdResourceName.orEmpty()
            if (resId.contains("comment", true) || resId.contains("panel_content", true)) {
                isOpen = true
                return
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                check(child, depth + 1)
            }
        }

        check(rootNode)
        return isOpen
    }

    private fun extractYouTubeSignature(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        val candidates = mutableListOf<String>()

        fun collect(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (depth > 8 || candidates.size >= 2) return

            val desc = node.contentDescription?.toString().orEmpty().trim()
            if (desc.contains("Sound", true) || desc.contains("original", true) || desc.contains("Subscribe", true)) {
                if (!candidates.contains(desc)) candidates.add(desc)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collect(child, depth + 1)
            }
        }

        collect(rootNode)
        return if (candidates.isNotEmpty()) candidates.joinToString("::") else null
    }

    fun resetState() {
        lastInstagramSignature = null
        lastYouTubeSignature = null
        lastInstagramScrollTime = 0L
        lastYouTubeScrollTime = 0L
    }
}
