package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins [idleReleaseTrigger] — the signal that lets an idle player let go of
 * the playback service.
 *
 * Holding a MediaController binds StashPlaybackService, and a process
 * hosting a live service is never cached, so Android's freezer can never
 * freeze it. Measured on a Pixel: 56 mAh over 5h49m of "cached" time, with
 * the service record still alive 1h15m after the last track played.
 *
 * The trigger fires only after playback has been stopped continuously for
 * the timeout — resuming has to take it back off the table, because
 * releasing the controller out from under an active listener would drop
 * playback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleReleaseTriggerTest {

    private val track = Track(id = 1L, title = "t", artist = "a", album = "", durationMs = 180_000)
    private val timeout = 5 * 60_000L

    @Test
    fun `does not fire while playing`() = runTest {
        val state = MutableStateFlow(PlayerState(currentTrack = track, isPlaying = true))
        var fired = 0

        backgroundScope.launch { idleReleaseTrigger(state, timeout).collect { fired++ } }
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        runCurrent()

        assertThat(fired).isEqualTo(0)
    }

    @Test
    fun `fires once playback has been paused for the timeout`() = runTest {
        val state = MutableStateFlow(PlayerState(currentTrack = track, isPlaying = true))
        var fired = 0

        backgroundScope.launch { idleReleaseTrigger(state, timeout).collect { fired++ } }
        runCurrent()
        state.value = PlayerState(currentTrack = track, isPlaying = false)
        runCurrent()

        advanceTimeBy(timeout - 1_000L)
        runCurrent()
        assertThat(fired).isEqualTo(0)

        advanceTimeBy(2_000L)
        runCurrent()
        assertThat(fired).isEqualTo(1)
    }

    @Test
    fun `resuming before the timeout cancels the release`() = runTest {
        val state = MutableStateFlow(PlayerState(currentTrack = track, isPlaying = false))
        var fired = 0

        backgroundScope.launch { idleReleaseTrigger(state, timeout).collect { fired++ } }
        runCurrent()
        advanceTimeBy(timeout / 2)
        runCurrent()

        state.value = PlayerState(currentTrack = track, isPlaying = true)
        runCurrent()
        advanceTimeBy(timeout * 2)
        runCurrent()

        assertThat(fired).isEqualTo(0)
    }

    @Test
    fun `a pause-resume-pause cycle restarts the countdown`() = runTest {
        val state = MutableStateFlow(PlayerState(currentTrack = track, isPlaying = false))
        var fired = 0

        backgroundScope.launch { idleReleaseTrigger(state, timeout).collect { fired++ } }
        runCurrent()
        advanceTimeBy(timeout / 2)
        runCurrent()

        state.value = PlayerState(currentTrack = track, isPlaying = true)
        runCurrent()
        state.value = PlayerState(currentTrack = track, isPlaying = false)
        runCurrent()

        // Half the timeout already elapsed before the resume, but the
        // countdown restarts from zero — so this must NOT fire yet.
        advanceTimeBy(timeout / 2 + 1_000L)
        runCurrent()
        assertThat(fired).isEqualTo(0)

        advanceTimeBy(timeout)
        runCurrent()
        assertThat(fired).isEqualTo(1)
    }

    @Test
    fun `does not re-fire while playback stays paused`() = runTest {
        val state = MutableStateFlow(PlayerState(currentTrack = track, isPlaying = false))
        var fired = 0

        backgroundScope.launch { idleReleaseTrigger(state, timeout).collect { fired++ } }
        runCurrent()
        advanceTimeBy(timeout * 10)
        runCurrent()

        assertThat(fired).isEqualTo(1)
    }
}
