package com.nuvio.app.features.sports

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SportsMatchingTest {
    private val kickoff = Instant.parse("2026-10-30T23:00:00Z")
    private fun team(name: String, short: String) = SportsTeam(name, name, short, null, null)
    private fun event(league: String = "ncaaf", teams: List<SportsTeam> = listOf(team("Baylor Bears", "Baylor"), team("UCF Knights", "UCF")), networks: List<String> = listOf("ESPN")) =
        SportsEvent("event", league, teams.joinToString(" at ") { it.name }, kickoff, "in", "", teams, networks)
    private fun channel(id: String, name: String, group: String = "USA | SPORTS") = SportsChannel(id, name, group)
    private fun ids(event: SportsEvent, channels: List<SportsChannel>, programmes: Map<String, List<SportsProgramme>> = emptyMap()) =
        sportsMatches(event, channels, programmes, kickoff).map { it.channel.id }

    @Test fun categoriesRequireExplicitUnambiguousUsOrUkMarkers() {
        listOf("US | SPORTS", "ᵁˢ | SPORTS", "USA ESPN", "United States | NCAA", "UK Sports", "United Kingdom | Football").forEach { assertTrue(isAllowedSportsGroup(it), it) }
        listOf("SPORTS", "NBA", "Australia Sports", "RUSSIA", "UKRAINE", "Canada", "USA / Canada", "US International", "").forEach { assertFalse(isAllowedSportsGroup(it), it) }
    }

    @Test fun baylorRegressionRejectsOtherSchoolSportGameRegionAndDate() {
        val channels = listOf(
            channel("correct", "Baylor Bears vs UCF Knights HD"),
            channel("alias", "Central Florida at Baylor FHD", "UK | COLLEGE FOOTBALL"),
            channel("other-school", "Mary Hardin-Baylor vs UCF Knights"),
            channel("volleyball", "Baylor vs UCF Volleyball"),
            channel("fc", "Baylor FC vs UCF"),
            channel("schreiner", "Mary Hardin Baylor vs Schreiner"),
            channel("shared", "Baylor at Texas Tech ESPN"),
            channel("stale", "Baylor vs UCF 2026-10-23"),
            channel("future", "Baylor vs UCF 11/06"),
            channel("foreign", "Baylor vs UCF", "CA | SPORTS"),
            channel("generic", "Baylor vs UCF", "SPORTS"),
            channel("replay", "Baylor vs UCF Replay"),
            channel("single", "Baylor Football"),
        )
        assertEquals(listOf("alias", "correct"), ids(event(), channels))
    }

    @Test fun exactNetworksDoNotConfuseNumberedOrPlusVariantsOrMatchDifferentGames() {
        val channels = listOf(channel("espn", "US | ESPN FHD"), channel("two", "ESPN2 UHD"), channel("u", "ESPNU"),
            channel("plus", "ESPN+"), channel("news", "ESPNews"), channel("game", "Texas vs Oklahoma ESPN"), channel("pass", "NBA League Pass"))
        assertEquals(listOf("espn"), ids(event(), channels))
        assertEquals(listOf("two"), ids(event(networks = listOf("ESPN2")), channels))
        assertEquals(listOf("plus"), ids(event(networks = listOf("ESPN+")), channels))
        assertEquals(listOf("main"), ids(event(networks = listOf("Sky Sports Main Event")), listOf(channel("main", "Sky Sports Main Event HD"), channel("football", "Sky Sports Football UHD"))))
    }

    @Test fun epgMustCoverKickoffAndCannotConflictWithTheMatchup() {
        val channels = listOf(channel("verified", "NCAA 01"), channel("conflict", "Baylor vs UCF 4K"), channel("early", "NCAA 02"), channel("network", "ESPN UHD"))
        val epg = mapOf(
            "verified" to listOf(SportsProgramme("Central Florida at Baylor", kickoff.minusSeconds(300), kickoff.plusSeconds(10000))),
            "conflict" to listOf(SportsProgramme("Baylor at Texas Tech", kickoff.minusSeconds(300), kickoff.plusSeconds(10000))),
            "early" to listOf(SportsProgramme("Baylor vs UCF", kickoff.minusSeconds(10000), kickoff)),
            "network" to listOf(SportsProgramme("Texas vs Oklahoma", kickoff.minusSeconds(300), kickoff.plusSeconds(10000))),
        )
        assertEquals(listOf("verified"), ids(event(), channels, epg))
        assertTrue(sportsMatches(event(), channels, epg, kickoff).single().reason.contains("programme"))
    }

    @Test fun evidenceOutranksQualityAndQualityThenFrameRateSortWithinEvidence() {
        val channels = listOf(channel("network", "ESPN UHD 60fps"), channel("hd", "Baylor vs UCF HD"),
            channel("fhd", "Baylor vs UCF FHD"), channel("fhd60", "Baylor vs UCF FHD 60fps"), channel("uhd", "Baylor vs UCF UHD"), channel("epg", "NCAA SD"))
        val epg = mapOf("epg" to listOf(SportsProgramme("Baylor vs UCF", kickoff, kickoff.plusSeconds(10000))))
        assertEquals(listOf("epg", "uhd", "fhd60", "fhd", "hd", "network"), ids(event(), channels, epg))
    }

    @Test fun nbaCitiesAndNicknamesAndEplAliasesMatchBothSides() {
        val nba = event("nba", listOf(team("Los Angeles Lakers", "Lakers"), team("Boston Celtics", "Celtics")))
        assertEquals(listOf("yes"), ids(nba, listOf(channel("yes", "Lakers at Boston"), channel("no", "Clippers at Boston"))))
        val epl = event("epl", listOf(team("Manchester United", "Man United"), team("Tottenham Hotspur", "Tottenham")))
        assertEquals(listOf("yes"), ids(epl, listOf(channel("yes", "Man Utd vs Spurs"), channel("no", "Man City vs Spurs"))))
    }

    @Test fun datesAcceptPlausibleUsAndUkLocalDaysAroundUtcMidnight() {
        val lateEvent = event().copy(start = Instant.parse("2026-10-31T01:00:00Z"))
        val channels = listOf(channel("us", "Baylor vs UCF Oct 30"), channel("utc", "Baylor vs UCF 2026-10-31"), channel("old", "Baylor vs UCF Oct 29"))
        assertEquals(setOf("us", "utc"), ids(lateEvent, channels).toSet())
    }

    @Test fun futureAndFinalEventsDoNotSuggestBareNetworks() {
        val channel = channel("network", "ESPN HD")
        assertTrue(sportsMatches(event(), listOf(channel), now = kickoff.minusSeconds(3600)).isEmpty())
        assertTrue(sportsMatches(event().copy(state = "post"), listOf(channel), now = kickoff).isEmpty())
        val epg = mapOf("network" to listOf(SportsProgramme("Baylor vs UCF", kickoff, kickoff.plusSeconds(10000))))
        assertEquals(listOf("network"), sportsMatches(event(), listOf(channel), epg, kickoff.minusSeconds(3600)).map { it.channel.id })
    }

    @Test fun lookupCandidatesAreBroaderBoundedBalancedAndStillNeedFinalEvidence() {
        val channels = (1..60).map { channel("slot$it", "NCAA $it", "USA | NCAA") } +
            listOf(channel("uk", "Sky Sports Main Event", "UK | SPORTS"), channel("network", "ESPN HD"), channel("foreign", "NCAA 1", "CA | NCAA"), channel("other", "NBA 1"))
        val candidates = sportsEpgCandidates(event(), channels, 6)
        assertEquals(6, candidates.size)
        assertTrue(candidates.any { it.id == "network" })
        assertTrue(candidates.any { it.id == "uk" })
        assertFalse(candidates.any { it.id in setOf("foreign", "other") })
        assertEquals(listOf("network"), ids(event(), candidates))
        assertTrue(sportsEpgCandidates(event(), channels, 0).isEmpty())
    }
}
