package com.nuvio.app.features.sports

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Inspired by homelessbrian/NuvioPlusIPTV's event-first Sports hub (GPL-3.0).
internal data class SportsLeague(val id: String, val label: String, val path: String, val query: String = "")
internal val sportsLeagues = listOf(
    SportsLeague("nba", "NBA", "basketball/nba"),
    SportsLeague("nfl", "NFL", "football/nfl"),
    SportsLeague("ncaaf", "NCAAF", "football/college-football", "80"),
    SportsLeague("epl", "EPL", "soccer/eng.1"),
)

internal data class SportsTeam(val id: String, val name: String, val shortName: String, val logo: String?, val score: String?)
internal data class SportsEvent(
    val id: String,
    val leagueId: String,
    val name: String,
    val start: Instant,
    val state: String,
    val detail: String,
    val teams: List<SportsTeam>,
    val networks: List<String>,
) {
    val isLive get() = state == "in"
    val isFinal get() = state == "post"
    val localTime: String get() = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault()).format(start)
}
internal data class SportsChannel(val id: String, val name: String, val category: String = "", val logo: String? = null)
internal data class SportsProgramme(val title: String, val start: Instant, val end: Instant, val description: String = "")
internal data class SportsMatch(val channel: SportsChannel, val reason: String, val rank: Int)
internal enum class SportsStreamFormat(val extension: String, val label: String) { HLS("m3u8", "HLS"), TS("ts", "MPEG-TS") }

/** Intentionally session-only: never serialized, included in exception messages or logged. */
internal class XtreamConnection(server: String, val username: String, private val password: String, val format: SportsStreamFormat) {
    val baseUrl = validateServer(server)
    fun streamUrl(channel: SportsChannel): String = "$baseUrl/live/${segment(username)}/${segment(password)}/${segment(channel.id)}.${format.extension}"
    internal fun passwordForRequest() = password
    fun mappingKey(eventId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((baseUrl + "\u0000" + username).toByteArray()).joinToString("") { "%02x".format(it) }
        return "channel:$digest:$eventId"
    }
    override fun toString() = "XtreamConnection(redacted)"

    companion object {
        fun validateServer(server: String): String {
            val raw = server.trim().trimEnd('/')
            val parsed = runCatching { Url(raw) }.getOrNull()
            require(parsed != null && parsed.protocol.name in setOf("http", "https") && parsed.host.isNotBlank() &&
                parsed.user == null && parsed.password == null && parsed.parameters.isEmpty() && parsed.fragment.isEmpty()) {
                "Enter an http:// or https:// server address, without credentials or query parameters."
            }
            require(parsed.encodedPath.trim('/') !in setOf("player_api.php", "get.php", "xmltv.php")) {
                "Enter the server address, without player_api.php or get.php."
            }
            return raw
        }
        private fun segment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

internal interface SportsSource {
    suspend fun events(league: SportsLeague, day: LocalDate): List<SportsEvent>
    suspend fun channels(connection: XtreamConnection): List<SportsChannel>
    suspend fun programmes(connection: XtreamConnection, channel: SportsChannel): List<SportsProgramme> = emptyList()
}
internal class SportsHttpSource : SportsSource, AutoCloseable {
    private val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 25_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 20_000 }
    }
    override suspend fun events(league: SportsLeague, day: LocalDate): List<SportsEvent> {
        val response = http.get("https://site.api.espn.com/apis/site/v2/sports/${league.path}/scoreboard") {
            parameter("dates", day.format(DateTimeFormatter.BASIC_ISO_DATE))
            parameter("limit", 1000)
            if (league.query.isNotEmpty()) parameter("groups", league.query)
        }
        check(response.status.value in 200..299) { "Schedule service unavailable (${response.status.value})." }
        return parseSportsEvents(response.bodyAsText(), league.id)
    }
    override suspend fun channels(connection: XtreamConnection): List<SportsChannel> {
        suspend fun api(action: String?): JsonElement {
            val response = http.get("${connection.baseUrl}/player_api.php") {
                header("User-Agent", SportsIptvUserAgent)
                parameter("username", connection.username)
                parameter("password", connection.passwordForRequest())
                action?.let { parameter("action", it) }
            }
            check(response.status.value in 200..299) { "Provider returned HTTP ${response.status.value}." }
            return Json.parseToJsonElement(response.bodyAsText())
        }
        val auth = api(null).jsonObject.obj("user_info")
        check(auth.text("auth") == "1" && auth.text("status").lowercase() == "active") { "Provider rejected the login or this account is inactive." }
        val categories = try {
            (api("get_live_categories") as? JsonArray).orEmpty().associate { it.jsonObject.text("category_id") to it.jsonObject.text("category_name") }
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { emptyMap() }
        val payload = api("get_live_streams") as? JsonArray ?: error("Provider returned an invalid channel list.")
        val channels = parseSportsChannels(payload, categories)
        check(channels.isNotEmpty()) { "This account returned no live channels." }
        return channels
    }
    override suspend fun programmes(connection: XtreamConnection, channel: SportsChannel): List<SportsProgramme> {
        val response = http.get("${connection.baseUrl}/player_api.php") {
            header("User-Agent", SportsIptvUserAgent)
            parameter("username", connection.username)
            parameter("password", connection.passwordForRequest())
            parameter("action", "get_short_epg")
            parameter("stream_id", channel.id)
            parameter("limit", 12)
        }
        check(response.status.value in 200..299) { "Programme data unavailable." }
        return parseSportsProgrammes(response.bodyAsText())
    }
    override fun close() = http.close()
}

internal fun parseSportsChannels(payload: JsonArray, categories: Map<String, String>): List<SportsChannel> = payload.mapNotNull {
    val row = it as? JsonObject ?: return@mapNotNull null
    val id = row.text("stream_id").takeIf { id -> id.toLongOrNull() != null } ?: return@mapNotNull null
    val name = row.text("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
    SportsChannel(id, name, categories[row.text("category_id")].orEmpty(), row.text("stream_icon").takeIf { it.startsWith("https://") })
}.distinctBy { it.id }

internal fun parseSportsEvents(raw: String, leagueId: String): List<SportsEvent> {
    val scheduled = Json.parseToJsonElement(raw).jsonObject["events"] as? JsonArray
        ?: error("Schedule response is missing its events list.")
    val parsed = scheduled.mapNotNull { item ->
        val event = item as? JsonObject ?: return@mapNotNull null
        val id = event.text("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val competition = event.array("competitions").firstOrNull() as? JsonObject ?: return@mapNotNull null
        val start = runCatching { OffsetDateTime.parse(competition.text("date").ifBlank { event.text("date") }).toInstant() }.getOrNull() ?: return@mapNotNull null
        val status = (competition["status"] as? JsonObject ?: event.obj("status")).obj("type")
        val teams = competition.array("competitors").mapNotNull { competitor ->
            val c = competitor as? JsonObject ?: return@mapNotNull null
            val team = (c["team"] as? JsonObject ?: c["athlete"] as? JsonObject) ?: return@mapNotNull null
            val name = team.text("displayName").ifBlank { team.text("name") }
            val score = when (val s = c["score"]) { is JsonObject -> s.text("displayValue"); is JsonPrimitive -> s.contentOrNull; else -> null }
            SportsTeam("$leagueId:${team.text("id")}", name, team.text("shortDisplayName").ifBlank { name }, team.text("logo").takeIf { it.startsWith("https://") }, score)
        }
        val networks = competition.array("broadcasts").flatMap { b -> (b as? JsonObject)?.array("names").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull } } +
            competition.array("geoBroadcasts").mapNotNull { (it as? JsonObject)?.obj("media")?.text("shortName")?.takeIf(String::isNotBlank) }
        SportsEvent("$leagueId:$id", leagueId, event.text("name"), start, status.text("state"), status.text("shortDetail").ifBlank { status.text("detail") }, teams, networks.distinct())
    }.distinctBy { it.id }.sortedBy { it.start }
    check(scheduled.isEmpty() || parsed.isNotEmpty()) { "Schedule contained events but none could be read." }
    return parsed
}

private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.array(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun parseSportsProgrammes(raw: String): List<SportsProgramme> =
    Json.parseToJsonElement(raw).jsonObject.array("epg_listings").mapNotNull { item ->
        val row = item as? JsonObject ?: return@mapNotNull null
        val start = row.text("start_timestamp").toLongOrNull() ?: return@mapNotNull null
        val end = row.text("end_timestamp").ifBlank { row.text("stop_timestamp") }.toLongOrNull() ?: return@mapNotNull null
        val title = decodeProgrammeText(row.text("title"))
        if (title.isBlank() || end <= start) return@mapNotNull null
        SportsProgramme(title, Instant.ofEpochSecond(start), Instant.ofEpochSecond(end), decodeProgrammeText(row.text("description")))
    }

private fun decodeProgrammeText(value: String): String = runCatching {
    val bytes = java.util.Base64.getDecoder().decode(value)
    val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    decoded.takeIf { text -> text.none { it.isISOControl() && it !in "\n\r\t" } } ?: value
}.getOrDefault(value)
