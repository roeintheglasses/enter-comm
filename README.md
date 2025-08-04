# Enter-Comm

**Enter-Comm** is an Android bike intercom app that creates a mesh network using WiFi Direct for offline voice communication between cyclists. The app enables real-time audio communication over 100-200 meter ranges without internet connectivity.

## Features

- **WiFi Direct Mesh Network**: Creates peer-to-peer connections between devices
- **Real-time Audio Communication**: Voice chat between connected cyclists
- **Offline Operation**: No internet or cellular connection required
- **Mesh Network Topology**: Multi-hop routing for extended range
- **Dynamic Network Healing**: Automatically adapts when devices move in/out of range
- **Battery Optimized**: Designed for long bike rides

## Architecture

### Core Components

- **WiFi Direct Manager**: Handles device discovery, connection, and mesh formation
- **Mesh Network Manager**: Manages multi-hop routing and network topology
- **Audio Streaming**: Thread-safe real-time audio communication system
- **UI Layer**: Jetpack Compose interface for device management and communication controls

### Technology Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Networking**: Android WiFi P2P APIs + IPv6 addressing
- **Audio**: Custom audio processing with planned WebRTC integration
- **Architecture**: MVVM with coroutines for asynchronous operations

## Getting Started

### Prerequisites

- Android 7.0 (API level 24) or higher
- Device with WiFi Direct support
- Microphone and speaker permissions

### Installation

1. Clone the repository
2. Open in Android Studio
3. Build and install on your device:
   ```bash
   ./gradlew installDebug
   ```

### Usage

1. Launch the app on multiple devices
2. Enable WiFi Direct and location permissions
3. Tap "Start Network" to begin device discovery
4. Connect to discovered devices
5. Use "Start Recording" to begin voice communication

## Development

### Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run lint checks
./gradlew lint
```

## Current Status

✅ **Working Features:**

- WiFi Direct device discovery and connection
- Mesh network formation and routing
- Audio recording and transmission
- Real-time audio playback (stable, no crashes)
- Network topology management

🔧 **In Development:**

- Audio quality optimization (currently using basic PCM encoding)
- WebRTC integration for enhanced audio processing
- UI improvements and better user feedback

## Contributing

This project is under active development. Key areas for contribution:

- Audio codec optimization
- Network performance improvements
- UI/UX enhancements
- Battery optimization
