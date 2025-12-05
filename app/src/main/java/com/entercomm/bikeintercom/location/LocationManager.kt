package com.entercomm.bikeintercom.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.os.BatteryManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.entercomm.bikeintercom.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * Represents a peer's location for radar display.
 */
data class PeerLocation(
    val nodeId: String,
    val nickname: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val bearing: Float = 0f, // Direction peer is facing
    val speed: Float = 0f, // Speed in m/s
    val timestamp: Long = System.currentTimeMillis(),
) {
    companion object {
        /** Maximum acceptable accuracy in meters for radar display */
        const val MAX_ACCURACY_METERS = 50f

        /** Validate that coordinates are within valid ranges */
        fun isValid(latitude: Double, longitude: Double): Boolean {
            return latitude in -90.0..90.0 && longitude in -180.0..180.0
        }

        /** Check if accuracy is acceptable for radar display */
        fun hasAcceptableAccuracy(accuracy: Float): Boolean {
            return accuracy in 0f..MAX_ACCURACY_METERS
        }
    }

    /** Check if this location has valid coordinates */
    val hasValidCoordinates: Boolean
        get() = isValid(latitude, longitude)

    /** Check if this location has acceptable accuracy for radar */
    val hasAcceptableAccuracy: Boolean
        get() = accuracy >= 0f && accuracy <= MAX_ACCURACY_METERS

    /**
     * Calculate distance to another location in meters using Haversine formula.
     * Pure Kotlin implementation for testability.
     */
    fun distanceTo(other: PeerLocation): Float {
        return haversineDistance(latitude, longitude, other.latitude, other.longitude).toFloat()
    }

    /**
     * Calculate bearing to another location in degrees (0-360).
     * Pure Kotlin implementation for testability.
     */
    fun bearingTo(other: PeerLocation): Float {
        return initialBearing(latitude, longitude, other.latitude, other.longitude).toFloat()
    }
}

/**
 * Data for radar visualization.
 */
data class RadarData(
    val localLocation: PeerLocation?,
    val peerLocations: List<PeerLocation>,
    val radarRange: Float = 500f, // Range in meters
    val lastUpdate: Long = System.currentTimeMillis(),
) {
    companion object {
        val EMPTY = RadarData(null, emptyList())

        // Radar range presets
        const val RANGE_CLOSE = 100f
        const val RANGE_MEDIUM = 500f
        const val RANGE_FAR = 2000f
    }

    /**
     * Get peers within radar range.
     */
    fun peersInRange(): List<Pair<PeerLocation, Float>> {
        val local = localLocation ?: return emptyList()
        return peerLocations
            .map { peer -> peer to local.distanceTo(peer) }
            .filter { (_, distance) -> distance <= radarRange }
            .sortedBy { (_, distance) -> distance }
    }

    /**
     * Convert peer location to radar coordinates (x, y in -1..1 range).
     */
    fun peerToRadarCoordinates(peer: PeerLocation): Pair<Float, Float>? {
        val local = localLocation ?: return null
        val distance = local.distanceTo(peer)
        if (distance > radarRange) return null

        // Calculate bearing from local to peer
        val bearing = local.bearingTo(peer)

        // Convert to radians (adjusting for north = up)
        val angleRad = Math.toRadians((bearing - local.bearing).toDouble())

        // Calculate normalized position (0..1 = center..edge)
        val normalizedDistance = (distance / radarRange).coerceIn(0f, 1f)

        // Convert polar to cartesian (x right, y up)
        val x = (sin(angleRad) * normalizedDistance).toFloat()
        val y = (-cos(angleRad) * normalizedDistance).toFloat() // Negative because y increases downward in canvas

        return x to y
    }
}

/**
 * Location message types for mesh protocol.
 */
enum class LocationMessageType {
    LOCATION_UPDATE, // Periodic location broadcast
    LOCATION_REQUEST, // Request peer's location
    LOCATION_RESPONSE, // Response to location request
}

/**
 * Manages location tracking and peer location sharing.
 * Uses standard Android LocationManager (no Google Play Services dependency).
 */
class LocationManager(private val context: Context) {

    companion object {
        private const val LOCATION_TIMEOUT_MS = 30000L // Consider location stale after 30s

        // Battery-aware GPS update intervals
        private const val UPDATE_INTERVAL_NORMAL_MS = 5000L // 5 seconds when battery > 50%
        private const val UPDATE_INTERVAL_LOW_MS = 15000L // 15 seconds when battery 21-50%
        private const val UPDATE_INTERVAL_CRITICAL_MS = 30000L // 30 seconds when battery <= 20%

        // Minimum distance for location updates (meters)
        private const val MIN_DISTANCE_METERS = 5f // Only update if moved at least 5 meters

        /**
         * Get GPS update interval based on battery level.
         */
        fun getUpdateIntervalForBattery(batteryLevel: Int): Long = when {
            batteryLevel > 50 -> UPDATE_INTERVAL_NORMAL_MS
            batteryLevel > 20 -> UPDATE_INTERVAL_LOW_MS
            else -> UPDATE_INTERVAL_CRITICAL_MS
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val androidLocationManager: android.location.LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager

    // Local location state
    private val _localLocation = MutableStateFlow<PeerLocation?>(null)
    val localLocation: StateFlow<PeerLocation?> = _localLocation.asStateFlow()

    // Peer locations
    private val peerLocations = ConcurrentHashMap<String, PeerLocation>()
    private val _radarData = MutableStateFlow(RadarData.EMPTY)
    val radarData: StateFlow<RadarData> = _radarData.asStateFlow()

    // Radar settings
    private val _radarRange = MutableStateFlow(RadarData.RANGE_MEDIUM)
    val radarRange: StateFlow<Float> = _radarRange.asStateFlow()

    // Location tracking state
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    // Local node info (set from service)
    private var localNodeId: String = ""
    private var localNickname: String = ""

    // Callback for sending location messages
    var sendLocationMessage: ((LocationMessageType, String, ByteArray) -> Unit)? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            updateLocalLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            // Legacy callback, required for older API levels
        }

        override fun onProviderEnabled(provider: String) {
            logD { "Location provider enabled: $provider" }
        }

        override fun onProviderDisabled(provider: String) {
            logD { "Location provider disabled: $provider" }
        }
    }

    /**
     * Initialize with local node info.
     */
    fun initialize(nodeId: String, nickname: String) {
        localNodeId = nodeId
        localNickname = nickname
        logD { "LocationManager initialized for node: $nodeId" }
    }

    /**
     * Start location tracking.
     */
    @SuppressLint("MissingPermission")
    fun startTracking(): Boolean {
        logD { "startTracking() called" }

        if (!hasLocationPermission()) {
            logW { "Location permission not granted" }
            return false
        }

        if (_isTracking.value) {
            logD { "Already tracking location" }
            return true
        }

        val locationMgr = androidLocationManager
        if (locationMgr == null) {
            logE { "LocationManager not available" }
            return false
        }

        try {
            // Try GPS provider first, fall back to network
            val provider = when {
                locationMgr.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ->
                    android.location.LocationManager.GPS_PROVIDER
                locationMgr.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ->
                    android.location.LocationManager.NETWORK_PROVIDER
                else -> {
                    logW { "No location provider available" }
                    return false
                }
            }

            // Get last known location immediately for quick display
            try {
                val lastLocation = locationMgr.getLastKnownLocation(provider)
                    ?: locationMgr.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    ?: locationMgr.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)

                if (lastLocation != null) {
                    logD { "Using last known location: ${lastLocation.latitude}, ${lastLocation.longitude}" }
                    updateLocalLocation(lastLocation)
                } else {
                    logD { "No last known location available" }
                }
            } catch (e: Exception) {
                logW({ "Could not get last known location" }, e)
            }

            // Get battery-aware update interval
            val batteryLevel = getBatteryLevel()
            val updateInterval = getUpdateIntervalForBattery(batteryLevel)

            locationMgr.requestLocationUpdates(
                provider,
                updateInterval,
                MIN_DISTANCE_METERS, // Only update if moved at least this distance
                locationListener,
                Looper.getMainLooper(),
            )

            _isTracking.value = true
            logD { "Location tracking started with provider: $provider, interval: ${updateInterval}ms (battery: $batteryLevel%)" }
            return true
        } catch (e: SecurityException) {
            logE({ "Security exception starting location tracking" }, e)
            return false
        } catch (e: Exception) {
            logE({ "Error starting location tracking" }, e)
            return false
        }
    }

    /**
     * Stop location tracking.
     */
    fun stopTracking() {
        try {
            androidLocationManager?.removeUpdates(locationListener)
            _isTracking.value = false
            logD { "Location tracking stopped" }
        } catch (e: Exception) {
            logE({ "Error stopping location tracking" }, e)
        }
    }

    /**
     * Update local node's nickname.
     */
    fun updateNickname(nickname: String) {
        localNickname = nickname
        _localLocation.value?.let { location ->
            _localLocation.value = location.copy(nickname = nickname)
            updateRadarData()
        }
    }

    /**
     * Set radar range.
     */
    fun setRadarRange(range: Float) {
        _radarRange.value = range.coerceIn(50f, 5000f)
        updateRadarData()
    }

    /**
     * Cycle through radar range presets.
     */
    fun cycleRadarRange() {
        val current = _radarRange.value
        _radarRange.value = when {
            current <= RadarData.RANGE_CLOSE -> RadarData.RANGE_MEDIUM
            current <= RadarData.RANGE_MEDIUM -> RadarData.RANGE_FAR
            else -> RadarData.RANGE_CLOSE
        }
        updateRadarData()
    }

    /**
     * Process incoming location message from peer.
     */
    fun processLocationMessage(type: LocationMessageType, senderId: String, payload: ByteArray) {
        when (type) {
            LocationMessageType.LOCATION_UPDATE -> handleLocationUpdate(senderId, payload)
            LocationMessageType.LOCATION_REQUEST -> handleLocationRequest(senderId)
            LocationMessageType.LOCATION_RESPONSE -> handleLocationUpdate(senderId, payload)
        }
    }

    /**
     * Broadcast our location to all peers.
     */
    fun broadcastLocation() {
        val location = _localLocation.value ?: return
        val payload = serializeLocation(location)
        sendLocationMessage?.invoke(LocationMessageType.LOCATION_UPDATE, "broadcast", payload)
    }

    /**
     * Request location from a specific peer.
     */
    fun requestPeerLocation(nodeId: String) {
        sendLocationMessage?.invoke(LocationMessageType.LOCATION_REQUEST, nodeId, ByteArray(0))
    }

    /**
     * Get a specific peer's location.
     */
    fun getPeerLocation(nodeId: String): PeerLocation? {
        return peerLocations[nodeId]
    }

    /**
     * Remove a peer's location (when they disconnect).
     */
    fun removePeerLocation(nodeId: String) {
        peerLocations.remove(nodeId)
        updateRadarData()
    }

    /**
     * Clear all peer locations.
     */
    fun clearPeerLocations() {
        peerLocations.clear()
        updateRadarData()
    }

    /**
     * Cleanup stale peer locations.
     */
    fun cleanupStaleLocations() {
        val currentTime = System.currentTimeMillis()
        val staleIds = peerLocations.filter { (_, location) ->
            currentTime - location.timestamp > LOCATION_TIMEOUT_MS
        }.keys

        staleIds.forEach { peerLocations.remove(it) }
        if (staleIds.isNotEmpty()) {
            updateRadarData()
            logD { "Removed ${staleIds.size} stale peer locations" }
        }
    }

    private fun updateLocalLocation(location: Location) {
        // Validate coordinates
        if (!PeerLocation.isValid(location.latitude, location.longitude)) {
            logW { "Ignoring invalid location: lat=${location.latitude}, lng=${location.longitude}" }
            return
        }

        // Filter low-accuracy locations for radar display
        if (!PeerLocation.hasAcceptableAccuracy(location.accuracy)) {
            logD { "Ignoring low-accuracy location: ${location.accuracy}m (max: ${PeerLocation.MAX_ACCURACY_METERS}m)" }
            return
        }

        val peerLocation = PeerLocation(
            nodeId = localNodeId,
            nickname = localNickname,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            accuracy = location.accuracy,
            bearing = location.bearing,
            speed = location.speed,
            timestamp = System.currentTimeMillis(),
        )

        _localLocation.value = peerLocation
        updateRadarData()

        // Broadcast location to peers
        scope.launch {
            broadcastLocation()
        }

        logD { "Local location updated: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m)" }
    }

    private fun handleLocationUpdate(senderId: String, payload: ByteArray) {
        val peerLocation = deserializeLocation(senderId, payload) ?: return
        peerLocations[senderId] = peerLocation
        updateRadarData()
        logD { "Peer location updated: $senderId at ${peerLocation.latitude}, ${peerLocation.longitude}" }
    }

    private fun handleLocationRequest(senderId: String) {
        val location = _localLocation.value ?: return
        val payload = serializeLocation(location)
        sendLocationMessage?.invoke(LocationMessageType.LOCATION_RESPONSE, senderId, payload)
    }

    private fun updateRadarData() {
        _radarData.value = RadarData(
            localLocation = _localLocation.value,
            peerLocations = peerLocations.values.toList(),
            radarRange = _radarRange.value,
            lastUpdate = System.currentTimeMillis(),
        )
    }

    private fun serializeLocation(location: PeerLocation): ByteArray {
        // Format: nickname|lat|lng|alt|accuracy|bearing|speed|timestamp
        val data = "${location.nickname}|${location.latitude}|${location.longitude}|${location.altitude}|${location.accuracy}|${location.bearing}|${location.speed}|${location.timestamp}"
        return data.toByteArray()
    }

    private fun deserializeLocation(nodeId: String, payload: ByteArray): PeerLocation? {
        return try {
            val data = String(payload)
            val parts = data.split("|")
            if (parts.size < 8) return null

            val latitude = parts[1].toDouble()
            val longitude = parts[2].toDouble()
            val accuracy = parts[4].toFloat()

            // Validate coordinates
            if (!PeerLocation.isValid(latitude, longitude)) {
                logW { "Rejecting peer location from $nodeId: invalid coordinates ($latitude, $longitude)" }
                return null
            }

            // Validate accuracy (allow slightly higher accuracy from peers since network conditions vary)
            if (accuracy < 0f) {
                logW { "Rejecting peer location from $nodeId: invalid accuracy ($accuracy)" }
                return null
            }

            PeerLocation(
                nodeId = nodeId,
                nickname = parts[0],
                latitude = latitude,
                longitude = longitude,
                altitude = parts[3].toDouble(),
                accuracy = accuracy,
                bearing = parts[5].toFloat().coerceIn(0f, 360f),
                speed = parts[6].toFloat().coerceAtLeast(0f),
                timestamp = parts[7].toLong(),
            )
        } catch (e: Exception) {
            logE({ "Failed to deserialize location from $nodeId" }, e)
            null
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get current battery level (0-100).
     */
    private fun getBatteryLevel(): Int {
        return try {
            val batteryStatus = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) {
                (level * 100) / scale
            } else {
                100 // Default to full if unknown
            }
        } catch (e: Exception) {
            logW({ "Failed to get battery level" }, e)
            100 // Default to full on error
        }
    }
}

/**
 * Calculate distance between two coordinates using Haversine formula.
 */
fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadius = 6371000.0 // meters

    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)

    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2).pow(2)

    val c = 2 * atan2(sqrt(a), sqrt(1 - a))

    return earthRadius * c
}

/**
 * Calculate initial bearing between two coordinates.
 */
fun initialBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val dLonRad = Math.toRadians(lon2 - lon1)

    val y = sin(dLonRad) * cos(lat2Rad)
    val x = cos(lat1Rad) * sin(lat2Rad) -
        sin(lat1Rad) * cos(lat2Rad) * cos(dLonRad)

    var bearing = Math.toDegrees(atan2(y, x))
    bearing = (bearing + 360) % 360

    return bearing
}
