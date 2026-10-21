package com.drivealert.app.util

import android.os.SystemClock
import com.drivealert.app.domain.ports.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemClockImpl @Inject constructor() : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()

    /** Counts time in deep sleep, so stationary timeouts stay correct with the screen off. */
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}
