# Engineering decisions

- Prefer Wi-Fi Direct, then an app-owned local hotspot, then the existing LAN path for Turbo trials. This supersedes the initial hotspot-first research proposal.
- Keep Turbo opt-in until repeated physical comparisons demonstrate a benefit. Count network setup time, interruptions and chat receipt latency alongside file throughput.
- Keep existing Keystore identities, verification and recipient consent across medium changes. Pairing alone does not authorize an incoming file.
