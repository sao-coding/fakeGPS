package com.koimsurai.fakegps

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.io.IOException

/** What's missing before a mock run can actually succeed — checked before, not after, tapping start. */
enum class PreflightIssue {
    LOCATION_SERVICES_OFF,
    MOCK_LOCATION_NOT_ALLOWED
}

/** Jitter presets, in meters — matches the "small/medium/large" choices in the settings sheet. */
object JitterPreset {
    const val SMALL = 3f
    const val MEDIUM = 10f
    const val LARGE = 30f
}

data class MainUiState(
    val selectedPoint: GeoPoint? = null,
    val isMocking: Boolean = false,
    val isServiceRunning: Boolean = false,
    val favorites: Map<String, GeoPoint> = emptyMap(),
    val mapVisible: Boolean = true,
    val searchInput: String = "",
    val isLocating: Boolean = false,
    val jitterEnabled: Boolean = false,
    val jitterRangeMeters: Float = JitterPreset.MEDIUM
)

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // The service can fail to actually start after isMocking was already set optimistically
        // (see startMockLocation) — this is what corrects the UI when that happens, instead of it
        // being stuck showing "mocking" for a service that isn't running.
        viewModelScope.launch {
            MockLocationBus.mockStartFailed.collect {
                _uiState.update { it.copy(isMocking = false) }
            }
        }
    }

    fun setServiceState(isRunning: Boolean) {
        _uiState.update { it.copy(isServiceRunning = isRunning, isMocking = isRunning) }
    }

    fun setSearchInput(input: String) {
        _uiState.update { it.copy(searchInput = input) }
    }

    fun selectPoint(geoPoint: GeoPoint) {
        _uiState.update { it.copy(selectedPoint = geoPoint) }
    }

    fun toggleMapVisibility() {
        _uiState.update { it.copy(mapVisible = !it.mapVisible) }
    }

    /**
     * What has to be true before a mock run can actually succeed, checked up front so the user is
     * sent to fix it before tapping start rather than finding out from a failure after the fact.
     */
    fun checkMockingPreflight(context: Context): List<PreflightIssue> {
        val issues = mutableListOf<PreflightIssue>()
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
            issues.add(PreflightIssue.LOCATION_SERVICES_OFF)
        }
        if (!isMockLocationAllowed(context)) {
            issues.add(PreflightIssue.MOCK_LOCATION_NOT_ALLOWED)
        }
        return issues
    }

    @Suppress("DEPRECATION") // unsafeCheckOpNoThrow needs API 29; this app's minSdk is also 29, but
    // checkOpNoThrow reads identically and needs no version branch — kept for that simplicity.
    private fun isMockLocationAllowed(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            // Can't tell — don't block the user on a check that itself failed; startMockLocation's
            // own SecurityException handling is still there as the real backstop.
            true
        }
    }

    fun startMockLocation(context: Context) {
        val point = _uiState.value.selectedPoint
        if (point == null) {
            Toast.makeText(context, context.getString(R.string.toast_select_location_first), Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_MOCK
            putExtra(MockLocationService.EXTRA_LATITUDE, point.latitude)
            putExtra(MockLocationService.EXTRA_LONGITUDE, point.longitude)
            putExtra(MockLocationService.EXTRA_JITTER_ENABLED, _uiState.value.jitterEnabled)
            putExtra(MockLocationService.EXTRA_JITTER_RANGE_METERS, _uiState.value.jitterRangeMeters)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        _uiState.update { it.copy(isMocking = true) }
    }

    fun stopMockLocation(context: Context) {
        val serviceIntent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP_MOCK
        }
        context.startService(serviceIntent)
        _uiState.update { it.copy(isMocking = false) }
    }

    fun searchAddress(context: Context) {
        val addressString = _uiState.value.searchInput
        if (addressString.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.search_address_hint), Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch {
            try {
                val geocoder = Geocoder(context)
                val addresses = geocoder.getFromLocationName(addressString, 1)
                if (addresses != null && addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val geoPoint = GeoPoint(address.latitude, address.longitude)
                    _uiState.update {
                        it.copy(
                            selectedPoint = geoPoint,
                            searchInput = "${address.latitude}, ${address.longitude}"
                        )
                    }
                } else {
                    Toast.makeText(context, context.getString(R.string.toast_address_not_found), Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Toast.makeText(context, context.getString(R.string.toast_geocoder_not_available), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Fetches the device's real GPS/network location once and selects it — no map tap or address search needed. */
    fun useCurrentLocation(context: Context) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Toast.makeText(context, context.getString(R.string.toast_location_permission_required), Toast.LENGTH_SHORT).show()
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // While mocking, GPS_PROVIDER is our own test provider and would just echo the fake
        // location back — read the real position from the remaining providers instead.
        val candidates = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { !(_uiState.value.isMocking && it == LocationManager.GPS_PROVIDER) }
            .filter { locationManager.isProviderEnabled(it) }
        if (candidates.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.toast_location_disabled), Toast.LENGTH_SHORT).show()
            return
        }

        _uiState.update { it.copy(isLocating = true) }
        try {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!_uiState.value.isLocating) return
                    locationManager.removeUpdates(this)
                    acceptLocation(location)
                }
            }
            // Listen on every enabled provider at once and take the first fix: indoors GPS often
            // never returns one, so waiting on it alone is what made this time out.
            candidates.forEach { provider ->
                locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }

            viewModelScope.launch {
                delay(15_000)
                if (!_uiState.value.isLocating) return@launch
                locationManager.removeUpdates(listener)

                val lastKnown = candidates
                    .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
                    .maxByOrNull { it.time }
                if (lastKnown != null) {
                    acceptLocation(lastKnown)
                } else {
                    _uiState.update { it.copy(isLocating = false) }
                    Toast.makeText(context, context.getString(R.string.toast_location_timeout), Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: SecurityException) {
            _uiState.update { it.copy(isLocating = false) }
            Toast.makeText(context, context.getString(R.string.toast_location_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Drops a test provider left behind by a previous run that was killed before it could clean up.
     * Until it is removed that stale provider keeps feeding the last fake position to every app on
     * the device, so clear it whenever we start up and are not actually mocking.
     */
    fun clearStaleMockProvider(context: Context) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (e: IllegalArgumentException) {
            // Nothing stale registered — the normal case.
        } catch (e: SecurityException) {
            // "Allow mock locations" is not granted to this app; nothing we can clean up.
        }
    }

    private fun acceptLocation(location: Location) {
        _uiState.update {
            it.copy(
                selectedPoint = GeoPoint(location.latitude, location.longitude),
                searchInput = "",
                isLocating = false
            )
        }
    }

    fun setJitterEnabled(enabled: Boolean, context: Context) {
        _uiState.update { it.copy(jitterEnabled = enabled) }
        saveJitterSettings(context)
        publishJitterSettings()
    }

    fun setJitterRange(rangeMeters: Float, context: Context) {
        _uiState.update { it.copy(jitterRangeMeters = rangeMeters) }
        saveJitterSettings(context)
        publishJitterSettings()
    }

    // The running service (if any) reads jitter settings from MockLocationBus on every push, so
    // this is what makes a change in the settings sheet take effect on the current mock run
    // instead of only the next one.
    private fun publishJitterSettings() {
        MockLocationBus.updateJitterSettings(_uiState.value.jitterEnabled, _uiState.value.jitterRangeMeters)
    }

    private fun saveJitterSettings(context: Context) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
        prefs.putBoolean("jitter_enabled", _uiState.value.jitterEnabled)
        prefs.putFloat("jitter_range_meters", _uiState.value.jitterRangeMeters)
        prefs.apply()
    }

    fun loadJitterSettings(context: Context) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("jitter_enabled", false)
        val range = prefs.getFloat("jitter_range_meters", JitterPreset.MEDIUM)
        _uiState.update { it.copy(jitterEnabled = enabled, jitterRangeMeters = range) }
        publishJitterSettings()
    }

    fun addFavorite(name: String, context: Context) {
        if (_uiState.value.selectedPoint == null) {
            Toast.makeText(context, context.getString(R.string.toast_select_location_first), Toast.LENGTH_SHORT).show()
            return
        }
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.toast_name_cannot_be_empty), Toast.LENGTH_SHORT).show()
            return
        }
        // A Map key silently overwrites on a name collision — without this check, saving a
        // second favorite under an existing name would quietly relocate the first one instead
        // of telling the user anything happened. The add dialog already blocks this in the UI;
        // this is the backstop in case a caller ever bypasses it.
        val nameTaken = _uiState.value.favorites.keys.any { it.equals(trimmedName, ignoreCase = true) }
        if (nameTaken) {
            Toast.makeText(context, context.getString(R.string.toast_favorite_name_duplicate), Toast.LENGTH_SHORT).show()
            return
        }
        val newFavorites = _uiState.value.favorites.toMutableMap()
        newFavorites[trimmedName] = _uiState.value.selectedPoint!!
        _uiState.update { it.copy(favorites = newFavorites) }
        saveFavorites(context)
        Toast.makeText(context, context.getString(R.string.toast_favorite_saved), Toast.LENGTH_SHORT).show()
    }

    fun deleteFavorite(name: String, context: Context) {
        val newFavorites = _uiState.value.favorites.toMutableMap()
        newFavorites.remove(name)
        _uiState.update { it.copy(favorites = newFavorites) }
        saveFavorites(context)
        Toast.makeText(context, context.getString(R.string.toast_favorite_deleted), Toast.LENGTH_SHORT).show()
    }

    fun loadFavorites(context: Context) {
        val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
        val favoriteSet = prefs.getStringSet("locations", emptySet()) ?: emptySet()
        val loadedFavorites = mutableMapOf<String, GeoPoint>()
        favoriteSet.forEach {
            val parts = it.split("|")
            if (parts.size == 3) {
                loadedFavorites[parts[0]] = GeoPoint(parts[1].toDouble(), parts[2].toDouble())
            }
        }
        _uiState.update { it.copy(favorites = loadedFavorites) }
    }

    private fun saveFavorites(context: Context) {
        val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE).edit()
        val favoriteSet = _uiState.value.favorites.map { "${it.key}|${it.value.latitude}|${it.value.longitude}" }.toSet()
        prefs.putStringSet("locations", favoriteSet)
        prefs.apply()
    }

    fun saveLastLocation(context: Context) {
        _uiState.value.selectedPoint?.let {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
            prefs.putFloat("last_lat", it.latitude.toFloat())
            prefs.putFloat("last_lon", it.longitude.toFloat())
            prefs.apply()
        }
    }

    fun loadLastLocation(context: Context) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val lat = prefs.getFloat("last_lat", -999f)
        val lon = prefs.getFloat("last_lon", -999f)
        if (lat != -999f && lon != -999f) {
            _uiState.update { it.copy(selectedPoint = GeoPoint(lat.toDouble(), lon.toDouble())) }
        } else {
            _uiState.update { it.copy(selectedPoint = GeoPoint(25.0330, 121.5654)) } // Default to Taipei 101
        }
    }
}

class MockLocationProvider(providerName: String, context: Context) {
    private val locationManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val providerName: String

    init {
        this.providerName = providerName
        // A previous run killed without reaching shutdown() (crash, force-stop, low memory) leaves
        // the test provider registered system-wide. addTestProvider would then throw
        // "Provider already exists" and, worse, the stale provider keeps hijacking real GPS reads —
        // so always clear it before registering.
        removeTestProviderQuietly()
        locationManager.addTestProvider(providerName, false, false, false, false, true, true, true, 1, 2)
        locationManager.setTestProviderEnabled(providerName, true)
    }

    private fun removeTestProviderQuietly() {
        try {
            locationManager.removeTestProvider(providerName)
        } catch (e: IllegalArgumentException) {
            // Nothing registered under this name — the normal case.
        } catch (e: SecurityException) {
            // "Allow mock locations" is off; addTestProvider below will surface the real error.
        }
    }

    fun pushLocation(lat: Double, lon: Double) {
        val mockLocation = Location(providerName)
        mockLocation.latitude = lat
        mockLocation.longitude = lon
        mockLocation.altitude = 0.0
        mockLocation.time = System.currentTimeMillis()
        mockLocation.accuracy = 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            mockLocation.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        locationManager.setTestProviderLocation(providerName, mockLocation)
    }

    fun shutdown() {
        removeTestProviderQuietly()
    }
}