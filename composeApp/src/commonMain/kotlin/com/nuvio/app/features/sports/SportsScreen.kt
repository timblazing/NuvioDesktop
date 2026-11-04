package com.nuvio.app.features.sports

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.nuvio.app.features.player.PlayerPlaybackSnapshot

internal const val SportsIptvUserAgent = "VLC/3.0.21 LibVLC/3.0.21"

@Composable
internal expect fun SportsScreen(
    topChromePadding: Dp?,
    active: Boolean,
    onPlay: (SportsPlaybackRequest) -> Unit,
)

/** Session-only: neither the resolver nor credential-bearing source URLs are persisted. */
class SportsPlaybackRequest(val title: String, val eventId: String, val resolve: suspend () -> List<SportsPlaybackSource>)
class SportsPlaybackSource(val channelId: String, val label: String, val url: String) {
    override fun toString() = "SportsPlaybackSource(redacted)"
}

@Composable
internal expect fun SportsPlaybackScreen(
    request: SportsPlaybackRequest,
    onBack: () -> Unit,
    onSystemBackHandlerChanged: ((() -> Unit)?) -> Unit = {},
)
