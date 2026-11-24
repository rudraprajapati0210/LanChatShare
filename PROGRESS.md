# LanChatShare progress

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
