# Two-phone acceptance

Install the same debug APK over the existing app on both phones. Record Android versions, phone models, network setup and display refresh rates. Keep the existing pairing.

- **LAN baseline.** Turn Turbo Off. Send text in each direction. From Diagnostics send 100 MB and 1 GB, then export both phones' JSON. Record band, RSSI, link speed, elapsed time, average MB/s and delivered receipts. Repeat over a phone hotspot and swap hosts.
- **Locked transfers.** Lock the sender, receiver and then both for 5 and 15 minutes during a large transfer. Check progress notification, speed, final size and SHA-256. Pause from the notification, resume in Share and check the saved offset.
- **Hotspot rejoin.** Toggle the host hotspot, leave and rejoin as a client, and switch routers mid-chat. Queue text while disconnected. Check one copy arrives with a receipt and the transfer resumes from the durable offset.
- **Isolated router.** Use guest Wi-Fi with client isolation. Follow Network and hotspot guidance. Confirm signing-key changes still require verification.
- **Direct link.** Set both to Always ask. Open Nearby, allow the next direct link on one phone, then try it from the other. Decline once, retry and accept Android's network prompt. Verify the displayed medium, deliver chat during a file transfer, then check that the temporary network closes after completion. Repeat with Auto, Off, no compatible 5 GHz group, and normal Wi-Fi unavailable. Compare reports from the same phones, direction, size and screen state.
- **Media and Library.** Compare an Original file byte-for-byte with its source. Send a Standard photo and video, inspect the receiver's small preview before acceptance, then cancel or retry. Filter by contact and file type. Open, externally share and delete a completed local copy.
- **Appearance.** Switch mode and palette, set one chat's wallpaper and restart. Check both system and app motion removal, large fonts and TalkBack. Inspect scrolling and simultaneous transfer progress at 60 Hz and 120 Hz where supported.
