package com.nuvio.app.features.sports

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal typealias SportsRelease = (() -> Unit, (String) -> Unit) -> Unit

/** One session, one native stream at a time. All terminal callbacks are claimed once. */
internal class SportsPlaybackSession(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onExited: () -> Unit,
) {
    internal class Attempt(val token: Int, val source: SportsPlaybackSource)
    var attempt: Attempt? by mutableStateOf(null)
        private set
    var message: String? by mutableStateOf(null)
        private set
    var resolving by mutableStateOf(true)
        private set
    var releasing by mutableStateOf(false)
        private set
    private var candidates = emptyList<SportsPlaybackSource>()
    private var nextIndex = 0
    private var generation = 0
    private var closed = false
    private var exiting = false
    private var release: SportsRelease? = null
    private var releaseClaim = 0
    private var waitingForController = false
    private var startedAt = now()
    private var lastProgressAt = startedAt
    private var lastPosition = 0L
    private var started = false
    private var paused = false

    @Synchronized fun resolved(sources: List<SportsPlaybackSource>) {
        if (closed || !resolving) return
        resolving = false
        candidates = sources.filter { it.url.isNotBlank() }.distinctBy { it.url }.take(8)
        if (candidates.isEmpty()) message = "No matching stream available" else startNext()
    }

    private fun startNext() {
        release = null
        waitingForController = false
        releasing = false
        message = null
        if (nextIndex >= candidates.size) {
            attempt = null
            candidates = emptyList()
            message = "No working stream"
            return
        }
        startedAt = now()
        lastProgressAt = startedAt
        lastPosition = 0L
        started = false
        paused = false
        attempt = Attempt(++generation, candidates[nextIndex++])
    }

    @Synchronized fun controllerReady(token: Int, barrier: SportsRelease) {
        if (closed || attempt?.token != token) return
        release = barrier
        if (waitingForController) {
            waitingForController = false
            beginRelease()
        }
    }

    @Synchronized fun snapshot(token: Int, snapshot: PlayerPlaybackSnapshot) {
        if (closed || releasing || attempt?.token != token) return
        if (snapshot.isEnded) { failed(token); return }
        val time = now()
        // The engine reports a deliberate pause as not playing and not buffering.
        val wasPaused = paused
        paused = !snapshot.isPlaying && !snapshot.isLoading
        if (paused || wasPaused) {
            lastProgressAt = time
            startedAt = time
        }
        if (snapshot.positionMs > lastPosition || (snapshot.isPlaying && snapshot.videoWidth > 0 && snapshot.videoHeight > 0)) {
            if (!started) lastProgressAt = time
            started = true
        }
        if (snapshot.positionMs != lastPosition) {
            lastPosition = snapshot.positionMs
            lastProgressAt = time
        }
    }

    @Synchronized fun tick() {
        val current = attempt ?: return
        if (closed || releasing || paused || message != null) return
        val time = now()
        if ((!started && time - startedAt >= 12_000) || (started && time - lastProgressAt >= 20_000)) {
            failed(current.token)
        }
    }

    @Synchronized fun failed(token: Int) {
        if (closed || releasing || message != null || attempt?.token != token) return
        releasing = true
        beginRelease()
    }

    @Synchronized fun backFromAttempt(token: Int, barrier: SportsRelease) {
        if (attempt?.token == token) back(barrier)
    }

    @Synchronized fun back(barrier: SportsRelease? = null) {
        if (closed) return
        exiting = true
        if (attempt == null) { finishExit(); return }
        if (releasing) return // An in-flight failover now completes as an exit.
        if (barrier != null) release = barrier
        message = null
        releasing = true
        beginRelease()
    }

    private fun beginRelease() {
        val barrier = release
        if (barrier == null) {
            // Never unmount a surface whose controller has not arrived yet.
            waitingForController = true
            return
        }
        val claim = ++releaseClaim
        var terminal = false
        val success = {
            synchronized(this) {
                if (!terminal && !closed && releaseClaim == claim) {
                    terminal = true
                    if (exiting) finishExit() else startNext()
                }
            }
        }
        val failure: (String) -> Unit = {
            synchronized(this) {
                if (!terminal && !closed && releaseClaim == claim) {
                    terminal = true
                    releasing = false
                    message = "Could not close stream. Use Back to try again."
                }
            }
        }
        try { barrier(success, failure) } catch (_: Throwable) { failure("") }
    }

    private fun finishExit() {
        if (closed) return
        closed = true
        candidates = emptyList()
        attempt = null
        release = null
        onExited()
    }

    /** Composition disposal invalidates all delayed resolver and native callbacks. */
    @Synchronized fun dispose() {
        closed = true
        candidates = emptyList()
        attempt = null
        release = null
    }
}

@Composable
internal actual fun SportsPlaybackScreen(
    request: SportsPlaybackRequest,
    onBack: () -> Unit,
    onSystemBackHandlerChanged: ((() -> Unit)?) -> Unit,
) {
    val currentBack by rememberUpdatedState(onBack)
    val session = remember(request) { SportsPlaybackSession(onExited = { currentBack() }) }
    SportsPlaybackSessionContent(request, session, onSystemBackHandlerChanged) { attempt ->
        PlayerScreen(
            profileId = 0,
            title = request.title,
            sourceUrl = attempt.source.url,
            sourceHeaders = mapOf("User-Agent" to SportsIptvUserAgent),
            providerName = "Sports IPTV",
            streamTitle = request.title,
            streamSubtitle = null,
            contentType = "live",
            parentMetaId = "sports:${request.eventId}",
            parentMetaType = "live",
            onBack = { barrier -> session.backFromAttempt(attempt.token, barrier) },
            onSystemBackHandlerChanged = { handler ->
                onSystemBackHandlerChanged(handler ?: { session.back() })
            },
            onPlaybackSnapshot = { session.snapshot(attempt.token, it) },
            onPlaybackFailure = { session.failed(attempt.token) },
            onPlaybackControllerReady = { controller ->
                if (controller != null) session.controllerReady(attempt.token) { success, failure ->
                    controller.releaseBeforeNavigation(success, failure)
                }
            },
        )
    }
}

/** A renderer seam allows real Compose navigation tests without opening provider streams. */
@Composable
internal fun SportsPlaybackSessionContent(
    request: SportsPlaybackRequest,
    session: SportsPlaybackSession,
    onSystemBackHandlerChanged: ((() -> Unit)?) -> Unit = {},
    player: @Composable (SportsPlaybackSession.Attempt) -> Unit,
) {
    val currentHandlerChanged by rememberUpdatedState(onSystemBackHandlerChanged)
    DisposableEffect(session) {
        currentHandlerChanged { session.back() }
        onDispose { currentHandlerChanged(null); session.dispose() }
    }
    LaunchedEffect(session) {
        val sources = try {
            withContext(Dispatchers.IO) { request.resolve() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emptyList()
        }
        session.resolved(sources)
    }
    LaunchedEffect(session) {
        while (true) { delay(500); session.tick() }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            session.attempt?.let { attempt -> key(attempt.token) { player(attempt) } }
            if (session.attempt == null || session.message != null) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(request.title, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(20.dp))
                        if (session.message == null) CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(session.message ?: "Finding a stream…")
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { session.back() }) { Text("Back") }
                    }
                }
            }
        }
    }
}
