package com.nuvio.app.features.sports

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.*
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SportsPlaybackSessionTest {
    private val sources get() = (1..3).map { SportsPlaybackSource("$it", "Channel $it", "https://example.invalid/$it") }
    private class Barrier {
        var calls = 0
        lateinit var success: () -> Unit
        lateinit var failure: (String) -> Unit
        val release: SportsRelease = { success, failure ->
            calls++
            this.success = success
            this.failure = failure
        }
    }

    @Test fun nativeFailureWaitsForReleaseThenProgressesAndRejectsLateCallbacks() {
        val session = SportsPlaybackSession(onExited = {})
        session.resolved(sources)
        val first = session.attempt!!
        val barrier = Barrier()
        session.controllerReady(first.token, barrier.release)
        session.failed(first.token)
        session.failed(first.token)
        assertEquals(1, barrier.calls)
        assertSame(first, session.attempt)
        assertTrue(session.releasing)
        barrier.success()
        val second = session.attempt!!
        assertEquals("2", second.source.channelId)
        session.failed(first.token)
        session.snapshot(first.token, PlayerPlaybackSnapshot(isEnded = true))
        session.backFromAttempt(first.token, barrier.release)
        barrier.success()
        barrier.failure("private provider error")
        assertSame(second, session.attempt)
        assertNull(session.message)
    }

    @Test fun startupTimeoutAndStallHaveIndependentBounds() {
        var time = 0L
        val session = SportsPlaybackSession(now = { time }, onExited = {})
        session.resolved(sources)
        val first = session.attempt!!
        val barrier = Barrier()
        session.controllerReady(first.token, barrier.release)
        session.snapshot(first.token, PlayerPlaybackSnapshot(isLoading = true, isPlaying = true))
        time = 11_999; session.tick(); assertEquals(0, barrier.calls)
        time = 12_000; session.tick(); assertEquals(1, barrier.calls)
        barrier.success()
        val second = session.attempt!!
        val secondBarrier = Barrier()
        session.controllerReady(second.token, secondBarrier.release)
        session.snapshot(second.token, PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 1_000))
        time += 19_999; session.tick(); assertEquals(0, secondBarrier.calls)
        time++; session.tick(); assertEquals(1, secondBarrier.calls)
    }

    @Test fun pauseNeverRotatesAndResumeGetsFreshStallWindow() {
        var time = 0L
        val session = SportsPlaybackSession(now = { time }, onExited = {})
        session.resolved(sources)
        val attempt = session.attempt!!
        val barrier = Barrier()
        session.controllerReady(attempt.token, barrier.release)
        session.snapshot(attempt.token, PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 1_000))
        session.snapshot(attempt.token, PlayerPlaybackSnapshot(isLoading = false, isPlaying = false, positionMs = 1_000))
        time = 1_000_000; session.tick()
        assertEquals(0, barrier.calls)
        session.snapshot(attempt.token, PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 1_000))
        session.tick(); assertEquals(0, barrier.calls)
        time += 20_000; session.tick(); assertEquals(1, barrier.calls)
    }

    @Test fun endAndExhaustionReleaseEveryAttemptBeforeShowingBackOnlyError() {
        val session = SportsPlaybackSession(onExited = {})
        session.resolved(sources)
        sources.forEach {
            val attempt = session.attempt!!
            val barrier = Barrier()
            session.controllerReady(attempt.token, barrier.release)
            session.snapshot(attempt.token, PlayerPlaybackSnapshot(isEnded = true))
            assertSame(attempt, session.attempt)
            barrier.success()
        }
        assertNull(session.attempt)
        assertEquals("No working stream", session.message)
    }

    @Test fun backDuringFailoverExitsAfterExistingBarrierAndNeverStartsNext() {
        var exits = 0
        val session = SportsPlaybackSession(onExited = { exits++ })
        session.resolved(sources)
        val attempt = session.attempt!!
        val barrier = Barrier()
        session.controllerReady(attempt.token, barrier.release)
        session.failed(attempt.token)
        session.back()
        assertEquals(0, exits)
        assertEquals(1, barrier.calls)
        barrier.success(); barrier.success()
        assertEquals(1, exits)
        assertNull(session.attempt)
    }

    @Test fun failureBeforeControllerWaitsForControllerRelease() {
        val session = SportsPlaybackSession(onExited = {})
        session.resolved(sources)
        val attempt = session.attempt!!
        session.failed(attempt.token)
        assertSame(attempt, session.attempt)
        val barrier = Barrier()
        session.controllerReady(attempt.token, barrier.release)
        assertEquals(1, barrier.calls)
        assertSame(attempt, session.attempt)
        barrier.success()
        assertEquals("2", session.attempt!!.source.channelId)
    }

    @Test fun releaseFailureStaysClosedAndBackCanRetryWithoutLeakingFailureText() {
        var exits = 0
        val session = SportsPlaybackSession(onExited = { exits++ })
        session.resolved(sources)
        val attempt = session.attempt!!
        val barrier = Barrier()
        session.controllerReady(attempt.token, barrier.release)
        session.failed(attempt.token)
        val oldSuccess = barrier.success
        barrier.failure("https://private.invalid/user/password")
        oldSuccess()
        assertSame(attempt, session.attempt)
        assertFalse(session.message!!.contains("private"))
        session.back()
        assertEquals(2, barrier.calls)
        oldSuccess()
        assertEquals(0, exits)
        barrier.success()
        assertEquals(1, exits)
    }

    @Test fun noMatchesAndCancellationIgnoreLateResolution() {
        val empty = SportsPlaybackSession(onExited = {})
        empty.resolved(emptyList())
        assertEquals("No matching stream available", empty.message)
        var exits = 0
        val cancelled = SportsPlaybackSession(onExited = { exits++ })
        cancelled.back()
        cancelled.resolved(sources)
        assertEquals(1, exits)
        assertNull(cancelled.attempt)
        val disposed = SportsPlaybackSession(onExited = { fail("Disposed session exited") })
        disposed.dispose(); disposed.resolved(sources); disposed.back()
        assertNull(disposed.attempt)
    }

    @Test fun candidateListIsBoundedAndDeduplicated() {
        val session = SportsPlaybackSession(onExited = {})
        session.resolved((1..20).flatMap { listOf(SportsPlaybackSource("$it", "Channel", "https://example.invalid/$it")).let { it + it } })
        var attempted = 0
        while (session.attempt != null) {
            val token = session.attempt!!.token
            session.controllerReady(token) { done, _ -> done() }
            attempted++
            session.failed(token)
        }
        assertEquals(8, attempted)
    }

    @Test fun composePlayerRemainsMountedUntilNativeReleaseCompletes() = runDesktopComposeUiTest {
        val barrier = Barrier()
        val events = mutableListOf<String>()
        val session = SportsPlaybackSession(onExited = {})
        val request = SportsPlaybackRequest("Live match", "match") { sources.take(2) }
        setContent {
            MaterialTheme {
                SportsPlaybackSessionContent(request, session) { attempt ->
                    DisposableEffect(attempt.token) {
                        events += "mount ${attempt.token}"
                        session.controllerReady(attempt.token, barrier.release)
                        onDispose { events += "dispose ${attempt.token}" }
                    }
                    Button(onClick = { session.failed(attempt.token) }) { Text("Native failure ${attempt.token}") }
                }
            }
        }
        waitUntil { onAllNodesWithText("Native failure 1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Native failure 1").performClick()
        runOnIdle { assertEquals(listOf("mount 1"), events) }
        onNodeWithText("Native failure 1").assertExists()
        runOnIdle { events += "released 1"; barrier.success() }
        waitUntil { onAllNodesWithText("Native failure 2").fetchSemanticsNodes().isNotEmpty() }
        runOnIdle {
            assertTrue(events.indexOf("released 1") < events.indexOf("mount 2"))
            assertTrue(events.contains("dispose 1"))
        }
    }

    @Test fun composeBackDuringResolutionShowsNoPlayerAfterLateResolverResult() = runDesktopComposeUiTest {
        val deferred = CompletableDeferred<List<SportsPlaybackSource>>()
        var exits = 0
        var mounts = 0
        val session = SportsPlaybackSession(onExited = { exits++ })
        val request = SportsPlaybackRequest("Live match", "match") { deferred.await() }
        setContent {
            MaterialTheme {
                SportsPlaybackSessionContent(request, session) { mounts++; Text("Unexpected player") }
            }
        }
        onNodeWithText("Finding a stream…").assertExists()
        onNodeWithText("Back").performClick()
        runOnIdle { deferred.complete(sources) }
        waitForIdle()
        runOnIdle { assertEquals(1, exits); assertEquals(0, mounts) }
    }
}
