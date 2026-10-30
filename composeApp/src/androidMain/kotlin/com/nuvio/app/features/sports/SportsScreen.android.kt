package com.nuvio.app.features.sports

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

// The Sports navigation entry is desktop-only.
@Composable
internal actual fun SportsScreen(topChromePadding: Dp?, active: Boolean, onPlay: (String, String, String) -> Unit) = Unit
