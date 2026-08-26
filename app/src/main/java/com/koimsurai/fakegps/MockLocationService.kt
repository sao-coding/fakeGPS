package com.koimsurai.fakegps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.random.Random

class MockLocationService : Service() {

    private var mockLocationProvider: MockLocationProvider? = null
    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    companion object {
        const val CHANNEL_ID = "MockLocationServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_MOCK = "com.koimsurai.fakegps.ACTION_START_MOCK"
        const val ACTION_STOP_MOCK = "com.koimsurai.fakegps.ACTION_STOP_MOCK"
        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_JITTER_ENABLED = "extra_jitter_enabled"
        const val EXTRA_JITTER_RANGE_METERS = "extra_jitter_range_meters"
        private const val METERS_PER_DEGREE_LAT = 111_320.0
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MOCK -> {
                val lat = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
                val lon = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
                val jitterEnabled = intent.getBooleanExtra(EXTRA_JITTER_ENABLED, false)
                val jitterRangeMeters = intent.getFloatExtra(EXTRA_JITTER_RANGE_METERS, 0f)
                startMockingLocation(lat, lon, jitterEnabled, jitterRangeMeters)
            }
            ACTION_STOP_MOCK -> {
                stopMockingLocation()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startMockingLocation(lat: Double, lon: Double, jitterEnabled: Boolean, jitterRangeMeters: Float) {
        // Go foreground first: the system kills the service if startForeground is never reached,
        // and setting up the test provider is exactly the step that can fail.
        startForeground(NOTIFICATION_ID, createNotification(lat, lon))

        // Seed the bus from the start intent (covers the shortcut path, which has no UI open to
        // have set it already); the loop below then re-reads it every tick, so a change made in
        // the settings sheet while this is running is not stuck with what we started with.
        MockLocationBus.updateJitterSettings(jitterEnabled, jitterRangeMeters)

        try {
            mockLocationProvider = MockLocationProvider(LocationManager.GPS_PROVIDER, this)

            serviceJob = serviceScope.launch {
                while (true) {
                    val jitter = MockLocationBus.jitterSettings.value
                    val (pushLat, pushLon) = if (jitter.enabled && jitter.rangeMeters > 0f) {
                        applyJitter(lat, lon, jitter.rangeMeters)
                    } else {
                        lat to lon
                    }
                    mockLocationProvider?.pushLocation(pushLat, pushLon)
                    MockLocationBus.publish(pushLat, pushLon)
                    delay(1000)
                }
            }
        } catch (e: SecurityException) {
            // "Allow mock locations" not granted to this app in developer options.
            notifyStartFailed(R.string.toast_enable_mock_locations)
        } catch (e: IllegalArgumentException) {
            // The system refused the test provider (e.g. another mock app holds it).
            notifyStartFailed(R.string.toast_mock_provider_unavailable)
        }
    }

    private fun notifyStartFailed(messageRes: Int) {
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_LONG).show()
        stopMockingLocation()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // The ViewModel already flipped isMocking to true optimistically when the user tapped
        // start — tell it the attempt actually failed so it can undo that.
        MockLocationBus.notifyMockStartFailed()
    }

    /** Nudges the base coordinate by a random offset within [rangeMeters], varying its trailing decimal digits — this is what makes a stationary mock location read as a real, slightly noisy GPS fix. */
    private fun applyJitter(lat: Double, lon: Double, rangeMeters: Float): Pair<Double, Double> {
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val dLat = (Random.nextDouble(-1.0, 1.0) * rangeMeters) / METERS_PER_DEGREE_LAT
        val dLon = (Random.nextDouble(-1.0, 1.0) * rangeMeters) / metersPerDegreeLon
        return (lat + dLat) to (lon + dLon)
    }

    private fun stopMockingLocation() {
        serviceJob?.cancel()
        mockLocationProvider?.shutdown()
        mockLocationProvider = null
        MockLocationBus.clear()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(lat: Double, lon: Double): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP_MOCK
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, lat, lon))
            .setSmallIcon(R.drawable.ic_map)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_action_stop), stopPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMockingLocation()
        super.onDestroy()
    }
}