package com.nuvio.app.features.sports

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

internal const val SportsIptvUserAgent = "VLC/3.0.21 LibVLC/3.0.21"

@Composable
internal expect fun SportsScreen(
    topChromePadding: Dp?,
    active: Boolean,
    onPlay: (title: String, url: String, channelId: String) -> Unit,
)
