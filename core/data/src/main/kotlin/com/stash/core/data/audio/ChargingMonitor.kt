package com.stash.core.data.audio

import android.content.Context
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Is the device on external power right now?
 *
 * Sampled at decision time rather than observed — same shape and reasoning
 * as `ConnectivityMonitor`: a single `BatteryManager.isCharging` read is
 * cheap, and the callers here are one-shot "should I do this expensive thing
 * now?" checks, so a Flow would be machinery without a reader.
 *
 * Open so tests can substitute an answer without a Robolectric context.
 */
@Singleton
open class ChargingMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    open fun isCharging(): Boolean = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.isCharging
    }.getOrDefault(false)
}
