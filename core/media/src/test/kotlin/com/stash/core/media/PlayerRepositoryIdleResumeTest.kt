package com.stash.core.media

import android.os.Looper
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * An idle player releases its MediaController so the playback service can
 * die and the process can finally be frozen (see [idleReleaseTrigger]).
 * Once the service is gone, ExoPlayer's timeline goes with it — so the very
 * next `play()` reconnects to a controller holding an EMPTY queue.
 *
 * Without a guard that is a dead play button: `ensureController()?.play()`
 * on an empty timeline is a silent no-op. These tests pin the fallback, and
 * it is worth having on its own merits — the OS can reclaim the service at
 * any time, which is the same "hit play, nothing happens" symptom.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryIdleResumeTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true)
    private val streamResolver: StreamSourceRegistry = mockk()
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val connectivity: ConnectivityMonitor = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val controller: MediaController = mockk(relaxed = true)
    private val playbackResumer: PlaybackResumer = mockk(relaxed = true)
    private val trackIdentityEvents: TrackIdentityEvents = mockk {
        every { changes } returns MutableSharedFlow()
    }

    private lateinit var repo: PlayerRepositoryImpl

    @Before
    fun setUp() {
        repo = PlayerRepositoryImpl(
            context = ApplicationProvider.getApplicationContext(),
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = streamingPreference,
            streamResolver = streamResolver,
            streamUrlCache = streamUrlCache,
            connectivity = connectivity,
            trackDao = trackDao,
            playbackResumer = playbackResumer,
            radioGenerator = mockk(relaxed = true),
            trackIdentityEvents = trackIdentityEvents,
        )
        repo.controllerDeferred = controller
    }

    @Test
    fun `play on an empty timeline restores the persisted queue instead of no-oping`() = runTest {
        every { controller.mediaItemCount } returns 0
        coEvery { playbackResumer.buildResumePlan() } returns null
        coEvery { trackDao.getLastPlayedTrack() } returns null
        every { trackDao.getRecentlyAdded(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())

        repo.play()
        // resumeLastQueue is deliberately fire-and-forget on the repository's
        // main-dispatcher scope, so drain the looper before asserting.
        shadowOf(Looper.getMainLooper()).idle()

        // The resume path is what rebuilds a timeline the dead service took
        // with it; calling play() straight through would do nothing at all.
        coVerify(exactly = 1) { playbackResumer.buildResumePlan() }
    }

    @Test
    fun `play with a loaded timeline plays without touching the resume path`() = runTest {
        every { controller.mediaItemCount } returns 12

        repo.play()

        verify(exactly = 1) { controller.play() }
        coVerify(exactly = 0) { playbackResumer.buildResumePlan() }
    }
}
