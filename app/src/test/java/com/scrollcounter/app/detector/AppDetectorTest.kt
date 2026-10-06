package com.scrollcounter.app.detector

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppDetectorTest {

    private lateinit var detector: AppDetector

    @Before
    fun setUp() {
        detector = AppDetector()
    }

    private fun createNode(
        resourceId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        isSelected: Boolean = false,
        children: List<AccessibilityNodeInfo> = emptyList()
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.viewIdResourceName } returns resourceId
        every { node.text } returns text
        every { node.contentDescription } returns contentDescription
        every { node.isSelected } returns isSelected
        every { node.childCount } returns children.size
        children.forEachIndexed { index, child ->
            every { node.getChild(index) } returns child
        }
        return node
    }

    // ==========================================
    // INSTAGRAM TESTS
    // ==========================================

    @Test
    fun testInstagram_WhenHomeFeedTabSelected_ReturnsNone() {
        val feedTabNode = createNode(
            resourceId = "com.instagram.android:id/feed_tab",
            contentDescription = "Home",
            isSelected = true
        )
        val root = createNode(children = listOf(feedTabNode))
        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.packageName } returns AppDetector.PACKAGE_INSTAGRAM
        every { event.eventType } returns AccessibilityEvent.TYPE_VIEW_SCROLLED

        val result = detector.analyzeEvent(event, root)

        assertEquals(TargetPlatform.NONE, result.platform)
        assertFalse(result.isNewContentScrolled)
    }

    @Test
    fun testInstagram_WhenSearchOrProfileTabSelected_ReturnsNone() {
        val searchTab = createNode(resourceId = "com.instagram.android:id/search_tab", isSelected = true)
        val root = createNode(children = listOf(searchTab))
        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.packageName } returns AppDetector.PACKAGE_INSTAGRAM

        val result = detector.analyzeEvent(event, root)

        assertEquals(TargetPlatform.NONE, result.platform)
        assertFalse(result.isNewContentScrolled)
    }

    @Test
    fun testInstagram_WhenClipsTabSelected_IdentifiesReels() {
        val clipsTabNode = createNode(
            resourceId = "com.instagram.android:id/clips_tab",
            contentDescription = "Reels",
            isSelected = true
        )
        val root = createNode(children = listOf(clipsTabNode))

        assertTrue(detector.isInstagramReelsActive(root))
    }

    @Test
    fun testInstagram_WhenClipsVideoContainerPresent_IdentifiesReels() {
        val clipsViewerNode = createNode(
            resourceId = "com.instagram.android:id/clips_video_container"
        )
        val root = createNode(children = listOf(clipsViewerNode))

        assertTrue(detector.isInstagramReelsActive(root))
    }

    @Test
    fun testInstagram_WhenCommentsSheetOpen_DoesNotCountScroll() {
        val clipsTab = createNode(resourceId = "com.instagram.android:id/clips_tab", isSelected = true)
        val commentComposer = createNode(resourceId = "com.instagram.android:id/comment_composer")
        val root = createNode(children = listOf(clipsTab, commentComposer))

        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.packageName } returns AppDetector.PACKAGE_INSTAGRAM
        every { event.eventType } returns AccessibilityEvent.TYPE_VIEW_SCROLLED

        val result = detector.analyzeEvent(event, root)

        assertEquals(TargetPlatform.INSTAGRAM_REELS, result.platform)
        assertFalse("Scrolls should NEVER be counted when reading comments!", result.isNewContentScrolled)
    }

    @Test
    fun testInstagram_ReelScroll_DistinctSignaturesCountAccurately() {
        val clipsTab = createNode(resourceId = "com.instagram.android:id/clips_tab", isSelected = true)
        val author1 = createNode(
            resourceId = "com.instagram.android:id/clips_author",
            text = "@creator_one"
        )
        val root1 = createNode(children = listOf(clipsTab, author1))

        val event1 = mockk<AccessibilityEvent>(relaxed = true)
        every { event1.packageName } returns AppDetector.PACKAGE_INSTAGRAM
        every { event1.eventType } returns AccessibilityEvent.TYPE_VIEW_SCROLLED

        val result1 = detector.analyzeEvent(event1, root1)
        assertEquals(TargetPlatform.INSTAGRAM_REELS, result1.platform)
        // Baseline established on initial reel load (not counted until user scrolls to new reel)
        assertFalse(result1.isNewContentScrolled)

        // Wait to simulate user scrolling to next distinct reel
        Thread.sleep(350)

        val author2 = createNode(
            resourceId = "com.instagram.android:id/clips_author",
            text = "@creator_two"
        )
        val root2 = createNode(children = listOf(clipsTab, author2))
        val result3 = detector.analyzeEvent(event1, root2)

        assertTrue("New distinct reel author must be counted!", result3.isNewContentScrolled)
    }

    // ==========================================
    // YOUTUBE SHORTS TESTS
    // ==========================================

    @Test
    fun testYouTube_WhenHomeTabSelected_ReturnsNone() {
        val homeTab = createNode(contentDescription = "Home", isSelected = true)
        val root = createNode(children = listOf(homeTab))

        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.packageName } returns AppDetector.PACKAGE_YOUTUBE

        val result = detector.analyzeEvent(event, root)
        assertEquals(TargetPlatform.NONE, result.platform)
        assertFalse(result.isNewContentScrolled)
    }

    @Test
    fun testYouTube_WhenNormalWatchPlayerActive_ReturnsNone() {
        val watchPlayer = createNode(resourceId = "com.google.android.youtube:id/watch_player")
        val root = createNode(children = listOf(watchPlayer))

        assertFalse(detector.isYouTubeShortsActive(root))
    }

    @Test
    fun testYouTube_WhenShortsTabSelected_IdentifiesShorts() {
        val shortsTab = createNode(contentDescription = "Shorts", isSelected = true)
        val root = createNode(children = listOf(shortsTab))

        assertTrue(detector.isYouTubeShortsActive(root))
    }

    @Test
    fun testYouTube_WhenShortsContainerPresent_IdentifiesShorts() {
        val shortsContainer = createNode(resourceId = "com.google.android.youtube:id/shorts_container")
        val root = createNode(children = listOf(shortsContainer))

        assertTrue(detector.isYouTubeShortsActive(root))
    }

    @Test
    fun testYouTube_WhenShortsCommentsOpen_DoesNotCountScroll() {
        val shortsTab = createNode(contentDescription = "Shorts", isSelected = true)
        val commentPanel = createNode(resourceId = "com.google.android.youtube:id/comment")
        val root = createNode(children = listOf(shortsTab, commentPanel))

        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.packageName } returns AppDetector.PACKAGE_YOUTUBE
        every { event.eventType } returns AccessibilityEvent.TYPE_VIEW_SCROLLED

        val result = detector.analyzeEvent(event, root)
        assertEquals(TargetPlatform.YOUTUBE_SHORTS, result.platform)
        assertFalse("Scrolling YouTube comments must never count as a Short!", result.isNewContentScrolled)
    }

    @Test
    fun testYouTube_ShortScroll_DebouncedAndSignatureCounted() {
        val shortsTab = createNode(contentDescription = "Shorts", isSelected = true)
        val soundNode1 = createNode(contentDescription = "Sound: Original sound - Artist 1")
        val root1 = createNode(children = listOf(shortsTab, soundNode1))

        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.packageName } returns AppDetector.PACKAGE_YOUTUBE
        every { event.eventType } returns AccessibilityEvent.TYPE_VIEW_SCROLLED

        val result1 = detector.analyzeEvent(event, root1)
        assertEquals(TargetPlatform.YOUTUBE_SHORTS, result1.platform)
        assertTrue(result1.isNewContentScrolled)

        // Immediate duplicate event within debounce threshold
        val result2 = detector.analyzeEvent(event, root1)
        assertFalse(result2.isNewContentScrolled)

        // Wait to pass minimum scroll interval
        Thread.sleep(700)

        val soundNode2 = createNode(contentDescription = "Sound: Trending sound - Artist 2")
        val root2 = createNode(children = listOf(shortsTab, soundNode2))
        val result3 = detector.analyzeEvent(event, root2)
        assertTrue("New distinct short sound must be counted!", result3.isNewContentScrolled)
    }

    // ==========================================
    // UNRELATED APP TESTS
    // ==========================================

    @Test
    fun testUnrelatedApp_ReturnsNone() {
        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.packageName } returns "com.whatsapp"

        val result = detector.analyzeEvent(event, null)
        assertEquals(TargetPlatform.NONE, result.platform)
        assertFalse(result.isNewContentScrolled)
    }
}
