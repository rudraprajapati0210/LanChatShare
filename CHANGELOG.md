# Changelog

## Unreleased

## v0.1.0 – first public prerelease

This is the first repository release tag. The existing debug APK reports Android version name `0.3.0` (version code 3). The tag preserves the current build and its application version. Install the same APK on both phones.

- Pair locally over Bluetooth and use identity-pinned TLS for queued chat, receipts and resumable file downloads.
- Receive conversation notifications and run active transfers in a foreground service.
- Measure file throughput against generated TCP and pinned-TLS traffic on paired phones in debug builds.

- Recover sessions after Wi-Fi changes and hotspot rejoin, with bounded reconnect delays.
- Send original or resized photos and videos and show small inline attachment previews.
- Browse a paged file Library with search, category, direction, contact and date filters, sorting, preview, sharing and local cleanup.
- Added persisted colour palettes, AMOLED, dynamic colour, per-conversation appearance, typing status and reduced motion.

- Added an opt-in direct-link trial for paired phones. Wi-Fi Direct group creation falls back to a local-only hotspot. The default remains Off pending measurements on physical phones.
- Added signal strength to benchmark reports and bounded message batching on the existing chat connection.
- Fixed subnet matching on Android 11 and 12 and declared the connectivity library's network-state permission.
