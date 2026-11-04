package com.nuvio.app.features.sports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** Standalone entry for testing IPTV without an upstream account or configured movie addons. */
@Composable
internal fun SportsTestApp() {
    var playback by remember { mutableStateOf<SportsPlaybackRequest?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box {
            SportsScreen(topChromePadding = null, active = playback == null) { playback = it }
            playback?.let { request -> SportsPlaybackScreen(request, onBack = { playback = null }) }
        }
    }
}
