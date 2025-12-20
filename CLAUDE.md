# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Enter-Comm** is an Android bike intercom app that creates a mesh network using WiFi Direct for offline voice communication between cyclists. Real-time audio communication over 100-200m range without internet.

## Development Commands

### Using Makefile (recommended)

```bash
make build          # Build debug APK
make release        # Build release APK
make test           # Run unit tests
make test-report    # Run tests with HTML report
make install        # Install debug APK on device
make run            # Install and launch app
make check          # Run all checks (format, detekt, lint, test)
make ci             # Simulate full CI pipeline locally
make format         # Auto-format code with Spotless
make detekt         # Run Detekt static analysis
make logs           # Show filtered app logs
make help           # Show all available commands
```

### Using Gradle directly

```bash
./gradlew assembleDebug                           # Build debug APK
./gradlew test                                    # Run all unit tests
./gradlew test --tests "*.AdpcmCodecTest"         # Run single test class
./gradlew installDebug                            # Install on device
./gradlew spotlessApply                           # Auto-format code
./gradlew detekt                                  # Static analysis
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Architecture

### Layer Overview

```
MainActivity (UI - Jetpack Compose)
       ↓ binds to
MeshNetworkService (Foreground Service - orchestrates all managers)
       ↓ coordinates
┌──────────────────┬─────────────────────┬─────────────────┬─────────────────┐
│ WiFiDirectManager│ MeshNetworkManager  │ AudioManager    │ LocationManager │
│ (P2P discovery)  │ (mesh routing)      │ (audio capture) │ (GPS tracking)  │
└──────────────────┴─────────────────────┴─────────────────┴─────────────────┘
                          ↓ uses
                   DistanceVectorRouter (Bellman-Ford routing)
```

### Core Components

**MeshNetworkService** (`mesh/MeshNetworkService.kt`)
- Android foreground service that orchestrates all managers
- Handles service lifecycle, notification management, and state coordination
- Exposes `ServiceState` via StateFlow for UI binding
- Actions: `ACTION_START_MESH`, `ACTION_STOP_MESH`, `ACTION_START_RECORDING`, `ACTION_STOP_RECORDING`, `ACTION_TOGGLE_MUTE`

**MeshNetworkManager** (`mesh/MeshNetworkManager.kt`)
- Custom UDP-based mesh protocol on ports 8888 (discovery) and 8889 (audio)
- Message types: `DISCOVERY`, `ROUTE_UPDATE`, `AUDIO_DATA`, `CONTROL`, `HEARTBEAT`, `GROUP`, `LOCATION`
- Uses ConcurrentHashMap for thread-safe node/route storage
- Discovery broadcasts every 10s, heartbeats every 5s, node timeout 15s

**DistanceVectorRouter** (`mesh/DistanceVectorRouter.kt`)
- Bellman-Ford algorithm for multi-hop routing
- Split-horizon with poison-reverse for loop prevention
- INFINITY = 16, MAX_HOP_COUNT = 15
- Route advertisements serialized as pipe-delimited strings

**WiFiDirectManager** (`wifidirect/WiFiDirectManager.kt`)
- Wraps Android WiFi P2P APIs (`WifiP2pManager`)
- Handles peer discovery, group formation, connection events
- Emits events via Kotlin Channel: `WiFiDirectEvent` sealed class
- Group owner gets IP x.x.x.1 (typically 192.168.49.1)

**AudioManager** (`audio/AudioManager.kt`)
- PCM audio capture/playback at 48kHz mono, 16-bit
- Per-source AudioProcessor instances for playback mixing
- Callbacks: `meshCallback` sends encoded audio to mesh network

**AdpcmCodec** (`audio/AdpcmCodec.kt`)
- IMA ADPCM codec providing ~4x compression
- 16-bit PCM → 4-bit ADPCM with header
- Frame size: 960 samples (20ms at 48kHz)

**LocationManager** (`location/LocationManager.kt`)
- GPS tracking for peer location display
- Location updates broadcast via mesh network

**MeshProtocol** (`mesh/protocol/`)
- Interface with implementations: `PipeDelimitedMeshProtocol` (default), `EncryptedMeshProtocol`
- Abstracts message serialization/deserialization
- Encryption uses AES-GCM with group key derivation

**GroupManager** (`mesh/GroupManager.kt`)
- Manages group membership and group codes
- Handles join/leave events and member tracking
- Coordinates with OnboardingManager for new user setup

**AppConfig** (`config/AppConfig.kt`)
- Centralized configuration constants (ports, timeouts, buffer sizes)
- Battery-aware discovery intervals
- All "magic numbers" should be defined here

### Data Flow

1. **Connection**: WiFiDirectManager discovers peers → forms P2P group → MeshNetworkManager establishes mesh routing via DistanceVectorRouter
2. **Audio TX**: AudioManager captures → AdpcmCodec encodes → MeshNetworkManager broadcasts via UDP
3. **Audio RX**: MeshNetworkManager receives → AdpcmCodec decodes → AudioManager plays via per-source AudioProcessor

### UI Layer

- **MainScreen** (`ui/screens/MainScreen.kt`) - Primary Compose UI with radar view and controls
- **OnboardingScreen** (`ui/screens/OnboardingScreen.kt`) - First-launch setup flow
- **Components** (`ui/components/`) - Reusable Compose components (Radar, Group, Technical)

### Key Patterns

- **StateFlow exposure**: All managers expose state via `MutableStateFlow` with public `StateFlow` getter
- **Coroutine scopes**: Each manager has `CoroutineScope(Dispatchers.IO/Default + SupervisorJob())`
- **Message serialization**: Pipe-delimited format: `messageId|sourceId|destinationId|type|ttl|timestamp|payload`
- **Logging**: Use `logD`, `logW`, `logE` from `util/Logger.kt` (supports test mode)

## Testing

Unit tests are in `app/src/test/`. Key test classes:
- `AdpcmCodecTest` - Audio codec encoding/decoding
- `DistanceVectorRouterTest` - Routing algorithm correctness
- `PipeDelimitedMeshProtocolTest` - Message serialization
- `JitterBufferTest` - Audio buffering behavior
- `GroupCodeUtilsTest` - Group code generation/validation

Run a specific test:
```bash
./gradlew test --tests "*.DistanceVectorRouterTest"
```

## Code Quality

Pre-commit hooks run Spotless and Detekt automatically. Install with:
```bash
make hooks
```

Configuration files:
- `config/detekt/detekt.yml` - Detekt rules
- `config/detekt/baseline.xml` - Baselined issues
- `.editorconfig` or `app/build.gradle.kts` spotless block - Formatting rules

## Implementation Notes

- WiFi Direct group owner always gets subnet `.1` address (typically 192.168.49.1)
- Discovery rate-limited to prevent broadcast storms (5s cooldown per IP)
- Audio packets limited to 16KB, decoded samples to 8KB for safety
- Service continues in limited mode if any manager fails to initialize
- Group codes use CODE_CHARS without ambiguous characters (0, 1, I, L, O excluded)
- Android 12+ requires `NEARBY_WIFI_DEVICES` permission for WiFi Direct
- Dependency versions managed in `gradle/libs.versions.toml`
