package com.nuvio.app.features.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.time.Instant
import kotlin.test.*

class SportsDataTest {
    @Test fun parsesEspnMinutePrecisionAndOffsetTimestamps() {
        // ESPN's live scoreboard uses minute precision, unlike the original test fixtures.
        listOf("2026-10-30T23:00Z", "2026-10-30T23:00:00Z", "2026-10-30T18:00-05:00").forEach { date ->
            val raw = """{"events":[{"id":"401810001","name":"Charlotte at Washington","date":"$date",
                "competitions":[{"date":"$date","status":{"type":{"state":"pre"}}}]}]}"""
            val events = parseSportsEvents(raw, "nba")
            assertEquals(1, events.size, "Schedule dropped event timestamp $date")
            assertEquals(Instant.parse("2026-10-30T23:00:00Z"), events.single().start)
        }
    }

    @Test fun distinguishesAnEmptyDayFromAnUnreadableSchedule() {
        assertTrue(parseSportsEvents("""{"events":[]}""", "nba").isEmpty())
        assertFailsWith<IllegalStateException> { parseSportsEvents("""{"events":[{"id":"bad","date":"invalid","competitions":[{}]}]}""", "nba") }
        assertFailsWith<IllegalStateException> { parseSportsEvents("""{"error":"unavailable"}""", "nba") }
    }

    @Test fun parsesScoresNetworksAndMalformedRowsWithoutDroppingTheSchedule() {
        val events = parseSportsEvents("""{"events":[
          {"id":"1","name":"Boston Celtics at Chicago Bulls","date":"2026-10-30T23:00:00Z","competitions":[{
            "status":{"type":{"state":"in","shortDetail":"4:31 - 2nd"}},
            "competitors":[{"team":{"id":"2","displayName":"Boston Celtics","shortDisplayName":"Celtics"},"score":"42"},
              {"team":{"id":"4","displayName":"Chicago Bulls"},"score":{"displayValue":"38"}}],
            "broadcasts":[{"names":["ESPN","ESPN"]}],"geoBroadcasts":[{"media":{"shortName":"ESPN2"}}]}]},
          {"id":"bad","date":"nonsense","competitions":[{}]}]}""", "nba")
        assertEquals(1, events.size)
        assertEquals("nba:1", events.single().id)
        assertTrue(events.single().isLive)
        assertEquals(listOf("42", "38"), events.single().teams.map { it.score })
        assertEquals(listOf("ESPN", "ESPN2"), events.single().networks)
    }

    @Test fun matcherUsesWordBoundariesAndPreservesNumberedNetworks() {
        val channels = listOf(SportsChannel("1", "US | ESPN HD"), SportsChannel("2", "ESPN2 FHD"), SportsChannel("3", "ESPN News"), SportsChannel("4", "ESPNews"), SportsChannel("5", "Boston Celtics vs Chicago Bulls"))
        val event = event().copy(networks = listOf("ESPN2"))
        assertEquals(listOf("5", "2"), sportsMatches(event, channels).map { it.channel.id })
        assertEquals("Both teams in channel name", sportsMatches(event, channels).first().reason)
        assertEquals("4", sportsMatches(event, channels, "4").first().channel.id)
    }

    @Test fun channelParserAcceptsNumericAndStringIdsAndIgnoresInvalidEntries() {
        val channels = parseSportsChannels(Json.parseToJsonElement("""[{"stream_id":1,"name":"ESPN","category_id":"7"},{"stream_id":"2","name":"NBA TV"},{"stream_id":"bad","name":"Bad"},{"stream_id":1,"name":"Duplicate"}]""").jsonArray, mapOf("7" to "Sports"))
        assertEquals(listOf("1", "2"), channels.map { it.id })
        assertEquals("Sports", channels.first().category)
    }

    @Test fun xtreamUrlEscapesCredentialsAndSupportsBothPlaybackFormats() {
        val c = XtreamConnection("https://example.com:8080/", "a/b", "p +?&", SportsStreamFormat.HLS)
        assertEquals("https://example.com:8080/live/a%2Fb/p%20%2B%3F%26/123.m3u8", c.streamUrl(SportsChannel("123", "ESPN")))
        assertFalse(c.toString().contains("example.com"))
        assertTrue(XtreamConnection("http://example.com", "u", "p", SportsStreamFormat.TS).streamUrl(SportsChannel("1", "TV")).endsWith(".ts"))
    }

    @Test fun xtreamRejectsPlaylistUrlsCredentialsAndUnsupportedSchemes() {
        listOf("file:///tmp/test", "https://user:pass@example.com", "https://example.com/get.php?username=a", "https://example.com/player_api.php", "example.com:8080").forEach {
            assertFailsWith<IllegalArgumentException>(it) { XtreamConnection(it, "u", "p", SportsStreamFormat.HLS) }
        }
    }

    companion object {
        internal fun event() = SportsEvent("nba:1", "nba", "Boston Celtics at Chicago Bulls", Instant.parse("2026-10-30T23:00:00Z"), "in", "4:31 - 2nd",
            listOf(SportsTeam("nba:2", "Boston Celtics", "Celtics", null, "42"), SportsTeam("nba:4", "Chicago Bulls", "Bulls", null, "38")), listOf("ESPN"))
    }
}
