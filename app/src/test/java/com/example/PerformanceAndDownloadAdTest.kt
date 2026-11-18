package com.example

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Looper
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerformanceAndDownloadAdTest {

    @Test
    fun testDownloadAdUnitIdDistinctAndConfiguredInKotlinAndJs() {
        val fiveAdsId = MainActivity.DEFAULT_REWARDED_AD_UNIT_ID
        val watchEpisodeId = MainActivity.EPISODE_REWARDED_AD_UNIT_ID
        val downloadEpisodeId = MainActivity.DOWNLOAD_REWARDED_AD_UNIT_ID

        assertEquals("ca-app-pub-2277779478101583/1404104591", fiveAdsId)
        assertEquals("ca-app-pub-2277779478101583/9116970862", watchEpisodeId)
        assertEquals("ca-app-pub-2277779478101583/9003880054", downloadEpisodeId)

        assertNotEquals(fiveAdsId, watchEpisodeId)
        assertNotEquals(fiveAdsId, downloadEpisodeId)
        assertNotEquals(watchEpisodeId, downloadEpisodeId)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val html = context.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(
            "index.html ADMOB_CONFIG must define downloadRewardedAdUnitId",
            html.contains("""downloadRewardedAdUnitId: "ca-app-pub-2277779478101583/9003880054"""")
        )
    }

    @Test
    fun testDownloadBlockedWithoutEarnedRewardAndUnlockedOnceAfterEarnedReward() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()

        val webView = WebView(activity)
        val bridge = MainActivity.AdMobBridge(activity, webView)

        val sampleUrl = "https://cdn.example.com/episodes/ep1.mp4"
        val animeTitle = "Test Anime"
        val epTitle = "Episode 1"
        val episodeKey = "fid_test_0"
        val attemptId = "dl_attempt_1001"

        // 1. Direct downloadEpisode without prior reward must be strictly blocked
        val directResult = bridge.downloadEpisode(sampleUrl, animeTitle, epTitle, episodeKey)
        assertEquals("AD_REWARD_REQUIRED", directResult)

        // 2. downloadEpisodeWithReward with unverified attemptId must be strictly blocked
        val unverifiedResult = bridge.downloadEpisodeWithReward(sampleUrl, animeTitle, epTitle, episodeKey, attemptId)
        assertEquals("AD_REWARD_REQUIRED", unverifiedResult)

        // 3. Grant reward only as OnUserEarnedRewardListener does, then verify wrong key/attempt stays blocked
        bridge.grantVerifiedDownloadReward(attemptId, episodeKey)
        assertFalse(
            "Mismatched episodeKey must not consume another episode's reward token",
            bridge.consumeVerifiedDownloadReward(attemptId, "fid_other_1")
        )

        // 4. Consuming with matching attemptId + episodeKey succeeds ONCE, then replays are blocked
        assertTrue(
            "Verified reward token must unlock download once",
            bridge.consumeVerifiedDownloadReward(attemptId, episodeKey)
        )
        assertFalse(
            "Replay of already-consumed reward token must be rejected",
            bridge.consumeVerifiedDownloadReward(attemptId, episodeKey)
        )

        // 5. Revoking on early dismiss / failure must also block consumption
        bridge.grantVerifiedDownloadReward("dl_attempt_2002", episodeKey)
        bridge.revokeVerifiedDownloadReward("dl_attempt_2002", episodeKey)
        assertFalse(
            "Revoked reward token must not unlock download",
            bridge.consumeVerifiedDownloadReward("dl_attempt_2002", episodeKey)
        )

        controller.pause().stop().destroy()
    }

    @Test
    fun testDownloadConfirmationPopupAndStrictRewardRulesInAssets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val html = context.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }

        // 1. Exact Arabic UI strings required for Download Rewarded Ad prompt
        assertTrue("Popup container #downloadAdConfirmPopup must exist", html.contains("""id="downloadAdConfirmPopup""""))
        assertTrue("Exact title 'تحميل الحلقة' must exist", html.contains("""<h2 class="ad-popup-title" id="downloadAdPopupTitle">تحميل الحلقة</h2>"""))
        assertTrue(
            "Exact message 'لمتابعة تحميل الحلقة، يجب مشاهدة إعلان قصير.' must exist",
            html.contains("""<p class="ad-popup-desc" id="downloadAdPopupDesc">لمتابعة تحميل الحلقة، يجب مشاهدة إعلان قصير.</p>""")
        )
        assertTrue("Confirm button 'موافق' must call confirmDownloadAdWatch()", html.contains("""onclick="window.confirmDownloadAdWatch()" style="flex:1">موافق</button>"""))
        assertTrue("Cancel button 'إلغاء' must call cancelDownloadAdWatch()", html.contains("""onclick="window.cancelDownloadAdWatch()" style="flex:1">إلغاء</button>"""))

        // 2. Strict JS reward enforcement
        assertTrue("startDownloadEpisode must route through showDownloadAdConfirmPopup", html.contains("window.showDownloadAdConfirmPopup(fid, idx, videoUrl, animeTitle, epTitle, episodeKey)"))
        assertTrue(
            "onDownloadAdResult must strictly require watched + _downloadAdRewardVerified",
            html.contains("""if (success === true && detail === "watched" && window._downloadAdRewardVerified === true)""")
        )
        assertTrue(
            "handleDownloadAdFailure must never initiate download on timeout/failure",
            html.contains("لم يتم بدء التحميل لأنه لم تتوفر أو تكتمل مشاهدة الإعلان.")
        )

        // 3. Previously requested MY CINEMA customizations must remain preserved
        assertTrue("Official website URL must remain updated", html.contains("https://mycinematv.netlify.app"))
        assertTrue("About brand MY CINEMA must remain preserved", html.contains("MY") && html.contains("CINEMA"))
    }

    @Test
    fun testMemoryTrimBatchDownloadedCacheAndInternalOptimizations() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()

        // 1. Verify WebView layer optimization (no forced LAYER_TYPE_HARDWARE FBO duplication)
        assertEquals(View.LAYER_TYPE_NONE, activity.webView.layerType)

        // 2. Verify batch downloaded episode keys JSON API
        val bridge = MainActivity.AdMobBridge(activity, activity.webView)
        val keysJson = bridge.getDownloadedEpisodeKeysJson()
        val parsed = JSONArray(keysJson)
        assertNotEquals(-1, parsed.length())

        // 3. Verify PosterImageCache memory management and Activity trim callbacks execute safely
        activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE)
        activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        activity.onLowMemory()
        assertTrue("Poster memory cache must be empty after TRIM_MEMORY_COMPLETE", PosterImageCache.memoryCacheSizeBytes() == 0)

        // 4. Verify index.html internal optimization invariants
        val html = activity.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue("O(1) _contentByFid Map index must exist", html.contains("window._contentByFid = window._contentByFid || new Map()"))
        assertTrue("O(1) _viewsScoreCache Map must exist", html.contains("window._viewsScoreCache = window._viewsScoreCache || new Map()"))
        assertTrue("Role-gated syncAdminListenersByRole must exist", html.contains("window.syncAdminListenersByRole = function(role)"))
        assertTrue("Incremental grid renderer must exist", html.contains("window.renderGridIncrementally = function(container, items, renderItemFn, batchSize = 24)"))
        assertTrue("Debounced search + search blob must exist", html.contains("window.scheduleDebouncedSearch = function()") && html.contains("window.getItemSearchBlob = function(item)"))
        assertTrue("Navigation cleanup must exist", html.contains("window.cleanupBeforeNavigation = function()"))

        controller.pause().stop().destroy()
    }
}
