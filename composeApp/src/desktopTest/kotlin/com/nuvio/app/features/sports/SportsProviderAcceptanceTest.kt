package com.nuvio.app.features.sports

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** Opt-in private acceptance check. Never include credentials or URLs in test output. */
class SportsProviderAcceptanceTest {
    @Test fun providerLoginDiscoveryAndStreamBytes() = runBlocking {
        val file = System.getenv("NUVIO_SPORTS_SECRET_FILE")
        assumeTrue("Live provider check requires a private credential file", !file.isNullOrBlank())
        val source = SportsHttpSource()
        val stream = HttpClient(CIO) {
            followRedirects = true
            install(HttpTimeout) { requestTimeoutMillis = 25_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 15_000 }
        }
        var stage = "private configuration"
        try {
            val input = Json.parseToJsonElement(File(file!!).readText()).jsonObject
            fun value(key: String) = input.getValue(key).jsonPrimitive.content
            val connection = XtreamConnection(value("server"), value("username"), value("password"), SportsStreamFormat.HLS)
            stage = "provider login and discovery"
            val channels = source.channels(connection)
            assertTrue(channels.isNotEmpty())
            val channel = channels.firstOrNull { Regex("(?i)\\bespn\\b").containsMatchIn(it.name) }
                ?: channels.firstOrNull { it.category.contains("sport", ignoreCase = true) }
                ?: channels.first()
            var url = connection.streamUrl(channel)
            var media = false
            var manifests = 0
            repeat(4) {
                if (!media) {
                    stage = "stream request after $manifests playlists"
                    var effectiveUrl = url
                    val bytes = stream.prepareGet(url) { header("User-Agent", SportsIptvUserAgent) }.execute { response ->
                        check(response.status.value in 200..299)
                        effectiveUrl = response.call.request.url.toString()
                        val buffer = ByteArray(32_768)
                        val body = response.bodyAsChannel()
                        var count = 0
                        while (count < buffer.size) {
                            val read = body.readAvailable(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                        check(count > 0)
                        buffer.copyOf(count)
                    }
                    val text = bytes.toString(Charsets.UTF_8)
                    if (text.trimStart().startsWith("#EXTM3U")) {
                        manifests++
                        val next = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                            ?: error("No media URI")
                        url = URI(effectiveUrl).resolve(next).toString()
                    } else {
                        // Accept MPEG-TS sync bytes or an ISO BMFF (fMP4) box, not HTML/JSON.
                        media = bytes[0] == 0x47.toByte() || (bytes.size > 8 && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) in setOf("ftyp", "styp", "moof"))
                    }
                }
            }
            assertTrue(media, "Provider stream did not return recognizable media bytes")
            println("PRIVATE ACCEPTANCE PASS: login, ${channels.size} live channels, $manifests HLS playlists, media bytes received. Native playback remains a separate check.")
        } catch (error: Exception) {
            // Network exception messages may contain the credential-bearing URL.
            fail("Private acceptance failed at $stage (${error.javaClass.simpleName}). No URLs or credential values logged.")
        } finally { source.close(); stream.close() }
    }
}
