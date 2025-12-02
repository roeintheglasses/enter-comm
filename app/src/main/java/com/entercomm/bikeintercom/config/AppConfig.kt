package com.entercomm.bikeintercom.config

/**
 * Centralized configuration for the Enter-Comm app.
 * All magic numbers and timing constants should be defined here.
 */
object AppConfig {

    // Mesh Network Configuration
    object Mesh {
        const val DISCOVERY_PORT = 8888
        const val AUDIO_PORT = 8889
        const val HEARTBEAT_INTERVAL_MS = 5000L
        const val NODE_TIMEOUT_MS = 15000L
        const val MAX_ROUTE_AGE_MS = 30000L
        const val MAX_MESSAGE_CACHE_SIZE = 1000
        const val DISCOVERY_INTERVAL_MS = 10000L
        const val NETWORK_SCAN_TIMEOUT_MS = 500
        const val DISCOVERY_COOLDOWN_MS = 5000L
        const val MESSAGE_TTL_DEFAULT = 5
        const val MAX_PACKET_SIZE = 16384 // 16KB
        const val MAX_AUDIO_SAMPLES = 8192
    }

    // Audio Configuration
    object Audio {
        const val SAMPLE_RATE = 48000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val BUFFER_SIZE_MS = 20
        const val PLAYBACK_BUFFER_MULTIPLIER = 4
        const val MAX_AUDIO_PROCESSORS = 10
    }

    // WiFi Direct Configuration
    object WiFiDirect {
        const val CONNECTION_TIMEOUT_MS = 30000L
        const val CONNECTION_COOLDOWN_MS = 5000L
        const val GROUP_OWNER_DEFAULT_IP = "192.168.49.1"
    }

    // Service Configuration
    object Service {
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CHANNEL_ID = "mesh_network_channel"
        const val INITIAL_SCAN_DELAY_MS = 10000L
        const val PERIODIC_SCAN_INTERVAL_MS = 120000L
        const val SERVICE_INIT_DELAY_MS = 100L
        const val SERVICE_BIND_TIMEOUT_MS = 3000L
        const val RETRY_MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1000L
        const val MESH_CONNECTION_VERIFY_DELAY_MS = 3000L
        const val WIFI_DIRECT_MONITOR_RESTART_DELAY_MS = 5000L
    }

    // UI Configuration
    object UI {
        const val PTT_BUTTON_SIZE_DP = 140
        const val PTT_TOUCH_TARGET_DP = 160
        const val MIN_BUTTON_HEIGHT_DP = 64
        const val AUDIO_LEVEL_UPDATE_INTERVAL_MS = 100L
    }

    // Battery-aware scan intervals
    fun getDiscoveryIntervalForBattery(batteryLevel: Int): Long = when (batteryLevel) {
        in 0..20 -> 120_000L   // 2 minutes when battery critical
        in 21..50 -> 60_000L   // 1 minute when battery low
        else -> 30_000L        // 30 seconds normally
    }
}
