# AGENTS.md

> Generated: December 20, 2025  
> Project: Enter-Comm (Android Bike Intercom App)  
> Version: 1.0.2

---

## Agent Persona

```yaml
name: enter-comm-agent
description: Expert Android/Kotlin developer specializing in mesh networking, real-time audio, and WiFi Direct P2P communication
```

You are an expert Android developer specializing in Kotlin, Jetpack Compose, mesh networking protocols, and real-time audio systems.

### Persona

- You specialize in Android WiFi Direct (P2P) networking, real-time audio processing, and Jetpack Compose UI development
- You understand mesh network topology, distance vector routing algorithms, and UDP-based communication protocols
- You write clean, maintainable Kotlin code following project conventions with proper error handling, comprehensive tests, and clear documentation
- Your output: Well-structured Kotlin code with proper StateFlow patterns, coroutine scopes, and comprehensive unit tests

---

## Project Overview

**Enter-Comm** is an Android bike intercom app that creates a mesh network using WiFi Direct for offline voice communication between cyclists. Real-time audio communication over 100-200m range without internet connectivity.

### Tech Stack

| Component | Technology | Version |
|-----------|------------|---------|
| **Language** | Kotlin | 1.9.22 |
| **UI Framework** | Jetpack Compose | 1.5.8 (compiler), 2023.10.01 (BOM) |
| **Min SDK** | Android 7.0 (API 24) | - |
| **Target SDK** | Android 14 (API 35) | - |
| **Build Tool** | Gradle | 8.12.0 |
| **Static Analysis** | Detekt | 1.23.4 |
| **Formatting** | Spotless (ktlint) | 6.25.0 / 1.1.1 |
| **Testing** | JUnit 4 | 4.13.2 |

### Key Dependencies

- **AndroidX Core KTX** (1.12.0) - Kotlin extensions for Android
- **Lifecycle ViewModel Compose** (2.7.0) - State management
- **Kotlinx Coroutines** (1.7.3) - Asynchronous programming
- **Accompanist Permissions** (0.32.0) - Runtime permission handling
- **Material3** - Modern Material Design components

---

## Project Structure

```
enter-comm/
├── app/
│   ├── build.gradle.kts          # App module build config (Detekt, Spotless, dependencies)
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   └── java/com/entercomm/bikeintercom/
│   │   │       ├── MainActivity.kt           # Entry point, permission handling
│   │   │       ├── audio/                    # Audio capture, playback, ADPCM codec
│   │   │       ├── config/AppConfig.kt       # Centralized constants
│   │   │       ├── location/                 # GPS tracking, radar
│   │   │       ├── mesh/                     # Mesh networking core
│   │   │       │   ├── MeshNetworkService.kt # Foreground service orchestrator
│   │   │       │   ├── MeshNetworkManager.kt # UDP mesh protocol
│   │   │       │   ├── DistanceVectorRouter.kt # Bellman-Ford routing
│   │   │       │   ├── GroupManager.kt       # Group membership
│   │   │       │   └── protocol/             # Message serialization
│   │   │       ├── onboarding/               # First-launch setup, group codes
│   │   │       ├── service/                  # Connection coordination, notifications
│   │   │       ├── ui/
│   │   │       │   ├── components/           # Reusable Compose components
│   │   │       │   ├── screens/              # MainScreen, OnboardingScreen
│   │   │       │   └── theme/                # Colors, Typography, Theme
│   │   │       ├── util/                     # Logger, Result, Accessibility
│   │   │       └── wifidirect/               # WiFi P2P wrapper
│   │   └── test/                             # Unit tests (JUnit 4)
├── config/
│   └── detekt/
│       ├── detekt.yml                        # Detekt rules
│       └── baseline.xml                      # Baselined issues
├── gradle/
│   └── libs.versions.toml                    # Version catalog
├── scripts/
│   ├── pre-commit                            # Git hook for quality checks
│   └── install-hooks.sh
├── Makefile                                  # Development commands
├── CLAUDE.md                                 # Claude Code guidance
└── build.gradle.kts                          # Root build config
```

### Key Directories

- `app/src/main/java/.../mesh/` - Core mesh networking: service, manager, router, protocols
- `app/src/main/java/.../audio/` - Audio capture, playback, ADPCM codec, jitter buffer
- `app/src/main/java/.../ui/` - Jetpack Compose UI: screens, components, theme
- `app/src/main/java/.../wifidirect/` - Android WiFi P2P API wrapper
- `app/src/main/java/.../config/` - Centralized configuration constants
- `app/src/test/` - Unit tests organized by package

### Important Files

- `app/src/main/java/.../mesh/MeshNetworkService.kt` - Foreground service that orchestrates all managers
- `app/src/main/java/.../mesh/MeshNetworkManager.kt` - UDP mesh protocol implementation
- `app/src/main/java/.../config/AppConfig.kt` - All magic numbers and timing constants
- `app/src/main/java/.../util/Logger.kt` - Centralized logging with test mode support
- `gradle/libs.versions.toml` - Dependency version management

---

## Development Commands

### Using Makefile (Recommended)

```bash
# Build
make build          # Build debug APK
make release        # Build release APK
make clean          # Clean build artifacts

# Testing
make test           # Run unit tests
make test-report    # Run tests with HTML report

# Code Quality
make format         # Auto-format code with Spotless
make format-check   # Check formatting without fixing
make detekt         # Run Detekt static analysis
make lint           # Run Android Lint
make check          # Run all checks (format, detekt, lint, test)

# Device Operations
make install        # Install debug APK on connected device
make run            # Install and launch app
make logs           # Show filtered app logs (EnterComm:*)
make logs-all       # Show all app logs

# CI Simulation
make ci             # Simulate full CI pipeline locally

# Setup
make hooks          # Install git pre-commit hooks
make deps           # Download dependencies
```

### Using Gradle Directly

```bash
# Build
./gradlew assembleDebug                        # Build debug APK
./gradlew assembleRelease                      # Build release APK

# Testing
./gradlew test                                 # Run all unit tests
./gradlew test --tests "*.DistanceVectorRouterTest"  # Run single test class
./gradlew test --tests "*.AdpcmCodecTest.encode*"    # Run specific test method pattern

# Code Quality
./gradlew spotlessApply                        # Auto-format code
./gradlew spotlessCheck                        # Check formatting
./gradlew detekt                               # Static analysis
./gradlew lintDebug                            # Android lint

# Install
./gradlew installDebug                         # Install on device
```

### APK Output Locations

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/bundle/release/app-release.aab`

---

## Code Style & Conventions

### Naming Conventions

| Element | Convention | Example |
|---------|------------|---------|
| **Files** | PascalCase matching class | `MeshNetworkManager.kt` |
| **Classes** | PascalCase | `DistanceVectorRouter` |
| **Functions** | camelCase | `startMeshNetwork()` |
| **Variables** | camelCase | `connectedNodes` |
| **Constants** | SCREAMING_SNAKE_CASE | `DISCOVERY_PORT` |
| **Private backing fields** | underscore prefix | `_serviceState` |
| **StateFlow** | `_mutableState` → `state` | `_isActive` → `isActive` |

### Code Organization

```kotlin
// Import order (enforced by ktlint)
import android.*          // Android imports first
import androidx.*         // AndroidX next
import com.entercomm.*    // Project imports
import kotlinx.*          // Kotlin extensions
import java.*             // Java standard library
import org.*              // Third-party

// Class structure
class MyManager {
    companion object {
        // Constants first
        const val TIMEOUT_MS = 5000L
    }
    
    // Private mutable state
    private val _state = MutableStateFlow(MyState())
    
    // Public read-only exposure
    val state: StateFlow<MyState> = _state.asStateFlow()
    
    // Private properties
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Public methods
    fun start() { ... }
    
    // Private methods
    private fun handleEvent() { ... }
}
```

### StateFlow Pattern (Project Standard)

```kotlin
// Always use this pattern for observable state
private val _connectedNodes = MutableStateFlow<List<MeshNode>>(emptyList())
val connectedNodes: StateFlow<List<MeshNode>> = _connectedNodes.asStateFlow()

// Update state
_connectedNodes.value = newList
```

### Coroutine Scopes Pattern

```kotlin
// Each manager creates its own supervised scope
private val supervisorJob = SupervisorJob()
private val scope = CoroutineScope(Dispatchers.IO + supervisorJob)

// Cleanup on shutdown
fun shutdown() {
    supervisorJob.cancel()
}
```

### Logging Pattern

```kotlin
// Use extension functions from util/Logger.kt
logD { "Discovery message sent to $ipAddress" }  // Debug
logI { "Network started on port $port" }         // Info
logW { "Rate limiting discovery response" }      // Warning
logE({ "Failed to start network" }, exception)   // Error with throwable

// In tests, enable test mode to suppress actual logging
Logger.isTestMode = true
```

### Style Rules (from ktlint/Spotless)

- **Max line length**: 200 characters
- **Indent**: 4 spaces
- **Wildcard imports**: Allowed (disabled check)
- **Trailing commas**: Recommended for multi-line parameters

### Patterns to Follow

**Data classes for immutable state:**
```kotlin
data class MeshNode(
    val nodeId: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val isDirectConnection: Boolean,
    val lastSeen: Long = System.currentTimeMillis(),
    val hopCount: Int = 1,
    val linkQuality: Float = 1.0f,
)
```

**Sealed classes for events:**
```kotlin
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable?, val message: String) : Result<Nothing>()
    data object Loading : Result<Nothing>()
}
```

**Early returns for validation:**
```kotlin
fun validateDiscoveryPayload(payload: String): DiscoveryPayload? {
    val parts = payload.split("|")
    if (parts.size < 2) {
        logW { "Invalid discovery payload: too few fields" }
        return null
    }
    
    val nodeId = parts[0]
    if (nodeId.isEmpty() || nodeId.length > 36) {
        logW { "Invalid nodeId length" }
        return null
    }
    
    // Continue with validation...
    return DiscoveryPayload(...)
}
```

### Anti-Patterns to Avoid

- ❌ Using `println` or `android.util.Log` directly (use `logD`, `logE`, etc.)
- ❌ Magic numbers outside `AppConfig` object
- ❌ Exposing MutableStateFlow publicly (always use `.asStateFlow()`)
- ❌ Using `GlobalScope` for coroutines (use supervised scopes)
- ❌ Blocking the main thread with network/IO operations

---

## Testing Guidelines

### Test Framework

- **Unit Tests**: JUnit 4 with Kotlin
- **Test Location**: `app/src/test/java/com/entercomm/bikeintercom/`
- **Naming**: `*Test.kt` suffix, tests mirror source package structure

### Test File Conventions

| Pattern | Example |
|---------|---------|
| **Location** | `app/src/test/java/.../mesh/DistanceVectorRouterTest.kt` |
| **Naming** | Class name + `Test` suffix |
| **Method naming** | Backtick descriptive names |

### Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "*.DistanceVectorRouterTest"

# Run specific test method (pattern match)
./gradlew test --tests "*.AdpcmCodecTest.*encode*"

# Run with HTML report
make test-report
# Report at: app/build/reports/tests/testDebugUnitTest/index.html
```

### Test Structure Pattern

```kotlin
class DistanceVectorRouterTest {

    private lateinit var router: DistanceVectorRouter

    @Before
    fun setUp() {
        Logger.isTestMode = true  // Suppress logging
        router = DistanceVectorRouter("node-local")
        router.initialize()
    }

    @After
    fun tearDown() {
        router.clear()
        Logger.isTestMode = false
    }

    // === Section Header for Related Tests ===

    @Test
    fun `addNeighbor creates direct route`() {
        router.addNeighbor("node-a", "192.168.1.2", 8888)

        val route = router.getRoute("node-a")
        assertNotNull("Route should exist", route)
        assertEquals("node-a", route!!.destination)
        assertEquals(1, route.hopCount)
        assertTrue(route.isDirectNeighbor)
    }

    @Test
    fun `processRouteAdvertisement ignores routes from unknown neighbors`() {
        val advertisement = DistanceVectorRouter.RouteAdvertisement(
            sourceNodeId = "unknown-node",
            sequenceNumber = 1,
            routes = listOf(DistanceVectorRouter.AdvertisedRoute("node-b", 1, 1)),
        )

        val changed = router.processRouteAdvertisement(advertisement, "192.168.1.99")
        
        assertFalse("Table should not change from unknown neighbor", changed)
        assertFalse(router.isReachable("node-b"))
    }
}
```

### Mocking Patterns

- **Logger**: Set `Logger.isTestMode = true` in `@Before` to suppress logging
- **Android dependencies**: Use constructor injection for testability
- **Time-based tests**: Use deterministic timestamps or inject clock

### Key Test Classes

| Test Class | Purpose |
|------------|---------|
| `DistanceVectorRouterTest` | Routing algorithm correctness |
| `AdpcmCodecTest` | Audio codec encoding/decoding |
| `PipeDelimitedMeshProtocolTest` | Message serialization |
| `JitterBufferTest` | Audio buffering behavior |
| `GroupCodeUtilsTest` | Group code generation/validation |
| `ConnectionCoordinatorTest` | WiFi Direct + Mesh coordination |

---

## Git & Version Control

### Commit Message Convention

Based on observed patterns, this project uses descriptive commit messages:

```
<type>: <description>

Examples:
- Implement audio effects processing and codec enhancements
- Fix CI workflow and failing unit tests
- Refactor service initialization and audio management
- Add accessibility features and settings management
- auto-claude: subtask-X-Y - Description (for automated tasks)
```

Common prefixes observed:
- **Implement/Add** - New features
- **Fix** - Bug fixes
- **Refactor** - Code restructuring
- **Update** - Modifications to existing code
- **Enhance** - Improvements
- **style:** - Formatting changes

### Branch Naming

- Feature branches should be descriptive
- Use kebab-case for branch names

### Pre-Commit Checks

The pre-commit hook runs automatically (install with `make hooks`):

```bash
# What runs on commit:
1. Spotless format check (formatting)
2. Detekt static analysis (code smells)
3. Android Lint (warnings only, non-blocking)
```

To manually run pre-commit checks:
```bash
make check
```

### Release Process

```bash
# Create and push release tag
make tag-release VERSION=v1.0.0

# Preview changelog
make changelog
```

---

## Debugging & Issue Resolution

### Logging

Use the centralized logger from `util/Logger.kt`:

```kotlin
// Extension function syntax (preferred)
logD { "Discovery message from $senderIp" }
logW { "Rate limiting response" }
logE({ "Failed to connect" }, exception)

// Direct Logger usage
Logger.d("MyTag", { "Debug message" })
Logger.e("MyTag", { "Error message" }, throwable)
```

Log output format: `EnterComm:ClassName: message`

### Error Handling Pattern

```kotlin
// Use sealed Result class for operations that can fail
sealed class MeshError(val message: String, val cause: Throwable? = null) {
    class ConnectionFailed(message: String, cause: Throwable? = null) : MeshError(message, cause)
    class PermissionDenied(message: String) : MeshError(message)
    class NetworkUnavailable(message: String) : MeshError(message)
    class AudioError(message: String, cause: Throwable? = null) : MeshError(message, cause)
}

// Usage with Result.runCatching
val result = Result.runCatching {
    meshManager.startNetwork()
}
result.onSuccess { logD { "Network started" } }
      .onError { msg, ex -> logE({ msg }, ex) }
```

### Common Issues & Solutions

| Issue | Solution |
|-------|----------|
| WiFi Direct not discovering peers | Ensure location permission granted; check `NEARBY_WIFI_DEVICES` on Android 12+ |
| Audio not playing | Check `RECORD_AUDIO` permission; verify AudioManager initialization |
| Mesh connection timing out | Increase `NODE_TIMEOUT` in `AppConfig`; check network broadcast addresses |
| Spotless formatting fails | Run `./gradlew spotlessApply` before committing |
| Detekt baseline issues | Update `config/detekt/baseline.xml` if intentional |

### Debug Commands

```bash
# View app logs (filtered)
make logs

# View all logs with grep
make logs-all

# ADB direct
adb logcat "EnterComm:*" "*:S"

# Clear and follow
adb logcat -c && adb logcat | grep -E "EnterComm|bikeintercom"
```

---

## Boundaries & Permissions

### ✅ Always Do

- Run `make format` after modifying Kotlin files
- Run `make check` before committing (or rely on pre-commit hook)
- Run relevant tests after changes: `./gradlew test --tests "*.YourTestClass"`
- Use `AppConfig` for any timing constants or magic numbers
- Expose StateFlow as read-only via `.asStateFlow()`
- Use `logD`, `logW`, `logE` from `util/Logger.kt`
- Follow the existing StateFlow pattern for observable state

### ⚠️ Ask First

- Adding new dependencies to `gradle/libs.versions.toml`
- Modifying `config/detekt/detekt.yml` rules
- Changing `AppConfig` constants (may affect timing/behavior)
- Modifying `AndroidManifest.xml` permissions
- Adding new foreground service types
- Changes to mesh protocol message format (breaks compatibility)

### 🚫 Never Do

- Commit secrets, API keys, or credentials
- Modify `keystore.properties` or `release-keystore.jks`
- Delete or overwrite `config/detekt/baseline.xml` without review
- Use `GlobalScope` for coroutines
- Expose `MutableStateFlow` publicly
- Add `println` or direct `android.util.Log` calls
- Push directly to main branch
- Modify generated files in `app/build/`

### Files to Never Modify

- `keystore.properties` - Contains keystore passwords
- `release-keystore.jks` - Signing keystore
- `local.properties` - Local SDK paths

### Files to Be Careful With

- `gradle/libs.versions.toml` - Dependency versions
- `config/detekt/detekt.yml` - Static analysis rules
- `app/build.gradle.kts` - Build configuration
- `AndroidManifest.xml` - Permissions and components

---

## Architecture & Design Patterns

### Architectural Pattern

**MVVM + Service-based architecture** with:
- Foreground Service (`MeshNetworkService`) as the central orchestrator
- Manager classes for domain concerns (Audio, Mesh, Location, Group)
- Jetpack Compose UI observing StateFlow

### Layer Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                    UI Layer (Jetpack Compose)                       │
│  MainScreen ─── OnboardingScreen ─── Components                     │
└─────────────────────────┬───────────────────────────────────────────┘
                          │ binds to / observes StateFlow
┌─────────────────────────▼───────────────────────────────────────────┐
│              MeshNetworkService (Foreground Service)                │
│              - Orchestrates all managers                            │
│              - Handles lifecycle, notifications                     │
│              - Exposes ServiceState via StateFlow                   │
└───────────┬─────────────┬─────────────┬─────────────┬───────────────┘
            │             │             │             │
    ┌───────▼───────┬─────▼─────┬───────▼───────┬─────▼─────┐
    │ WiFiDirect    │ MeshNetwork│    Audio     │ Location  │
    │ Manager       │ Manager    │   Manager    │  Manager  │
    │ (P2P disc)    │ (mesh UDP) │  (capture)   │   (GPS)   │
    └───────────────┴─────┬──────┴──────────────┴───────────┘
                          │
              ┌───────────▼───────────┐
              │  DistanceVectorRouter │
              │  (Bellman-Ford)       │
              └───────────────────────┘
```

### Key Design Decisions

1. **Foreground Service**: `MeshNetworkService` runs as a foreground service to maintain mesh connectivity during bike rides, even when the app is backgrounded.

2. **Distance Vector Routing**: Uses Bellman-Ford algorithm with split-horizon/poison-reverse for loop prevention. Max hop count: 15, Infinity metric: 16.

3. **UDP-based Mesh Protocol**: Custom pipe-delimited protocol on ports 8888 (discovery) and 8889 (audio). Message format: `messageId|sourceId|destinationId|type|ttl|timestamp|payload`

4. **ADPCM Audio Codec**: IMA ADPCM for ~4x compression (16-bit PCM → 4-bit ADPCM). 48kHz mono, 20ms frames.

5. **Group Code System**: 4-8 character alphanumeric codes (excluding ambiguous chars: 0, 1, I, L, O) for private group filtering.

### Data Flow

1. **Connection**: WiFiDirectManager discovers peers → P2P group formed → MeshNetworkManager establishes mesh routing
2. **Audio TX**: AudioManager captures → AdpcmCodec encodes → MeshNetworkManager broadcasts via UDP
3. **Audio RX**: MeshNetworkManager receives → AdpcmCodec decodes → AudioManager plays via AudioProcessor

---

## Environment Setup

### Required Permissions (from AndroidManifest.xml)

| Permission | Purpose |
|------------|---------|
| `ACCESS_WIFI_STATE` | Read WiFi state |
| `CHANGE_WIFI_STATE` | Control WiFi |
| `ACCESS_FINE_LOCATION` | WiFi Direct requires location |
| `ACCESS_COARSE_LOCATION` | Location fallback |
| `RECORD_AUDIO` | Microphone access |
| `NEARBY_WIFI_DEVICES` | Android 12+ WiFi Direct |
| `FOREGROUND_SERVICE` | Background operation |
| `FOREGROUND_SERVICE_MICROPHONE` | Audio in foreground |
| `VIBRATE` | Haptic feedback |

### Local Development Setup

```bash
# 1. Clone repository
git clone <repo-url>
cd enter-comm

# 2. Set up local.properties (created by Android Studio)
# Should contain: sdk.dir=/path/to/android/sdk

# 3. Install git hooks
make hooks

# 4. Download dependencies
make deps

# 5. Build debug APK
make build

# 6. Run all checks
make check

# 7. Install on device
make install
```

### IDE Recommendations

- **Android Studio** (Hedgehog or newer)
- Enable "Optimize imports on the fly"
- Configure ktlint plugin for real-time formatting
- Set line length to 200 characters

---

## UI/Component Guidelines

### Component Structure

```
ui/
├── components/           # Reusable composables
│   ├── AccessibilityComponents.kt  # Settings toggles, sliders
│   ├── GroupComponents.kt          # Group info, member list
│   ├── RadarComponents.kt          # Radar visualization
│   └── TechnicalComponents.kt      # Network topology, stats
├── screens/              # Full-screen composables
│   ├── MainScreen.kt               # Tab-based main UI
│   └── OnboardingScreen.kt         # First-launch flow
└── theme/
    ├── Color.kt                    # Color definitions
    ├── Theme.kt                    # Material3 theme
    └── Type.kt                     # Typography
```

### Theme: Pitch Black

The app uses a custom dark theme optimized for outdoor visibility:

```kotlin
// Primary colors (from Color.kt)
val TechGreen = Color(0xFF00FF88)      // Primary accent
val TechCyan = Color(0xFF00D4FF)       // Secondary accent
val TechRed = Color(0xFFFF4444)        // Recording/error
val TechOrange = Color(0xFFFF9500)     // Warnings
val PitchBlack = Color(0xFF000000)     // Background

// Surface colors
val DarkSurface = Color(0xFF121212)
val DarkSurfaceVariant = Color(0xFF1E1E1E)
```

### Compose Patterns

**State hoisting:**
```kotlin
@Composable
fun MyComponent(
    state: MyState,                    // State hoisted up
    onAction: () -> Unit,              // Events hoisted up
    modifier: Modifier = Modifier,     // Modifier always last
) { ... }
```

**Collecting StateFlow:**
```kotlin
val serviceState by meshService?.serviceState?.collectAsState() 
    ?: remember { mutableStateOf(ServiceState()) }
```

---

## Mobile-Specific Guidelines

### Platform Considerations

- **Minimum API**: 24 (Android 7.0)
- **Target API**: 35 (Android 14)
- **WiFi Direct**: Required hardware feature

### Android 12+ Considerations

- `NEARBY_WIFI_DEVICES` permission required for WiFi Direct
- `POST_NOTIFICATIONS` permission required for foreground service notification
- Bluetooth scanning restrictions (if adding BLE in future)

### Battery Optimization

Battery-aware discovery intervals in `AppConfig`:
```kotlin
fun getDiscoveryIntervalForBattery(batteryLevel: Int): Long = when (batteryLevel) {
    in 0..20 -> 120_000L   // 2 minutes when critical
    in 21..50 -> 60_000L   // 1 minute when low
    else -> 30_000L        // 30 seconds normally
}
```

### Wake Locks

The service acquires wake locks for reliable WiFi scanning:
- `WifiManager.WifiLock` - Keeps WiFi radio active
- `PowerManager.WakeLock` - Prevents CPU sleep during scans

---

## Implementation Notes

- WiFi Direct group owner always gets subnet `.1` address (typically `192.168.49.1`)
- Discovery rate-limited to prevent broadcast storms (5s cooldown per IP)
- Audio packets limited to 16KB, decoded samples to 8KB for safety
- Service continues in limited mode if any manager fails to initialize
- Group codes use `CODE_CHARS` without ambiguous characters (`0, 1, I, L, O` excluded)
- Dependency versions managed in `gradle/libs.versions.toml`

