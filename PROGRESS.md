# LanChatShare progress

## Background reliability phase

Bug 4 reproduced on API 37 with notifications granted. The baseline saved the incoming text but posted only the connection notification. The updated build posts MessagingStyle alerts and the Mark as read regression passes. Room v5 treats existing messages as read on upgrade. Notification controls, inline reply, deep links, file decisions and transfer progress actions are in place. Next is screen-off transfer scheduling and power management.

## Current checkpoint — Share and inline photos

The user confirmed physical verification of chat and Wi-Fi/hotspot operation. Milestone E adds auto-scroll, photo sharing in conversations, inline previews, a multi-recipient Share hub and a transfer journal. Room v4 preserves existing messages and peers. Consent and receipts travel over chat. File contents use authenticated HTTPS ranges and SHA-256 verification. The sender stages one immutable copy across recipients.

The Android transport regression checks refusal before acceptance, resuming a partial download and byte-for-byte delivery. The chat UI regression checks an incoming message while scrolled up. The physical transfer checklist is in README.md. Library categorization is next.

The debug APK builds. All 13 JVM tests and six targeted API 37 Android tests passed. The transfer test rejects corrupted partial content before a successful retry. The UI test checks a decoded photo preview, a real WorkManager retry and Android Share-sheet input. Android Lint reports zero errors and three existing warnings.

## Previous checkpoint — one-to-one chat

The debug app sends durable messages and delivery receipts over mutually authenticated Netty/CIO WebSockets. Updating both phones preserves the Bluetooth identity and existing Room pins. Weekly TLS keys have a separate purpose from pairing keys. Nearby can revoke a peer, end its sessions and remove queued outgoing messages while retaining delivered history.

The initial audit built successfully and passed nine existing JVM tests and the previous two-identity Android test. The completed binding adds four JVM tests for rotation, unknown identity, tampering and time validity. Android coverage exercises queued text, a reply, both receipts, rejection before WebSocket upgrade, onboarding and conversation navigation. The transport test uses two identities and independent databases on a single API 37 emulator with Android Keystore transport keys at both ends. All 13 JVM and seven Android tests passed. Android Lint finished with zero errors and three warnings (an unused trust manager in the Bouncy Castle dependency, launcher resource configuration and wordmark path length).

Next checkpoint is physical Wi-Fi and hotspot reliability. Run the two-phone README checklist on a shared router and with each phone as host, then repeat with a second client leaving and rejoining. Share transfers, Library categorization and release hardening remain open.

## Previous Bluetooth checkpoint

Milestone B implementation and automated checks are complete. Two-phone radio acceptance is pending. Milestones C through F remain unstarted and G is partially implemented. Stop here before Wi-Fi transport.

The debug APK builds. Seven pairing JVM tests and two profile JVM tests pass. Four Android connectivity tests pass, including a real Keystore signature and Room v1-to-v2 migration. The existing onboarding UI test passed during the audit. On the API 37 emulator, Nearby displayed the runtime permission prompt, began discovery, showed the Bluetooth-off action, and resumed discovery after Bluetooth was re-enabled. Android Lint reports zero errors and two resource warnings (an obsolete launcher resource directory and the wordmark path length).

Build, permission details and the two-phone checklist are in README.md. Device-to-device BLE reachability, OEM bonding prompts and end-to-end user confirmation still need physical validation. Endpoint hints are authenticated and expire with the handoff token. The planned port is 47831. Socket reachability belongs to milestone C.

## Recovery checkpoint

The workspace had no Git metadata. Commit `f59d371` preserves the existing source. The Gradle debug build and lint completed. Two profile tests, five protocol tests and three Android identity/storage tests passed. Audit lint found five warnings. No compile errors or explicit TODO markers were present.

| Milestone | State at recovery |
|---|---|
| A · Setup, design system and first-run profile | Done |
| B · Bluetooth discovery, pairing and trust | Partly done. Signed Wi-Fi handoff and cancellation/permission recovery outstanding. |
| C · Wi-Fi sessions, hotspot and discovery | Not started |
| D · Chat core | Not started. Chats opens its empty state. |
| E · Transfer and Share | Not started. Share opens its placeholder. |
| F · Library | Not started. Library opens its empty state. |
| G · Hardening, polish and tests | Partly done. StrictMode, lint and scoped tests exist. |

The two-phone acceptance checklist lives in README.md. Physical radio testing requires two Android devices.
Signed v1 hellos now include the calculated certificate public-key fingerprint and optional Wi-Fi route hints. IPv4/IPv6 hints and the one-use token are retained together in memory. No shared Wi-Fi is required for pairing.
The comparison prompt and socket now share one pairing deadline. Nearby rechecks permissions after session state changes and catches permission loss during scanning and Bluetooth enable requests.
Protocol tests now check rejected signing keys, malformed address and port hints, IPv4 handoff, and one-use token direction. Seven protocol JVM tests passed.
Both peers now exchange the self-signed certificate and its SHA-256 fingerprint inside the signed hello. Verification checks that the certificate contains the key that signed the transcript. Room schema v2 preserves existing SPKI pins.

