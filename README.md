# TailBait
> A personal security Android application that helps detect potential stalking by monitoring Bluetooth Low Energy (BLE) devices across multiple locations.

## Overview

**TailBait** is an open-source Android application designed to enhance personal safety by detecting potential tracking devices. The app continuously scans for Bluetooth Low Energy (BLE) devices in your vicinity, records their locations, and alerts you when a device appears at multiple distinct locations, which could indicate stalking or unauthorized tracking.

### Why This App?

With the proliferation of Bluetooth trackers (AirTags, Tile, etc.) and IoT devices, unwanted tracking has become a serious privacy and safety concern. This app provides a comprehensive solution to:

- **Monitor your surroundings** for unknown BLE devices
- **Detect patterns** that may indicate stalking or harassment
- **Identify your own devices** through Learn Mode to reduce false positives
- **Track location history** of suspicious devices
- **Receive real-time alerts** when threats are detected

### Important Disclaimer

This app is a **security awareness tool** and should be used as part of a comprehensive personal safety strategy. It:
- Does NOT guarantee detection of all tracking devices
- May produce false positives or miss actual threats
- Should NOT replace professional security advice or law enforcement
- Is NOT foolproof - always trust your instincts and seek help if you feel unsafe

## Features
- Detect BLE devices within your proximity and store the location of their sighting
- Alert when devices are
-
## Architecture

### High-Level Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                        │
│  Home | Learn Mode | Device List | Alerts | Map | Settings  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    ViewModel Layer (MVVM)                    │
│  State Management | Business Logic | User Actions           │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   Repository Layer                           │
│  Device | Location | Whitelist | Detection | Alert | Settings│
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Service Layer                            │
│  BLE Scanner | Location Tracker | Detection Worker          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      Data Layer                              │
│  Room Database | Nordic BLE | Google Location API           │
└─────────────────────────────────────────────────────────────┘
```

### Database Schema

The app uses Room with 6 tables:

1. **scanned_devices**: Unique BLE devices with MAC addresses and metadata
2. **locations**: GPS coordinate points with timestamps
3. **device_location_records**: Device-location associations with RSSI and distance
4. **whitelist_entries**: Trusted devices excluded from alerts
5. **alert_history**: Generated alerts with threat scores
6. **app_settings**: User preferences and configuration

See [docs/architecture/PROJECT_OVERVIEW.md](docs/architecture/PROJECT_OVERVIEW.md) for detailed schema information.

### Detection Algorithm

The stalking detection algorithm uses multi-factor analysis:

```kotlin
ThreatScore = (
    LocationCount * 0.3 +
    DistanceFactor * 0.25 +
    TimeCorrelation * 0.2 +
    Consistency * 0.15 +
    DeviceType * 0.1
)
```

**Threat Levels:**
- **0.9+**: CRITICAL - Immediate alert
- **0.75-0.89**: HIGH - Strong warning
- **0.6-0.74**: MEDIUM - Notification
- **0.5-0.59**: LOW - Logged
- **<0.5**: No alert

## Getting Started

### Prerequisites

- **Android Studio**: Hedgehog (2023.1.1) or later
- **JDK**: 17 or later
- **Android SDK**: API 26-35
- **Gradle**: 8.2+ (wrapper included)
- **Git**: For cloning the repository

### Permissions Required

The app requires the following Android permissions:

```xml
<!-- Bluetooth (Android 12+) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Location -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

<!-- Notifications (Android 13+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Services -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
```

## Installation

### Option 1: Download APK (Coming Soon)

Pre-built APKs will be available on the [Releases](https://github.com/[your-username]/ble-device-tracker/releases) page.

### Option 2: Build from Source

See [Building from Source](#building-from-source) section below.

### Option 3: Google Play Store (Future)

The app may be published to Google Play Store in the future.

## Usage Guide

### First Launch

1. **Grant Permissions**: The app will request Bluetooth, Location, and Notification permissions
2. **Choose Scanning Mode**: Select Continuous, Periodic, or Location-Based
3. **Run Learn Mode**: Identify and whitelist your own devices to prevent false positives
4. **Start Scanning**: Begin monitoring for suspicious devices

### Learn Mode Workflow

1. Navigate to **Learn Mode** screen
2. Tap **Start Learning** to begin manual scan
3. Wait for devices to appear (usually 10-30 seconds)
4. Select **your own devices** (phone, watch, car, etc.)
5. Tap **Whitelist Selected** to exclude them from alerts
6. Repeat in different locations for comprehensive coverage

### Understanding Alerts

When a suspicious device is detected:

1. **Notification**: You'll receive a notification with threat level
2. **Review Details**: Tap to see device information and locations
3. **Take Action**:
   - **CRITICAL/HIGH**: Consider contacting law enforcement
   - **MEDIUM**: Monitor the device, export data if needed
   - **LOW**: Review in Alert History

### Exporting Data

To export data for law enforcement or analysis:

1. Go to **Settings** > **Export Data**
2. Choose format (CSV, JSON, or GPX)
3. Select data types (devices, locations, alerts)
4. Share or save to device storage

### Whitelist Management

To manually add trusted devices:

1. Go to **Device List** screen
2. Long-press a device or tap the menu icon
3. Select **Add to Whitelist**
4. Optionally add a nickname and category

## Building from Source

> **📖 For complete build instructions, troubleshooting, and CI/CD setup, see [docs/development/BUILD.md](docs/development/BUILD.md)**

### Quick Start

```bash
# Clone the repository
git clone https://github.com/[your-username]/ble-device-tracker.git
cd ble-device-tracker

# Configure Google Maps API Key
cp local.properties.example local.properties
# Edit local.properties and add your MAPS_API_KEY

# Build debug APK
./gradlew assembleDebug

# APK location
app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions

The project includes automated CI/CD workflows that build APKs on every push:

- **Debug Build**: `.github/workflows/android-build.yml` - Runs on push/PR
- **Release Build**: `.github/workflows/release-build.yml` - Runs on version tags

Download built APKs from the **Actions** tab after each workflow run.

### Prerequisites

- **JDK 17** or later ([Download](https://adoptium.net/))
- **Android Studio** Hedgehog or later (optional, recommended)
- **Gradle 8.14.3** (included via wrapper)

### Configuration

#### Google Maps API Key (Required)

The app uses Google Maps to display device locations. You need to obtain a Google Maps API key:

1. **Get an API Key**:
   - Go to [Google Cloud Console](https://console.cloud.google.com/)
   - Create a new project or select an existing one
   - Enable **Maps SDK for Android**
   - Create credentials (API Key)
   - Restrict the key to Android apps and your app's package name (`untailed`)

2. **Configure the API Key**:
   - Copy `local.properties.example` to `local.properties`
   - Add your API key:
     ```properties
     MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY_HERE
     ```
   - The `local.properties` file is ignored by git for security

3. **Alternative**: Set as environment variable:
   ```bash
   export MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY_HERE
   ```

> **Note**: Without a valid API key, the map view will not function properly. A placeholder key is used for builds, but it won't display actual maps.

#### Build Variants

- **debug**: Development build with debug logging
- **release**: Production build with ProGuard/R8 enabled

#### Gradle Properties

Configure in `gradle.properties`:

```properties
# Signing (see docs/development/keystore-setup.md)
RELEASE_STORE_FILE=path/to/keystore.jks
RELEASE_STORE_PASSWORD=your_keystore_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password

# Build optimization
org.gradle.jvmargs=-Xmx2048m
org.gradle.parallel=true
org.gradle.caching=true
```

## Battery Optimization

The BLE Device Tracker app is designed with battery efficiency in mind while maintaining effective security monitoring.

### Scanning Modes & Battery Impact

| Mode | Battery Usage | Best For |
|------|--------------|----------|
| **Continuous** | ~3-5% per hour | Maximum security, short trips |
| **Periodic** | ~1-2% per hour | Daily use, balanced protection |
| **Location-Based** | <1% per hour | Long-term use, efficiency priority |
| **Manual Only** | Minimal | Occasional checks, maximum efficiency |

### Battery Optimization Techniques

The app implements several strategies to minimize battery drain:

#### 1. **Adaptive Scan Intervals**
- Scan intervals automatically adjust based on battery level
- When battery < 20%: Increases scan interval by 2x
- When battery < 10%: Increases scan interval by 4x
- Can be configured in Settings

#### 2. **Efficient BLE Scanning**
- Uses Nordic BLE library optimized for low power consumption
- Implements scan windowing (30 seconds scan / 5 minutes idle)
- RSSI threshold filtering (-85 dBm) reduces processing of weak signals
- Batch processing of scan results to minimize wake-ups

#### 3. **Location Services Optimization**
- Uses `PRIORITY_BALANCED_POWER_ACCURACY` for location requests
- Location updates only when significant movement detected (>50 meters)
- Respects system battery-saving mode
- Fused Location Provider minimizes GPS usage

#### 4. **WorkManager Scheduling**
- Detection algorithm runs every 15 minutes (configurable)
- Data cleanup worker runs once daily
- Uses system job scheduling for optimal battery efficiency
- No wake locks held longer than necessary

#### 5. **Database Optimization**
- Indexed queries for fast lookups
- Automatic data cleanup based on retention settings (default 30 days)
- Batch inserts for multiple records
- Write-Ahead Logging (WAL) mode for concurrent access

#### 6. **Background Processing**
- Minimal foreground service overhead
- Notification updates throttled to prevent excessive wake-ups
- Coroutines with proper scope management prevent memory leaks
- All ViewModels properly cleanup resources in `onCleared()`

### Battery Usage Monitoring

To monitor the app's battery usage:

1. **Settings** > **Battery** > **Battery Usage**
2. Find "Untailed" in the list
3. View detailed usage breakdown

### Power Consumption Best Practices

For optimal battery life:

1. **Use Location-Based Mode** for daily monitoring
2. **Run Learn Mode** periodically rather than keeping continuous mode on
3. **Adjust data retention** to 7-14 days if storage and processing speed is a concern
4. **Enable battery saver mode** in Android settings (app respects this)
5. **Whitelist known devices** to reduce unnecessary detection processing

### Technical Details

#### Foreground Service
The continuous scanning mode uses a foreground service with:
- Low priority notification (minimal battery impact)
- Proper lifecycle management
- Automatic restart on system kills (configurable)

#### Wake Locks
The app uses wake locks minimally:
- Only during active BLE scanning (< 30 seconds)
- Automatically released on completion
- No persistent wake locks

#### Network Usage
The app has **zero network usage** - all processing is local:
- No API calls
- No cloud sync
- No analytics tracking
- No advertisements

This contributes to better battery life as network radios remain off.

## Testing

### Unit Tests

Run all unit tests:

```bash
./gradlew test
```

### Instrumentation Tests

Run on connected device:

```bash
./gradlew connectedAndroidTest
```

### Test Coverage

Generate coverage report:

```bash
./gradlew jacocoTestReport
```

View report at: `app/build/reports/jacoco/test/html/index.html`

### Manual Testing Checklist

- [ ] BLE scanning in all modes (continuous, periodic, location-based)
- [ ] Learn Mode device identification
- [ ] Whitelist management
- [ ] Alert generation and notifications
- [ ] Map view with device locations
- [ ] Data export in all formats
- [ ] Permission handling and edge cases
- [ ] Battery usage and optimization
- [ ] Background service persistence
- [ ] Database migrations

## Privacy & Security

### Data Storage

- **100% Local**: All data stored on device using Room/SQLite
- **No Cloud Sync**: No data transmitted to external servers
- **No Analytics**: No third-party tracking or analytics services
- **Encrypted**: Protected by Android's encryption-at-rest

### Privacy Policy

See [docs/legal/PRIVACY_POLICY.md](docs/legal/PRIVACY_POLICY.md) for complete privacy information.

### Security Considerations

- **Source Code Transparency**: Fully open source for security audits
- **Minimal Permissions**: Only requests necessary permissions
- **No Data Sharing**: Never shares data with third parties
- **User Control**: Complete control over data retention and deletion

### Responsible Disclosure

If you discover a security vulnerability:

1. **DO NOT** open a public issue
2. Email: security@untailed.app
3. Provide detailed description and reproduction steps
4. Allow 90 days for patching before public disclosure

## Contributing

We welcome contributions from the community! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Quick Start for Contributors

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/amazing-feature`
3. Make your changes
4. Write/update tests
5. Run linting: `./gradlew lint`
6. Commit: `git commit -m 'Add amazing feature'`
7. Push: `git push origin feature/amazing-feature`
8. Open a Pull Request

### Code Style

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use Android Studio default formatter
- Run `./gradlew ktlintFormat` before committing
- Write meaningful commit messages

### Code of Conduct

Please review our [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) before contributing.

## License

This project is licensed under the **Apache License 2.0** - see the [LICENSE](LICENSE) file for details.

### What This Means

- ✅ Commercial use allowed
- ✅ Modification allowed
- ✅ Distribution allowed
- ✅ Patent use allowed
- ✅ Private use allowed
- ⚠️ License and copyright notice required
- ⚠️ State changes required
- ❌ Trademark use not allowed
- ❌ Liability not provided
- ❌ Warranty not provided

 Need for open-source privacy-focused security tools

## FAQ

### Q: Does this app drain my battery?

**A:** Battery usage depends on scanning mode:
- **Continuous**: ~3-5% per hour (acceptable for security use)
- **Periodic**: ~1-2% per hour (recommended for daily use)
- **Location-Based**: <1% per hour (most efficient)

### Q: Can this detect all tracking devices?

**A:** No. The app can only detect devices that:
- Use Bluetooth Low Energy (BLE)
- Are actively advertising
- Are within range (~30-100 meters)
- Are not using advanced anti-detection measures

### Q: Will this work with AirTags?

**A:** Yes, AirTags and similar BLE trackers can be detected. However, some devices use MAC address randomization which may affect tracking accuracy.

### Q: Is my location data safe?

**A:** Yes. All location data is stored locally on your device and never transmitted elsewhere. You have complete control over retention and deletion.

### Q: Can I use this app commercially?

**A:** Yes, the Apache 2.0 license allows commercial use with attribution.

### Q: Does this replace Apple's AirTag detection?

**A:** No, it complements it. Apple's built-in detection only works for AirTags. This app detects all BLE devices for broader coverage.

---


**Stay safe and protect your privacy!**

If you find this project useful, please give it a ⭐ on GitHub and share it with others who might benefit from it.

