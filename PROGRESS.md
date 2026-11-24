# LanChatShare progress

## Current checkpoint

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

