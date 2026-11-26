# LanChatShare

[Transfer research](docs/TRANSFER_RESEARCH.md) · [Benchmark results](docs/BENCHMARKS.md) · [Engineering decisions](docs/DECISIONS.md)

Chats opens directly after the one-time name prompt. Verify a phone in Nearby, then tap its name in Chats to send text over local Wi-Fi. Both phones need this build and local network access. A previously verified phone keeps its pairing when you install the update over the earlier APK.

## Check chat on two phones

1. Update both installations without clearing app data. Join the same Wi-Fi network with mobile data off and open Chats. Allow local network access if prompted.
2. Send a message and reply. Each phone should show Delivered after the other saves its message.
3. Disconnect one phone, queue a message, then reconnect. Check that the message appears once and its receipt changes to Delivered.
4. Repeat with one phone hosting a hotspot, then swap hosts. With a third phone, disconnect and rejoin one client while the other continues chatting.
5. In Nearby, choose Forget on a trusted phone and confirm. Check that it cannot reconnect until you verify it again. Forgetting removes unsent messages and retains delivered history.

## Share photos and files

Choose Original for unchanged bytes or Standard for a smaller photo or video. The last choice is saved on this phone and is editable in Settings → Media quality. The chat picker shows the recipient before sending. The offer can include a small preview while the file awaits acceptance.

Update both phones. In a conversation, tap **Photo** and pick an image. The other phone can accept it in chat, see it inline and use **Save a copy** to export the original. Incoming text and photo offers bring the open conversation to the newest item.

In **Share files**, pick photos, videos, documents, APKs, vCards or text, select one or more paired phones and tap **Send**. You can also choose LanChatShare in the gallery or another app's Android Share sheet. It opens the recipient picker before sending. Each recipient decides independently. **Transfers** shows requests, progress, pause, resume, retry and history. Enable incoming-file notifications there to accept or decline in the background.

The app stages a private copy before offering it. Interrupted downloads resume from a recorded 256 KiB boundary and undergo full SHA-256 verification before becoming available. Both phones need free space for their copies. After an Android force-stop, reopen the app.

### Physical checks for Share

1. Scroll up in a busy conversation. Have the other phone send text and then a photo. Check that each appears in view and that accepting the photo shows its preview. Export it with Save a copy.
2. Send a large file, pause and resume, disconnect Wi-Fi partway and rejoin. Background and reopen the app. Compare the exported file's SHA-256 with the original.
3. Choose two phones, accept on one and decline on the other. Check their independent statuses. Repeat on a hotspot with mobile data off.
4. Try an empty file, a file larger than 4 GiB where space permits, low storage and an APK. Check consent and recovery with notifications allowed and denied.

## Background operation and notifications

Under Settings → Notifications and reachability, **Stay reachable** keeps the authenticated LAN listener running while the app is closed. With it off, leaving the app stops the listener after active connected transfers finish. There is no push service. If Android kills or force-stops the service, peers queue messages locally until you reconnect. Open the app to restart it after a force-stop.

Allow notifications to see Messages, File requests, Transfers and Connection status. Reply or mark a conversation as read from a message alert. Opening the conversation clears its alert. Quiet hours use the phone's local time. Preview and lock-screen controls are in the same settings screen. Android's channel settings and Do Not Disturb also apply.

Settings → Diagnostics shows whether notifications, Wi-Fi, Bluetooth and the listener are available and whether battery optimization is active. If screen-off transfers pause, review the app's battery settings. Samsung has sleeping-app lists, Xiaomi has app battery saver and background autostart, Oppo and OnePlus have app battery usage controls, and Vivo has background power management. Names vary by model. Opening battery settings is optional.

LanChatShare uses the connected-device foreground service for communication with another phone. On Android 14 and newer it declares the service type, its permission and the qualifying Wi-Fi permission. Android 15's six-hour background budget applies to dataSync and mediaProcessing. During file I/O the app requests a partial CPU wake lock and a Wi-Fi lock. Android 14 and newer map high-performance Wi-Fi locks to low-latency mode, which has foreground and screen-on restrictions. Android and OEM power policies can still reduce speed or interrupt the LAN. [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) · [Android 15 timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) · [Wi-Fi lock restrictions](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_HIGH_PERF)

### Two-phone acceptance checklist

- Install the update over both existing installations. On Android 13+, allow alerts on one phone and deny them on the other. Verify the settings link can enable them later.
- Send messages while the recipient is in that conversation, in a different chat, on the home screen, locked and after swiping the app from Recents. Check sender identity, preview privacy, quiet hours, inline reply, Mark as read and tap-to-open. Switch Stay reachable off and on. Force-stop separately and check queued delivery after reopening.
- Send 1 MB, 30 MB, 100 MB and 1 GB files in both directions. Compare SHA-256 after export. Record devices, OS, Wi-Fi band, file size and elapsed time. Use both router Wi-Fi and a hotspot.
- During a sufficiently long transfer lock only the sender, only the receiver, then both. Repeat for 5 and 15 minutes. Record the last offset before locking, the next offset or completion after waking, elapsed time and checksum. Repeat after swiping away the app and after toggling Wi-Fi. Check Diagnostics and repeat with the battery exception if the OEM suspends the app.
- Test low storage, pause, cancellation from either phone and file-request Accept/Decline. Verify ongoing transfer notifications clear and CPU and Wi-Fi locks release after completion or interruption.
- For the later motion and theme checkpoints, repeat in both light and dark mode, with large fonts and reduced motion.

## Build

### Measure transfer speed

Open Settings → Diagnostics → Transfer benchmark. Choose a paired phone and run 10 MB, 100 MB and 1 GB separately. Accept each offer on the second phone and wait for Delivered. Use Copy results or Export JSON on both phones. Repeat on the same router with the receiver locked, then on a hotspot. Label the reports with phone models, which phone hosted the network, and which screen was locked. Negotiated Wi-Fi Mbps is a link rate; MB/s is measured application throughput. The meter starts at observed acceptance and includes final verification. After a process restart the report identifies the offset at which measurement resumed.

Open the root folder in Android Studio with JDK 17 or later, Android SDK Platform 37.1, and a configured Android SDK location. On Windows run `./gradlew.bat :app:assembleDebug :core:data:testDebugUnitTest :app:lintDebug`. On macOS or Linux use `./gradlew`. The debug APK is generated under `app/build/outputs/apk/debug`. The build downloads dependencies. The installed app works offline. Nearby explains the Bluetooth permissions when opened.

For the Compose flow test, use a fresh emulator or dedicated test device with empty app data, then run `./gradlew.bat :app:connectedDebugAndroidTest`. The test writes a profile to that test installation. The repository test independently reopens a real preferences file across three store lifetimes.

## Phone checks for milestone B

1. Install the debug APK on two Android 11+ phones. Switch off Wi-Fi and mobile data. Open Nearby on both, grant the requested permissions and keep the screens awake. On Android 11 enable system location for BLE scanning.
2. Select the other phone on one device. Complete any Android Bluetooth bonding prompt, compare the six digits in LanChatShare and confirm on both phones. Check that both trusted lists show the full display name.
3. On a fresh pairing, cancel on either side or leave Nearby before accepting. Verify neither phone gains a trusted record. Retry. Switch Bluetooth off during pairing and restore it.
4. Force-stop and reopen both apps. Reconnect. Matching pins on both phones skip the app code. Repeat with identical names and emoji. Advertisements carry a short name and device suffix. The handshake supplies the full name.
5. Deny permission, then restore it from App settings. Check recovery and stale-device removal. Clearing app data on a dedicated test phone creates a new UUID and key.
6. Leave the comparison open for 90 seconds. Verify pairing ends and neither new peer is saved. On a shared Wi-Fi network, pair again and check the verified status for the peer IP and port 47831. With Wi-Fi off, pairing should still succeed. Open Chats to check the subsequent Wi-Fi connection.
7. Run `./gradlew.bat :core:connectivity:testDebugUnitTest` for both confirmation paths, key changes, malformed frames and repeated connections. Compare discovery and bonding across manufacturers on physical phones.

## Phone checks for milestone A

1. Launch offline. Try spaces and a 25-character name, then enter a valid name with emoji. Continue should open Chats.
2. Force-stop from Android Settings and reopen. Chats should appear directly. Under Settings → Profile, change the name and avatar colour. Force-stop again and verify both edits and the original device identity persist.
3. Open Share files from each tab and return using Back. Check the layout in landscape and on a tablet. Enable dark mode, large text and TalkBack.
4. Compare the launcher icon and themed monochrome icon on a supported launcher. About should show the same mark and wordmark.

## Local network behaviour

Pairing requests Bluetooth permissions at point of use. Android 11 requires location permission for BLE scanning. Android 12+ separates scan, advertise and connect permissions. Reading the connected Wi-Fi interface uses ACCESS_NETWORK_STATE. The Nearby Wi-Fi Devices runtime permission is requested for local-only hotspot creation on Android 13+. Network and notification features will have version-specific rationales when implemented.

For host testing in milestone C, turn mobile data off, turn on the phone hotspot, join from two clients, and open the app on all three phones. Verify host-to-client and client-to-host communication, then disconnect and rejoin one client. Manufacturer-specific discovery, numeric verification, and isolation behaviour need physical testing.

See [architecture and physical test scenarios](ARCHITECTURE.md), [design specification](DESIGN.md), and the [bundled font licence](core/design/OFL.txt).


If Windows reports “Could not find or load main class” followed by part of a PATH entry when launching Gradle Test Executor, check PATH for stray quotation marks. In PowerShell, run `$env:Path = $env:Path.Replace('"', '')` for the current shell, then rerun the test command with `--no-daemon`.


## Comparing a direct link on two phones

For a controlled comparison today, run the Diagnostics benchmark on your router, then on a phone-hosted hotspot with the same sender, receiver, screen state and test size. Record the frequency and link speed from each report. A 2.4 GHz connection can be congested. Try the hotspot's 5 GHz setting if both phones support it. An ordinary Wi-Fi client cannot always identify a phone hotspot, so label that run when saving the report.

In Settings → Network and hotspot, enable **Always ask** on both paired phones. In Nearby on the receiving phone tap **Allow next direct link**. On the other phone tap **Try direct link** beside the discovered phone. Keep Nearby open during negotiation and approve Android's network picker. Start a transfer within two minutes. The app first tries a 5 GHz Wi-Fi Direct group, then a local-only hotspot. The guest joins as a Wi-Fi station. If the phone rejects the request, continue on your shared network. Auto accepts a verified peer's upgrade request while Nearby is open. Off is the initial setting.

The secure Bluetooth session authenticates fresh ephemeral keys using the existing identity pins. Only mutually recognized identities may exchange temporary Wi-Fi credentials. File requests use sockets bound to the selected network or group owner's interface address and still require pinned mutual TLS. Android controls the channel and restores its own Wi-Fi selection after the app releases its network request. Wi-Fi Aware remains a research candidate.
