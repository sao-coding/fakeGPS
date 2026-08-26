package com.koimsurai.fakegps

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast

class ShortcutActivity : Activity() {

    companion object {
        const val ACTION_STOP_MOCK_LOCATION = "com.koimsurai.fakegps.ACTION_STOP_MOCK_LOCATION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            "com.koimsurai.fakegps.ACTION_SET_LAST_LOCATION" -> startMockingLastLocation()
            ACTION_STOP_MOCK_LOCATION -> stopMocking()
        }
        finish()
    }

    private fun startMockingLastLocation() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val lat = prefs.getFloat("last_lat", -999f)
        val lon = prefs.getFloat("last_lon", -999f)

        if (lat == -999f || lon == -999f) {
            Toast.makeText(this, getString(R.string.toast_no_last_location_saved), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val serviceIntent = Intent(this, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_START_MOCK
                putExtra(MockLocationService.EXTRA_LATITUDE, lat.toDouble())
                putExtra(MockLocationService.EXTRA_LONGITUDE, lon.toDouble())
                // The shortcut can cold-start the app with no MainActivity/ViewModel ever having
                // run, so MockLocationBus's jitter settings would otherwise default to "off" —
                // read the same persisted prefs MainViewModel does so this matches what the user
                // last configured in the app instead of silently dropping it.
                putExtra(MockLocationService.EXTRA_JITTER_ENABLED, prefs.getBoolean("jitter_enabled", false))
                putExtra(MockLocationService.EXTRA_JITTER_RANGE_METERS, prefs.getFloat("jitter_range_meters", JitterPreset.MEDIUM))
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Toast.makeText(this, getString(R.string.toast_mock_location_started), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.toast_enable_mock_locations), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMocking() {
        if (!isServiceRunning()) {
            Toast.makeText(this, getString(R.string.toast_mock_location_not_running), Toast.LENGTH_SHORT).show()
            return
        }
        val serviceIntent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP_MOCK
        }
        startService(serviceIntent)
        Toast.makeText(this, getString(R.string.toast_mock_location_stopped), Toast.LENGTH_SHORT).show()
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == MockLocationService::class.java.name }
    }
}
