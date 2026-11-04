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
        override suspend fun events(league: SportsLeague, day: LocalDate) = if (league.id == "nba") listOf(SportsDataTest.event()) else emptyList()
        override suspend fun channels(connection: XtreamConnection) = listOf(SportsChannel("123", "US | ESPN HD", "USA | SPORTS"), SportsChannel("124", "ESPN HD", "CA | SPORTS"))
    }

    @Test fun clickingACardConnectsThenOpensPlaybackWithOnlyUsOrUkChannels() = runDesktopComposeUiTest(width = 1440, height = 900) {
        var played: SportsPlaybackRequest? = null
        var resolvedChannels: List<SportsChannel>? = null
        content(1440, 900, onPlay = { played = it }) { _, _, channels -> resolvedChannels = channels; emptyList() }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Celtics").fetchSemanticsNodes().isNotEmpty() }
        screenshot("desktop")
        onNodeWithText("Find channels").assertDoesNotExist()
        onNode(hasContentDescription("Watch", substring = true)).performClick()
        onNodeWithText("Server URL").performTextInput("https://example.com")
        onNodeWithText("Username").performTextInput("test-user")
        onNodeWithText("Password").performTextInput("test-password")
        onNodeWithText("Connect", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) { played != null }
        kotlinx.coroutines.runBlocking { played!!.resolve() }
        assertEquals(listOf("123"), resolvedChannels?.map { it.id })
        onNodeWithText("IPTV connected").assertExists()
        onNodeWithText("NFL", substring = false).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("No events scheduled for this league and date.").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun narrowWindowShowsLeagueFilters() = runDesktopComposeUiTest(width = 393, height = 852) {
        content(393, 852, onPlay = { fail("Must connect before playing") })
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Celtics").fetchSemanticsNodes().isNotEmpty() }
        screenshot("narrow")
        listOf("NBA", "NFL", "NCAAF", "EPL").forEach { onNodeWithText(it, substring = false).assertExists() }
        onNodeWithText("Live", substring = false).performClick()
        onNodeWithText("Celtics").assertExists()
    }

    @Test fun unreadableScheduleShowsAnErrorInsteadOfClaimingNoGames() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val failingSource = object : SportsSource {
            override suspend fun events(league: SportsLeague, day: LocalDate): List<SportsEvent> = error("Unreadable schedule")
            override suspend fun channels(connection: XtreamConnection): List<SportsChannel> = error("Not used")
        }
        setContent { NuvioTheme { SportsHub(source = failingSource, onPlay = {}) } }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Could not refresh the schedule.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("No events scheduled for this league and date.").assertDoesNotExist()
    }

    private fun DesktopComposeUiTest.content(
        width: Int, height: Int, onPlay: (SportsPlaybackRequest) -> Unit,
        resolve: suspend (SportsEvent, XtreamConnection, List<SportsChannel>) -> List<SportsPlaybackSource> = { _, _, _ -> emptyList() },
    ) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                NuvioTheme {
                    Box(Modifier.requiredSize(width.dp, height.dp)) { SportsHub(source = source, onPlay = onPlay, resolveSources = resolve) }
                }
            }
        }
    }
    private fun DesktopComposeUiTest.screenshot(name: String) {
        val directory = File("build/reports/sports").apply { mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }
}
