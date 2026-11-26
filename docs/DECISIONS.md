# Engineering decisions

- On API 37, the platform TLS 1.3 RSA-PSS signing path attempted a raw RSA operation that the signing-only Keystore key rejected. The routed TLS client now uses a P-256 signing key certified under the same pinned identity. The existing CIO path retains its RSA credential. Both transport keys rotate together. The Android transport test exercises direct TLS, rejection of another identity, legacy LAN chat and resume after reconnect.

- Prefer Wi-Fi Direct, then an app-owned local hotspot, then the existing LAN path for Turbo trials. This supersedes the initial hotspot-first research proposal.
- Keep Turbo opt-in until repeated physical comparisons demonstrate a benefit. Count network setup time, interruptions and chat receipt latency alongside file throughput.
- Keep existing Keystore identities, verification and recipient consent across medium changes. Pairing alone does not authorize an incoming file.
- Network callbacks trigger immediate rediscovery. The slow interface check also covers downstream hotspots that Android does not expose as a Network. Reconnect delays reset on success or a network change and cap at 32 seconds.
- Original media is the initial choice. Standard produces a JPEG with a 1600-pixel longest edge or an H.264/AAC MP4 fitting inside 1280 × 720. A bounded JPEG preview can accompany the authenticated offer before the recipient accepts the full file.
