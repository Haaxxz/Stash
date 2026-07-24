package com.stash.core.model

/**
 * Outcome of [com.stash.core.media.PlayerRepository.startRadio]. Replaces a
 * Boolean that collapsed three distinct failures into one misleading
 * "needs Online mode" toast (issue: silent radio button).
 */
sealed interface RadioStartResult {
    /** Station built and spliced/queued; the seed label is live. */
    data object Started : RadioStartResult

    /** Streaming (Online mode) is off — radio can't stream tracks. */
    data object StreamingOff : RadioStartResult

    /** MediaController unavailable (player still starting / connection lost). */
    data object PlayerNotReady : RadioStartResult

    /** Generator produced an empty first batch — no similar tracks found. */
    data object NoStation : RadioStartResult
}
