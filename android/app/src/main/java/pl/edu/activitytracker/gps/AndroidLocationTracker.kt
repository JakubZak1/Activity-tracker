package pl.edu.activitytracker.gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.edu.activitytracker.domain.LocationSample
import pl.edu.activitytracker.domain.LocationStatus

interface LocationTracker {
    val locations: SharedFlow<LocationSample>
    val status: StateFlow<LocationStatus>

    fun start()
    fun stop()
}

class AndroidLocationTracker(
    private val context: Context,
) : LocationTracker {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _locations = MutableSharedFlow<LocationSample>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val locations: SharedFlow<LocationSample> = _locations.asSharedFlow()

    private val _status = MutableStateFlow<LocationStatus>(LocationStatus.Idle)
    override val status: StateFlow<LocationStatus> = _status.asStateFlow()

    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        if (!hasLocationPermission()) {
            _status.value = LocationStatus.PermissionMissing
            return
        }

        if (callback != null) {
            _status.value = LocationStatus.Running
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(750L)
            .setMaxUpdateDelayMillis(2_000L)
            .build()

        val nextCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    _locations.tryEmit(
                        LocationSample(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                            timestampMillis = location.time,
                        ),
                    )
                }
            }
        }

        callback = nextCallback
        fusedLocationClient.requestLocationUpdates(request, nextCallback, Looper.getMainLooper())
            .addOnSuccessListener { _status.value = LocationStatus.Running }
            .addOnFailureListener { error ->
                callback = null
                _status.value = LocationStatus.Failed(error.message ?: "Location updates failed")
            }
    }

    override fun stop() {
        val activeCallback = callback
        if (activeCallback != null) {
            fusedLocationClient.removeLocationUpdates(activeCallback)
        }
        callback = null
        _status.value = LocationStatus.Idle
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }
}
