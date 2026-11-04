package com.nuvio.app.features.sports

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant

/** Bounded, memory-only programme cache; no source URLs or credentials are stored in it. */
internal class SportsResolver(private val sourceFactory: () -> SportsHttpSource = ::SportsHttpSource) {
    private data class Cached(val at: Instant, val programmes: List<SportsProgramme>)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    suspend fun resolve(event: SportsEvent, connection: XtreamConnection, channels: List<SportsChannel>): List<SportsPlaybackSource> =
        withContext(Dispatchers.Default) {
            if (event.isFinal) return@withContext emptyList()
            sourceFactory().use { source -> resolveSportsSources(event, connection, channels, source, cacheRead = { channel ->
                cache[connection.mappingKey(channel.id)]?.takeIf { it.at > Instant.now().minusSeconds(120) }?.programmes
            }, cacheWrite = { channel, programmes ->
                if (cache.size >= 256) cache.clear()
                cache[connection.mappingKey(channel.id)] = Cached(Instant.now(), programmes)
            }) }
        }
}

internal suspend fun resolveSportsSources(
    event: SportsEvent,
    connection: XtreamConnection,
    channels: List<SportsChannel>,
    source: SportsSource,
    cacheRead: (SportsChannel) -> List<SportsProgramme>? = { null },
    cacheWrite: (SportsChannel, List<SportsProgramme>) -> Unit = { _, _ -> },
): List<SportsPlaybackSource> = coroutineScope {
    if (event.isFinal) return@coroutineScope emptyList()
    val allowed = channels.filter { isAllowedSportsGroup(it.category) }
    val semaphore = Semaphore(6)
    val programmeEntries = sportsEpgCandidates(event, allowed).map { channel ->
        async {
            val programmes = cacheRead(channel) ?: semaphore.withPermit {
                try {
                    withTimeoutOrNull(3_000) { source.programmes(connection, channel) }?.also { cacheWrite(channel, it) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            }
            channel.id to programmes.orEmpty()
        }
    }.awaitAll().toMap()
    sportsMatches(event, allowed, programmeEntries).take(12).map {
        SportsPlaybackSource(it.channel.id, it.channel.name, connection.streamUrl(it.channel))
    }
}
