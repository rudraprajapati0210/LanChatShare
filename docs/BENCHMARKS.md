# Transfer benchmarks

Decimal file sizes, two API 37 emulators and two independently pinned identities on virtual Wi-Fi. Completion includes final SHA-256 verification. The 4 MiB window is experimental.

| Configuration | 10 MB | 30 MB | 100 MB | Chat receipt p95 during 100 MB |
|---|---:|---:|---:|---:|
| Original raw ranges | 21.762 s | 53.640 s | 92.352 s | 183 ms |
| Shipping 256 KiB checked ranges | 18.721 s | 51.032 s | 159.656 s | 316 ms |
| Experimental 4 MiB window | 10.396 s | 27.297 s | 85.455 s | 2,589 ms |

The experimental run also verified 500 MB and 1 GB. Other host work overlapped those runs. Use them as integrity checks only. Physical direct-link throughput remains unmeasured.

On two paired phones, run Settings → Diagnostics → Transfer benchmark. Select 100 MB and then 1 GB. Export both reports. Repeat three times in each direction on the same router and again on a phone hotspot, recording phone models, OS, host, screen state and radio frequency. Compare median MB/s including retries. Measure direct-network setup separately and include it in end-to-end elapsed time when evaluating Turbo.
