# LanChatShare

Milestone B adds foreground Bluetooth discovery, two-phone verification and a persistent trusted-device list. Chats is home. Wi-Fi sessions, messaging and file transfers follow in later milestones.

## Build

Open the root folder in Android Studio with JDK 17 or later, Android SDK Platform 37.1, and a configured Android SDK location. On Windows run `./gradlew.bat :app:assembleDebug :core:data:testDebugUnitTest :app:lintDebug`. On macOS or Linux use `./gradlew`. The debug APK is generated under `app/build/outputs/apk/debug`. The build downloads dependencies. The installed app works offline. Nearby explains the Bluetooth permissions when opened.

For the Compose flow test, use a fresh emulator or dedicated test device with empty app data, then run `./gradlew.bat :app:connectedDebugAndroidTest`. The test writes a profile to that test installation. The repository test independently reopens a real preferences file across three store lifetimes.

## Phone checks for milestone B

1. Install the debug APK on two Android 11+ phones. Switch off Wi-Fi and mobile data. Open Nearby on both, grant the requested permissions and keep the screens awake. On Android 11 enable system location for BLE scanning.
2. Select the other phone on one device. Complete any Android Bluetooth bonding prompt, compare the six digits in LanChatShare and confirm on both phones. Check that both trusted lists show the full display name.
3. On a fresh pairing, cancel on either side or leave Nearby before accepting. Verify neither phone gains a trusted record. Retry. Switch Bluetooth off during pairing and restore it.
4. Force-stop and reopen both apps. Reconnect. Matching pins on both phones skip the app code. Repeat with identical names and emoji. Advertisements carry a short name and device suffix. The handshake supplies the full name.
5. Deny permission, then restore it from App settings. Check recovery and stale-device removal. Clearing app data on a dedicated test phone creates a new UUID and key.
6. Leave the comparison open for 90 seconds. Verify pairing ends and neither new peer is saved. On a shared Wi-Fi network, pair again and check the verified status for the peer IP and port 47831. With Wi-Fi off, pairing should still succeed. Milestone C will bind the TLS listener and check reachability.
7. Run `./gradlew.bat :core:connectivity:testDebugUnitTest` for both confirmation paths, key changes, malformed frames and repeated connections. Compare discovery and bonding across manufacturers on physical phones.

## Phone checks for milestone A

1. Launch offline. Try spaces and a 25-character name, then enter a valid name with emoji. Continue should open Chats.
2. Force-stop from Android Settings and reopen. Chats should appear directly. Under Settings → Profile, change the name and avatar colour. Force-stop again and verify both edits and the original device identity persist.
3. Open Share files from each tab and return using Back. Check the layout in landscape and on a tablet. Enable dark mode, large text and TalkBack.
4. Compare the launcher icon and themed monochrome icon on a supported launcher. About should show the same mark and wordmark.

## Upcoming network behaviour

Pairing requests Bluetooth permissions at point of use. Android 11 requires location permission for BLE scanning. Android 12+ separates scan, advertise and connect permissions. Reading the connected Wi-Fi interface uses ACCESS_NETWORK_STATE. The Nearby Wi-Fi Devices runtime permission will be requested for local-only hotspot creation in milestone C on Android 13+. Network and notification features will have version-specific rationales when implemented.

For host testing in milestone C, turn mobile data off, turn on the phone hotspot, join from two clients, and open the app on all three phones. Verify host-to-client and client-to-host communication, then disconnect and rejoin one client. Manufacturer-specific discovery, numeric verification, and isolation behaviour need physical testing.

See [architecture and physical test scenarios](ARCHITECTURE.md), [design specification](DESIGN.md), and the [bundled font licence](core/design/OFL.txt).


If Windows reports “Could not find or load main class” followed by part of a PATH entry when launching Gradle Test Executor, check PATH for stray quotation marks. In PowerShell, run `$env:Path = $env:Path.Replace('"', '')` for the current shell, then rerun the test command with `--no-daemon`.


