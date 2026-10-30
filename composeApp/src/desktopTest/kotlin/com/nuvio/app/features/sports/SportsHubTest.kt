package com.nuvio.app.features.sports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SportsHubTest {
    private val source = object : SportsSource {
        override suspend fun events(league: SportsLeague, day: LocalDate) = if (league.id == "nfl") listOf(SportsDataTest.event()) else emptyList()
        override suspend fun channels(connection: XtreamConnection) = listOf(SportsChannel("123", "US | ESPN HD", "Sports"), SportsChannel("124", "NBA TV", "Sports"))
    }

    @Test fun desktopConnectSearchAndPlaybackFlow() = runDesktopComposeUiTest(width = 1440, height = 900) {
        var played: String? = null
        content(1440, 900) { _, url, _ -> played = url }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Celtics").fetchSemanticsNodes().isNotEmpty() }
        screenshot("desktop")
        onNodeWithText("Connect IPTV").performClick()
        onNodeWithText("Server URL").performTextInput("https://example.com")
        onNodeWithText("Username").performTextInput("test-user")
        onNodeWithText("Password").performTextInput("test-password")
        onNodeWithText("Connect", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("IPTV connected").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Find channels").performClick()
        onNodeWithText("Broadcast network suggestion").assertExists()
        screenshot("channel-picker")
        onNodeWithText("Search all channels").performTextInput("NBA")
        onNodeWithText("NBA TV").performClick()
        runOnIdle { assertEquals("https://example.com/live/test-user/test-password/124.m3u8", played) }
        onNodeWithText("NBA", substring = false).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("No events scheduled for this league and date.").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun narrowWindowAndDisconnectedPlayback() = runDesktopComposeUiTest(width = 393, height = 852) {
        content(393, 852) { _, _, _ -> fail("Must connect before playing") }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Celtics").fetchSemanticsNodes().isNotEmpty() }
        screenshot("narrow")
        onNodeWithText("Find channels").performClick()
        onNodeWithText("Connect your IPTV provider").assertExists()
        onNodeWithText("Close").performClick()
        onNodeWithText("Live", substring = false).performClick()
        onNodeWithText("Celtics").assertExists()
    }

    @Test fun unreadableScheduleShowsAnErrorInsteadOfClaimingNoGames() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val failingSource = object : SportsSource {
            override suspend fun events(league: SportsLeague, day: LocalDate): List<SportsEvent> = error("Unreadable schedule")
            override suspend fun channels(connection: XtreamConnection): List<SportsChannel> = error("Not used")
        }
        setContent { NuvioTheme { SportsHub(source = failingSource, onPlay = { _, _, _ -> }) } }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Could not refresh the schedule.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("No events scheduled for this league and date.").assertDoesNotExist()
    }

    private fun DesktopComposeUiTest.content(width: Int, height: Int, onPlay: (String, String, String) -> Unit) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                NuvioTheme {
                    Box(Modifier.requiredSize(width.dp, height.dp)) { SportsHub(source = source, onPlay = onPlay) }
                }
            }
        }
    }
    private fun DesktopComposeUiTest.screenshot(name: String) {
        val directory = File("build/reports/sports").apply { mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }
}
