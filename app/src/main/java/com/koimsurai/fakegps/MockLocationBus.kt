package com.koimsurai.fakegps

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Carries the position the service is actually broadcasting back to the UI, so the readout can show
 * the live (jittered) coordinate rather than only the fixed point the user picked. Service and UI
 * share a process, so a singleton flow is all this needs.
 */
object MockLocationBus {

    private const val METERS_PER_DEGREE_LAT = 111_320.0

    data class JitterSettings(val enabled: Boolean, val rangeMeters: Float)

    private val _liveLocation = MutableStateFlow<GeoPoint?>(null)
    val liveLocation = _liveLocation.asStateFlow()

    // The ViewModel sets isMocking optimistically the moment the user taps start, before the
    // service has actually managed to register a test provider. When that registration fails
    // (mock locations not allowed, provider held by another app, ...) the service stops itself
    // again immediately — this is how it tells the ViewModel to undo the optimistic state instead
    // of leaving the UI stuck showing "mocking" for a service that isn't actually running.
    private val _mockStartFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val mockStartFailed = _mockStartFailed.asSharedFlow()

    fun notifyMockStartFailed() {
        _mockStartFailed.tryEmit(Unit)
    }

    // The service's push loop reads this every tick instead of the value it started with, so a
    // change made in the settings sheet while mocking is already running takes effect on the very
    // next push (≤1s) rather than needing a stop/start.
    private val _jitterSettings = MutableStateFlow(JitterSettings(enabled = false, rangeMeters = 0f))
    val jitterSettings = _jitterSettings.asStateFlow()

    fun publish(lat: Double, lon: Double) {
        _liveLocation.value = GeoPoint(lat, lon)
    }

    fun clear() {
        _liveLocation.value = null
    }

    fun updateJitterSettings(enabled: Boolean, rangeMeters: Float) {
        _jitterSettings.value = JitterSettings(enabled, rangeMeters)
    }

    /** Straight-line distance in metres between the picked point and what is being broadcast. */
    fun offsetMeters(base: GeoPoint, live: GeoPoint): Double {
        val dLat = (live.latitude - base.latitude) * METERS_PER_DEGREE_LAT
        val dLon = (live.longitude - base.longitude) *
            METERS_PER_DEGREE_LAT * cos(Math.toRadians(base.latitude))
        return sqrt(dLat * dLat + dLon * dLon)
    }
}
