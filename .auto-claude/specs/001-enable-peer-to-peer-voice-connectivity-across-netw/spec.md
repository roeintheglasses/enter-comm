# Specification: Enable Peer-to-Peer Voice Connectivity Across Networks

## Overview

This feature enables Enter-Comm devices to discover and establish voice communication without requiring any shared WiFi network or cellular connectivity. Currently, the app relies on LAN-based UDP broadcast discovery which only works when devices share the same network. The implementation will prioritize WiFi Direct as the primary discovery and connection mechanism, allowing devices to form ad-hoc peer-to-peer connections for mesh intercom communication regardless of their network state.

## Workflow Type

**Type**: feature

**Rationale**: This is a significant architectural enhancement that changes the primary connection flow from LAN-based discovery to WiFi Direct-based peer-to-peer connectivity. It requires modifications to the connection lifecycle, discovery mechanisms, and integration between WiFi Direct and mesh networking components.

## Task Scope

### Services Involved
- **app** (primary) - Android bike intercom application with mesh networking

### This Task Will:
- [ ] Modify `ConnectionCoordinator` to make WiFi Direct the primary discovery mechanism
- [ ] Update `MeshNetworkService` to automatically start WiFi Direct discovery when mesh starts
- [ ] Ensure `WiFiDirectManager` properly integrates with `MeshNetworkManager` for seamless handoff
- [ ] Add automatic connection establishment when matching group codes are discovered
- [ ] Update the connection flow to work without any pre-existing network infrastructure
- [ ] Ensure mesh networking works over WiFi Direct connections once established

### Out of Scope:
- Bluetooth connectivity (WiFi Direct provides sufficient range for bike intercom use case)
- iOS support (this is an Android-only application)
- Changes to the audio codec or quality
- Changes to the mesh routing algorithm
- Multi-group connections (devices still connect to one group at a time)

## Service Context

### Enter-Comm Android App

**Tech Stack:**
- Language: Kotlin
- Framework: Android SDK with Jetpack Compose
- Build: Gradle with Kotlin DSL
- Key directories: `app/src/main/java/com/entercomm/bikeintercom/`

**Entry Point:** `app/src/main/java/com/entercomm/bikeintercom/MainActivity.kt`

**How to Run:**
```bash
make build          # Build debug APK
make install        # Install on device
make run            # Install and launch
make test           # Run unit tests
```

**Ports:**
- Discovery: UDP 8888
- Audio: UDP 8889

## Files to Modify

| File | Service | What to Change |
|------|---------|---------------|
| `app/src/main/java/com/entercomm/bikeintercom/service/ConnectionCoordinator.kt` | app | Make WiFi Direct the primary discovery mechanism; remove dependency on LAN connectivity |
| `app/src/main/java/com/entercomm/bikeintercom/mesh/MeshNetworkService.kt` | app | Auto-start WiFi Direct discovery when mesh network starts; coordinate group code matching |
| `app/src/main/java/com/entercomm/bikeintercom/wifidirect/WiFiDirectManager.kt` | app | Add group code filtering to WiFi Direct discovery; improve automatic connection handling |
| `app/src/main/java/com/entercomm/bikeintercom/mesh/MeshNetworkManager.kt` | app | Ensure mesh works seamlessly over WiFi Direct connections; prioritize WiFi Direct interface |
| `app/src/main/java/com/entercomm/bikeintercom/config/AppConfig.kt` | app | Add WiFi Direct discovery intervals and priority settings |

## Files to Reference

These files show patterns to follow:

| File | Pattern to Copy |
|------|----------------|
| `app/src/main/java/com/entercomm/bikeintercom/mesh/MeshNetworkManager.kt` | StateFlow exposure, coroutine patterns, node discovery handling |
| `app/src/main/java/com/entercomm/bikeintercom/service/ConnectionCoordinator.kt` | Event handling, connection state management, retry logic |
| `app/src/main/java/com/entercomm/bikeintercom/wifidirect/WiFiDirectManager.kt` | WiFi P2P API usage, permission handling, broadcast receiver patterns |
| `app/src/main/java/com/entercomm/bikeintercom/config/AppConfig.kt` | Configuration organization, timing constants |

## Patterns to Follow

### StateFlow Exposure Pattern

From `MeshNetworkManager.kt`:

```kotlin
private val _connectedNodes = MutableStateFlow<List<MeshNode>>(emptyList())
val connectedNodes: StateFlow<List<MeshNode>> = _connectedNodes.asStateFlow()

private val _isActive = MutableStateFlow(false)
val isActive: StateFlow<Boolean> = _isActive.asStateFlow()
```

**Key Points:**
- All managers expose state via `MutableStateFlow` with public `StateFlow` getter
- Use `asStateFlow()` for immutable public access
- Update state atomically with `.value = `

### Event Channel Pattern

From `WiFiDirectManager.kt`:

```kotlin
sealed class WiFiDirectEvent {
    object WiFiP2pEnabled : WiFiDirectEvent()
    object WiFiP2pDisabled : WiFiDirectEvent()
    data class PeersChanged(val peers: List<WifiP2pDevice>) : WiFiDirectEvent()
    data class ConnectionChanged(val info: WifiP2pInfo?) : WiFiDirectEvent()
    // ...
}

private val eventChannel = Channel<WiFiDirectEvent>(Channel.UNLIMITED)

fun getEvents() = eventChannel.receiveAsFlow()
```

**Key Points:**
- Use sealed classes for type-safe events
- Channel with UNLIMITED buffer for non-blocking sends
- Expose as Flow for collection

### Connection Retry Pattern

From `ConnectionCoordinator.kt`:

```kotlin
private suspend fun connectToMeshWithRetry(targetIP: String) {
    repeat(AppConfig.Service.RETRY_MAX_ATTEMPTS) { attempt ->
        try {
            logD { "Mesh connection attempt ${attempt + 1}/${AppConfig.Service.RETRY_MAX_ATTEMPTS}" }
            meshNetworkManager.addDirectConnection(targetIP, AppConfig.Mesh.DISCOVERY_PORT)

            delay(AppConfig.Service.MESH_CONNECTION_VERIFY_DELAY_MS)

            if (meshNetworkManager.connectedNodes.value.isNotEmpty()) {
                logD { "Mesh connection established successfully" }
                _events.emit(ConnectionEvent.ConnectionEstablished(targetIP))
                return
            }

            delay(AppConfig.Service.RETRY_DELAY_MS * (attempt + 1))
        } catch (e: Exception) {
            logE({ "Mesh connection attempt failed" }, e)
        }
    }
}
```

**Key Points:**
- Use `repeat()` for retry loops
- Exponential backoff with `(attempt + 1)` multiplier
- Verify connection success with state check
- Emit events for UI feedback

## Requirements

### Functional Requirements

1. **WiFi Direct Primary Discovery**
   - Description: Devices must discover each other via WiFi Direct without requiring any existing network connection
   - Acceptance: When two devices with matching group codes are within WiFi Direct range (~200m), they should discover each other within 30 seconds

2. **Automatic Connection Establishment**
   - Description: When devices with matching group codes discover each other, they should automatically establish a WiFi Direct connection
   - Acceptance: After discovery, connection should be established within 15 seconds without user intervention

3. **Seamless Mesh Integration**
   - Description: Once WiFi Direct connection is established, mesh networking should automatically start working over the connection
   - Acceptance: Audio transmission should begin working within 5 seconds of WiFi Direct connection establishment

4. **No Network Dependency**
   - Description: The connection flow must work when devices have no WiFi network and no cellular connection
   - Acceptance: Voice communication works when both devices have WiFi and mobile data disabled

5. **Group Code Filtering**
   - Description: Only connect to devices with matching group codes during WiFi Direct discovery
   - Acceptance: Devices with different group codes should not establish connections

### Edge Cases

1. **Multiple Devices** - When 3+ devices are present, group owner should accept all clients with matching codes; mesh routing handles multi-hop
2. **Group Owner Already Exists** - If joining an existing group, connect as client; don't try to create competing group
3. **Connection Dropped** - Auto-retry connection with backoff; maintain mesh state for fast reconnection
4. **Permission Denied** - Gracefully handle missing NEARBY_WIFI_DEVICES or FINE_LOCATION permissions with user guidance
5. **WiFi Direct Busy** - Handle P2P_BUSY errors by retrying after cooldown period

## Implementation Notes

### DO
- Follow the StateFlow exposure pattern for all new state
- Use `AppConfig` for all timing constants and magic numbers
- Use `logD`, `logW`, `logE` from `util/Logger.kt` for logging
- Start WiFi Direct discovery automatically when mesh network starts
- Implement group code matching in WiFi Direct service discovery
- Use WiFi Direct's service discovery mechanism for group code matching
- Handle WiFi Direct events asynchronously via coroutines

### DON'T
- Don't remove LAN-based discovery entirely - keep as fallback when devices ARE on same network
- Don't block the main thread during WiFi Direct operations
- Don't assume internet connectivity is available
- Don't create new AudioManager instances - reuse existing infrastructure
- Don't modify the mesh routing algorithm (DistanceVectorRouter)

## Development Environment

### Start Services

```bash
make build          # Build debug APK
make install        # Install on device
make run            # Install and launch app
make test           # Run unit tests
make check          # Run all checks (format, detekt, lint, test)
make logs           # Show filtered app logs
```

### Required Permissions (already in manifest)
- `ACCESS_WIFI_STATE`
- `CHANGE_WIFI_STATE`
- `ACCESS_NETWORK_STATE`
- `CHANGE_NETWORK_STATE`
- `ACCESS_FINE_LOCATION`
- `NEARBY_WIFI_DEVICES` (Android 13+)
- `RECORD_AUDIO`
- `FOREGROUND_SERVICE`

### Required Environment Variables
- None (standalone Android app)

## Success Criteria

The task is complete when:

1. [ ] Two devices can discover each other via WiFi Direct without any network connection
2. [ ] Devices with matching group codes automatically connect via WiFi Direct
3. [ ] Voice communication works over WiFi Direct connection without internet
4. [ ] Existing LAN-based discovery still works as fallback when on same network
5. [ ] No console errors during connection/discovery flow
6. [ ] Existing tests still pass (`make test`)
7. [ ] Connection works when both devices have WiFi networks disabled
8. [ ] Connection works when both devices have mobile data disabled

## QA Acceptance Criteria

**CRITICAL**: These criteria must be verified by the QA Agent before sign-off.

### Unit Tests
| Test | File | What to Verify |
|------|------|----------------|
| WiFi Direct integration | `WiFiDirectManagerTest.kt` (new) | Group code filtering in discovery works |
| Connection flow | `ConnectionCoordinatorTest.kt` (new) | WiFi Direct primary flow works |
| Existing mesh tests | `MeshNetworkManagerTest.kt` | No regressions in mesh networking |
| Existing routing tests | `DistanceVectorRouterTest.kt` | Routing still works correctly |

### Integration Tests
| Test | Services | What to Verify |
|------|----------|----------------|
| WiFi Direct to Mesh | WiFiDirectManager ↔ MeshNetworkManager | Connection established, mesh routes created |
| Discovery to Audio | ConnectionCoordinator ↔ AudioManager | Voice transmission works after connection |

### End-to-End Tests
| Flow | Steps | Expected Outcome |
|------|-------|------------------|
| Offline Connection | 1. Disable WiFi/mobile on both devices 2. Start app with same group code 3. Wait for discovery | Devices connect and voice works |
| Group Code Filtering | 1. Start apps with different group codes 2. Wait 1 minute | Devices should NOT connect |
| Multi-device | 1. Connect 3 devices with same code 2. Test voice from device 1 | Voice heard on devices 2 and 3 |

### Browser Verification (if frontend)
| Page/Component | URL | Checks |
|----------------|-----|--------|
| N/A - Native Android app | - | - |

### Database Verification (if applicable)
| Check | Query/Command | Expected |
|-------|---------------|----------|
| N/A - No database | - | - |

### QA Sign-off Requirements
- [ ] All unit tests pass (`make test`)
- [ ] All integration tests pass
- [ ] All E2E tests pass on physical Android devices
- [ ] Manual testing on 2+ physical devices without network
- [ ] No regressions in existing functionality
- [ ] Code follows established patterns (StateFlow, Events, AppConfig)
- [ ] No security vulnerabilities introduced
- [ ] Permissions handled gracefully with user guidance
