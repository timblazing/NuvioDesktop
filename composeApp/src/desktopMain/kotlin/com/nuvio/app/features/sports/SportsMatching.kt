package com.nuvio.app.features.sports

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

internal fun normalizeSportsName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
    .replace(combiningMarks, "").replace("&", " and ").replace("+", " plus ")
    .replace(nonWords, " ").trim().replace(spaces, " ")

private val combiningMarks = Regex("\\p{M}+")
private val nonWords = Regex("[^a-z0-9]+")
private val spaces = Regex("\\s+")
private val matchupMarker = Regex("\\b(vs?|versus|at)\\b")
private val multiplexWords = Regex("\\b(pass|multiplex|redzone|conference|extra)\\b")
private val uhdQuality = Regex("\\b(4k|uhd|2160p?)\\b")
private val fhdQuality = Regex("\\b(1080p?|fhd)\\b")
private val hdQuality = Regex("\\b(720p?|hd)\\b")
private val highFrameRate = Regex("\\b(50|60)\\s*fps\\b")
private val slotNumbers = Regex("\\b\\d+\\b")
private val networkDecorations = Regex("\\b(usa|us|uk|united states|united kingdom|uhd|fhd|hd|sd|4k|1080p?|720p?|2160p?|50fps|60fps)\\b")
private val isoDate = Regex("\\b(20\\d{2})[-/.](\\d{1,2})[-/.](\\d{1,2})\\b")
private val shortDate = Regex("\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](20\\d{2}))?\\b")
private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
private val writtenDate = Regex("\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+(\\d{1,2})\\b", RegexOption.IGNORE_CASE)

private fun phrase(text: String, value: String): Boolean = value.isNotBlank() && " $text ".contains(" $value ")

/** Region is a category property, never inferred from a channel or the selected league. */
internal fun isAllowedSportsGroup(category: String): Boolean {
    val text = normalizeSportsName(category)
    val allowed = listOf("us", "usa", "united states", "uk", "united kingdom").any { phrase(text, it) }
    val foreign = foreignRegions
    return allowed && foreign.none { phrase(text, it) }
}
private val foreignRegions = listOf("canada", "canadian", "ca", "australia", "au", "ireland", "irish", "india", "indian", "france", "french", "germany", "german", "spain", "spanish", "italy", "italian", "international", "world", "global", "latin", "arab", "africa", "aus", "nz", "new zealand", "za", "south africa", "brazil", "br", "mexico", "mx", "portugal", "pt", "netherlands", "nl", "poland", "pl", "russia", "ru", "ukraine", "ua", "turkey", "tr", "qatar", "china", "cn", "japan", "jp", "korea", "kr", "de", "fr", "es", "it", "pakistan", "bangladesh", "nigeria", "caribbean")


/** Suggestions need matchup evidence, or an exact broadcast identity without competing programme data. */
internal fun sportsMatches(
    event: SportsEvent,
    channels: List<SportsChannel>,
    programmes: Map<String, List<SportsProgramme>> = emptyMap(),
    now: Instant = Instant.now(),
): List<SportsMatch> {
    val aliases = event.teams.map { teamAliases(it, event.leagueId) }
    val isBaylor = event.teams.any { normalizeSportsName(it.name).startsWith("baylor") }
    fun hits(text: String): Int = aliases.count { names -> names.any { phrase(text, it) } }
    fun both(text: String): Boolean = aliases.size >= 2 && hits(text) == aliases.size && matchupMarker.findAll(text).take(2).count() <= 1 &&
        // A school name embedded in another school's name is not the requested team.
        !(isBaylor && phrase(text, "mary hardin baylor"))
    val eventTitle = normalizeSportsName(event.name)
    fun namedEvent(text: String): Boolean = both(text) || (aliases.size < 2 && eventTitle.length >= 8 && phrase(text, eventTitle))
    val networks = event.networks.map(::networkIdentity).filter(String::isNotBlank).toSet()
    val dates = eventDates(event.start)
    val categoryTexts = channels.map { it.category }.distinct().associateWith(::normalizeSportsName)
    val categories = categoryTexts.keys.associateWith(::isAllowedSportsGroup)
    return channels.asSequence().filter { categories[it.category] == true }.mapNotNull { channel ->
        val name = normalizeSportsName(channel.name)
        if (wrongSport(name + " " + categoryTexts.getValue(channel.category), event.leagueId) || wrongDate(channel.name, dates) ||
            listOf("replay", "rerun", "highlights", "classic").any { phrase(name, it) }) return@mapNotNull null
        val atStart = programmes[channel.id].orEmpty().filter { it.start <= event.start && it.end > event.start }
        // Any contradictory programme covering kickoff makes this channel unsafe to suggest.
        if (atStart.any { !namedEvent(normalizeSportsName(it.title)) || wrongSport(normalizeSportsName(it.title + " " + it.description), event.leagueId) }) return@mapNotNull null
        val evidence = when {
            atStart.any { namedEvent(normalizeSportsName(it.title)) } -> 1000 to if (aliases.size >= 2) "Both teams in programme at event start" else "Event title in programme at event start"
            both(name) -> 900 to "Both teams in channel name"
            aliases.size < 2 && eventTitle.length >= 8 && phrase(name, eventTitle) -> 850 to "Event title in channel name"
            hits(name) > 0 || hasMatchup(name) -> return@mapNotNull null
            !event.isFinal && now >= event.start.minusSeconds(20 * 60) && now < event.start.plusSeconds(6 * 60 * 60) &&
                !multiplexWords.containsMatchIn(name) && networks.contains(networkIdentity(channel.name)) && networkIdentity(channel.name).isNotBlank() -> 400 to "Broadcast network suggestion"
            else -> return@mapNotNull null
        }
        SportsMatch(channel, evidence.second, evidence.first + streamQuality(name))
    }.sortedWith(compareByDescending<SportsMatch> { it.rank }.thenBy { it.channel.name }).toList()
}

private fun teamAliases(team: SportsTeam, league: String): Set<String> {
    val full = normalizeSportsName(team.name)
    val short = normalizeSportsName(team.shortName)
    val names = mutableSetOf(full)
    if (short.length >= 3 && !(league == "nba" && short in setOf("los angeles", "new york")) &&
        !(league in setOf("ncaaf", "ncaam") && !full.startsWith(short) && full.endsWith(" $short"))) names += short
    if (league == "nba") {
        (nbaTeams.firstOrNull { phrase(full, it.second) } ?: nbaTeams.firstOrNull { phrase(full, it.first) })?.let { (city, nickname) ->
            names += nickname
            if (city !in setOf("los angeles", "new york")) names += city
        }
    }
    if (league in setOf("epl", "ucl")) {
        footballAliases.firstOrNull { group -> group.any { phrase(full, it) || short == it } }?.let { names += it }
    }
    if (league in setOf("ncaaf", "ncaam")) {
        collegeAliases.firstOrNull { group -> group.any { phrase(full, it) || short == it } }?.let { names += it }
    }
    return names.filter { it.length >= 3 }.toSet()
}

private val collegeAliases = listOf(
    setOf("ucf", "central florida"), setOf("lsu", "louisiana state"), setOf("usc", "southern california"),
    setOf("ucla", "california los angeles"), setOf("tcu", "texas christian"), setOf("smu", "southern methodist"),
    setOf("byu", "brigham young"), setOf("utep", "texas el paso"), setOf("utsa", "texas san antonio"),
    setOf("pitt", "pittsburgh"), setOf("ole miss"), setOf("umass", "massachusetts"),
)
private val nbaTeams = listOf(
    "atlanta" to "hawks", "boston" to "celtics", "brooklyn" to "nets", "charlotte" to "hornets",
    "chicago" to "bulls", "cleveland" to "cavaliers", "dallas" to "mavericks", "denver" to "nuggets",
    "detroit" to "pistons", "golden state" to "warriors", "houston" to "rockets", "indiana" to "pacers",
    "los angeles" to "clippers", "los angeles" to "lakers", "memphis" to "grizzlies", "miami" to "heat",
    "milwaukee" to "bucks", "minnesota" to "timberwolves", "new orleans" to "pelicans", "new york" to "knicks",
    "oklahoma city" to "thunder", "orlando" to "magic", "philadelphia" to "76ers", "phoenix" to "suns",
    "portland" to "trail blazers", "sacramento" to "kings", "san antonio" to "spurs", "toronto" to "raptors",
    "utah" to "jazz", "washington" to "wizards",
)
private val footballAliases = listOf(
    setOf("manchester united", "man united", "man utd"), setOf("manchester city", "man city"),
    setOf("tottenham hotspur", "tottenham", "spurs"), setOf("wolverhampton wanderers", "wolves"),
    setOf("newcastle united", "newcastle"), setOf("nottingham forest", "nottm forest", "nott forest"),
    setOf("brighton and hove albion", "brighton"), setOf("west ham united", "west ham"),
    setOf("afc bournemouth", "bournemouth"), setOf("leicester city", "leicester"),
)

private fun hasMatchup(text: String): Boolean = matchupMarker.containsMatchIn(text)

private fun wrongSport(text: String, league: String): Boolean {
    val sport = when (league) {
        "nfl", "ncaaf" -> "football"
        "nba", "ncaam" -> "basketball"
        "nhl" -> "hockey"
        "mlb" -> "baseball"
        "epl", "ucl", "mls", "laliga" -> "soccer"
        "ufc" -> "mma"
        "f1" -> "racing"
        else -> return false
    }
    return sportLabels.any { (kind, words) -> kind != sport && words.any { phrase(text, it) } } ||
        (sport != "football" && sport != "soccer" && phrase(text, "football"))
}
private val sportLabels = mapOf("football" to listOf("nfl", "ncaaf", "american football"), "basketball" to listOf("nba", "ncaam", "basketball"),
        "hockey" to listOf("nhl", "hockey"), "baseball" to listOf("mlb", "baseball"), "soccer" to listOf("soccer", "epl", "premier league", "fc"),
        "mma" to listOf("ufc", "mma"), "racing" to listOf("f1", "formula 1"), "other" to listOf("volleyball", "tennis", "cricket", "rugby", "golf", "softball"))


/** Strip only provider decorations; extra words or digits prevent a network fallback. */
private fun networkIdentity(raw: String): String {
    var text = normalizeSportsName(raw)
    text = text.replace(networkDecorations, " ")
        .trim().replace(spaces, " ")
    return text
}

private fun streamQuality(name: String): Int {
    val resolution = when {
        uhdQuality.containsMatchIn(name) -> 40
        fhdQuality.containsMatchIn(name) -> 30
        hdQuality.containsMatchIn(name) -> 20
        else -> 10
    }
    return resolution + if (highFrameRate.containsMatchIn(name)) 1 else 0
}

private val eventZones = listOf(ZoneOffset.UTC, ZoneId.of("America/New_York"), ZoneId.of("America/Chicago"), ZoneId.of("America/Denver"), ZoneId.of("America/Los_Angeles"), ZoneId.of("Europe/London"))
private fun eventDates(start: Instant): Set<LocalDate> = eventZones.map { start.atZone(it).toLocalDate() }.toSet()

private fun wrongDate(name: String, dates: Set<LocalDate>): Boolean {
    val iso = isoDate.findAll(name).toList()
    if (iso.any { match -> runCatching { LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()) !in dates }.getOrDefault(true) }) return true
    if (iso.isNotEmpty()) return false
    if (writtenDate.findAll(name).any { dates.none { date -> monthNames.indexOf(it.groupValues[1].lowercase(Locale.ROOT).take(3)) + 1 == date.monthValue && it.groupValues[2].toInt() == date.dayOfMonth } }) return true
    return shortDate.findAll(name).any { match ->
        val year = match.groupValues[3].toIntOrNull()
        dates.none { date -> (year == null || year == date.year) && match.groupValues[1].toInt() == date.monthValue && match.groupValues[2].toInt() == date.dayOfMonth }
    }
}

/** This broader list is only for EPG discovery; it must never be exposed as playable matches. */
internal fun sportsEpgCandidates(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 48): List<SportsChannel> {
    if (limit <= 0) return emptyList()
    val aliases = event.teams.map { teamAliases(it, event.leagueId) }
    val networks = event.networks.map(::networkIdentity).toSet()
    val leagueLabels = when (event.leagueId) {
        "ncaaf" -> listOf("ncaaf", "ncaa", "college football", "espn", "fox", "abc", "cbs", "nbc")
        "ncaam" -> listOf("ncaam", "ncaa", "college basketball", "espn", "fox", "cbs")
        "epl" -> listOf("epl", "premier league", "sky sports", "tnt sports", "nbc", "usa network", "peacock")
        "ucl" -> listOf("ucl", "champions league", "tnt sports", "cbs", "paramount")
        else -> listOf(event.leagueId)
    }
    val genericSports = listOf("espn", "espn2", "espnu", "espnews", "sky sports", "tnt sports", "fox sports", "fs1", "fs2", "cbs sports", "nbc sports", "sportsnet", "tsn")
    val dates = eventDates(event.start)
    val categoryTexts = channels.map { it.category }.distinct().associateWith(::normalizeSportsName)
    val categories = categoryTexts.keys.associateWith(::isAllowedSportsGroup)
    val tiers = channels.distinctBy { it.id }.filter { categories[it.category] == true }.mapNotNull { channel ->
        val name = normalizeSportsName(channel.name)
        if (wrongSport(name + " " + categoryTexts.getValue(channel.category), event.leagueId) || wrongDate(channel.name, dates)) return@mapNotNull null
        val both = aliases.size >= 2 && aliases.all { team -> team.any { phrase(name, it) } }
        val tier = when {
            both -> 0
            networkIdentity(channel.name) in networks -> 1
            leagueLabels.any { phrase(name + " " + categoryTexts.getValue(channel.category), it) } -> 2
            genericSports.any { phrase(name, it) } -> 2
            else -> return@mapNotNull null
        }
        tier to channel
    }.groupBy({ it.first }, { it.second })
    val result = mutableListOf<SportsChannel>()
    for (tier in tiers.keys.sorted()) {
        // Round-robin categories and network families so duplicates cannot consume the entire budget.
        val buckets = tiers.getValue(tier).sortedByDescending { streamQuality(normalizeSportsName(it.name)) }
            .groupBy { normalizeSportsName(it.category) + ":" + networkFamily(it.name) }.values.map { it.iterator() }
        while (result.size < limit && buckets.any { it.hasNext() }) {
            for (bucket in buckets) {
                if (result.size == limit) break
                if (bucket.hasNext()) result += bucket.next()
            }
        }
        if (result.size == limit) break
    }
    return result
}

private fun networkFamily(name: String): String {
    val text = normalizeSportsName(name)
    return listOf("espn", "sky sports", "tnt sports", "fox", "nbc", "cbs").firstOrNull { text.contains(it) }
        ?: text.replace(slotNumbers, "").trim()
}
