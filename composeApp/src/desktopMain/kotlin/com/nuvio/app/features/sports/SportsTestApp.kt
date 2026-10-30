package com.nuvio.app.features.sports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.nuvio.app.features.player.PlayerBackReleaseGuard
import com.nuvio.app.features.player.PlayerScreen

/** Standalone entry for testing IPTV without an upstream account or configured movie addons. */
@Composable
internal fun SportsTestApp() {
    var playback by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    val guard = remember(playback) { PlayerBackReleaseGuard() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box {
            // Keep the hub and session-only connection alive while the player is on top.
            SportsScreen(topChromePadding = null, active = playback == null) { title, url, id ->
                playback = Triple(title, url, id)
            }
            playback?.let { (title, url, id) ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PlayerScreen(
                        profileId = 0, title = title, sourceUrl = url,
                        sourceHeaders = mapOf("User-Agent" to SportsIptvUserAgent),
                        providerName = "Sports IPTV", streamTitle = title, streamSubtitle = null,
                        contentType = "live", parentMetaId = "sports:$id", parentMetaType = "live",
                        onBack = { release ->
                            guard.request({ playback != null }, release, {}, { playback = null; true })
                        },
                    )
                }
            }
        }
    }
}
