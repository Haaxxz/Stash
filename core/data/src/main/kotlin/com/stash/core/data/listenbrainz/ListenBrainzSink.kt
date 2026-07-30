package com.stash.core.data.listenbrainz

import android.util.Log
import com.stash.core.data.listen.Listen
import com.stash.core.data.listen.ListenSink
import com.stash.core.data.listen.SinkResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ListenBrainz as a [ListenSink].
 *
 * The whole class is this short because queueing, retry, batch-splitting and
 * per-destination state all live in
 * [com.stash.core.data.listen.ListenSinkDrainer] and `listen_submissions`. That
 * was the point of extracting them: Last.fm and YouTube history each needed a
 * boolean column, three DAO queries, a schema migration and their own drain loop,
 * and this needed none of those.
 */
@Singleton
class ListenBrainzSink @Inject constructor(
    private val api: ListenBrainzApiClient,
    private val preference: ListenBrainzPreference,
) : ListenSink {

    override val id: String = TARGET_ID

    /**
     * ListenBrainz accepts far larger `import` payloads, but a music app submits a
     * handful of listens per drain in practice, and a smaller batch means a
     * rejected one is cheaper to split and isolate.
     */
    override val maxBatchSize: Int = 50

    override suspend fun isEnabled(): Boolean = preference.tokenNow() != null

    /**
     * Only listens from the moment the user connected.
     *
     * Returning [Long.MAX_VALUE] when no timestamp is stored is a deliberate
     * fail-closed: an unknown cutoff must submit nothing rather than default to
     * zero and flood ListenBrainz with the user's entire history.
     */
    override suspend fun listeningSinceMs(): Long =
        preference.connectedAtNow() ?: Long.MAX_VALUE

    override suspend fun submit(batch: List<Listen>): SinkResult {
        val token = preference.tokenNow()
            ?: return SinkResult.Transient("no token")

        return when (val response = api.submitListens(token, batch)) {
            is ListenBrainzApiClient.Response.Accepted -> SinkResult.Success

            // 401 is a dead token, and it is worth naming: the user has to paste a
            // new one, and retrying until the attempt cap silently discards those
            // listens instead of telling anybody.
            is ListenBrainzApiClient.Response.Refused -> {
                if (response.code == 401) {
                    Log.w(TAG, "token rejected — the user must reconnect ListenBrainz")
                }
                SinkResult.Rejected("HTTP ${response.code}: ${response.message}")
            }

            is ListenBrainzApiClient.Response.Unavailable ->
                SinkResult.Transient(response.message)
        }
    }

    override suspend fun nowPlaying(listen: Listen) {
        if (!preference.nowPlayingEnabledNow()) return
        val token = preference.tokenNow() ?: return
        // Best-effort by definition: a retry would land after the user moved on.
        runCatching { api.submitPlayingNow(token, listen) }
            .onFailure { Log.d(TAG, "now-playing ping failed: ${it.message}") }
    }

    companion object {
        /** Also the `listen_submissions.target` value. Never change it. */
        const val TARGET_ID = "listenbrainz"
        private const val TAG = "ListenBrainzSink"
    }
}
