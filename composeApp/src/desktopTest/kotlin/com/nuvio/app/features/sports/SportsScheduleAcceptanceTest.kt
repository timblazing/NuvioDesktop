package com.nuvio.app.features.sports

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Real HTTP → parser → native UI; no IPTV credentials required. */
@OptIn(ExperimentalTestApi::class)
class SportsScheduleAcceptanceTest {
    @Test fun realEspnEventsAppearInTheNativeHub() {
        assumeTrue("Enable live schedule checks explicitly", System.getenv("NUVIO_SPORTS_LIVE_SCHEDULES") == "1")
        val http = SportsHttpSource()
        // Stable known game day reproducing the original empty-event report.
        val gameDay = LocalDate.of(2026, 10, 30)
        try {
            val nba = sportsLeagues.first { it.id == "nba" }
            val games = runBlocking { http.events(nba, gameDay) }
            assertTrue(games.isNotEmpty(), "ESPN NBA schedule should contain games on the known date")
            val source = object : SportsSource {
                override suspend fun events(league: SportsLeague, day: LocalDate) = http.events(league, gameDay)
                override suspend fun channels(connection: XtreamConnection) = error("Not used by schedule acceptance")
            }
            runDesktopComposeUiTest(width = 1440, height = 900) {
                setContent { NuvioTheme { SportsHub(source = source, onPlay = { _, _, _ -> }) } }
                onNodeWithText("NBA", substring = false).performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Find channels").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("${games.size} events ·", substring = true).assertExists()
                val directory = File("build/reports/sports").apply { mkdirs() }
                ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(directory, "live-nba.png"))
            }
            println("LIVE SCHEDULE PASS: ${games.size} real ESPN NBA events parsed and displayed in the native hub.")
        } finally { http.close() }
    }
}
