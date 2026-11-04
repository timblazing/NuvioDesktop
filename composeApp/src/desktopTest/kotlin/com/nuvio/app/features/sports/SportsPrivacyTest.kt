package com.nuvio.app.features.sports

import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.externalPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlin.test.*

class SportsPrivacyTest {
    @Test fun livePlaybackCannotSaveCredentialsInWatchHistory() {
        val launch = PlayerLaunch(profileId = 0, title = "Test channel", sourceUrl = "https://example.com/live/test-user/test-password/1.m3u8",
            streamTitle = "Test channel", providerName = "Sports IPTV", contentType = "live", parentMetaId = "sports:privacy-test", parentMetaType = "live")
        val session = launch.externalPlaybackSession()
        assertNull(session.lastSourceUrl)
        // Defend the persistence boundary even if a caller supplies a URL itself.
        WatchProgressRepository.upsertPlaybackProgress(session.copy(lastSourceUrl = launch.sourceUrl),
            PlayerPlaybackSnapshot(isPlaying = true, durationMs = 7_200_000L, positionMs = 600_000L))
        assertNull(WatchProgressRepository.progressForVideo("sports:privacy-test", "sports:privacy-test"))
    }

    @Test fun manualChannelMappingsAreScopedToTheProviderAccount() {
        val first = XtreamConnection("https://one.example", "u", "p", SportsStreamFormat.HLS)
        val otherServer = XtreamConnection("https://two.example", "u", "p", SportsStreamFormat.HLS)
        val otherAccount = XtreamConnection("https://one.example", "other", "p", SportsStreamFormat.HLS)
        assertNotEquals(first.mappingKey("nfl:1"), otherServer.mappingKey("nfl:1"))
        assertNotEquals(first.mappingKey("nfl:1"), otherAccount.mappingKey("nfl:1"))
        assertFalse(first.mappingKey("nfl:1").contains("one.example"))
    }
}
