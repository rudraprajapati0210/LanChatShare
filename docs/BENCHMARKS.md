# Transfer benchmarks

## Reading a Phase 1 export

Use the receiving phone's file average and its matching TCP and TLS memory runs. The report adds `percentOfTCP` and `percentOfTLS` when it finds an earlier sample within two hours with the same peer, direction, byte count, path, setup label and reported frequency. The file must start at offset zero and spend at least 95% of its measured interval in the matching screen state. Ceiling records record the screen at the beginning and end of each stream. Keep it in that state throughout the run. Unknown frequency can match another unknown frequency, so record the hotspot or router band manually if available.

Both phones export the same file transfer ID. In `stages`, each measured operation has cumulative milliseconds and a call count. Sender `sourceRead`, `crc` and `bodyWrite` cover reads, checksum calculation and writes into the HTTP response sink. Receiver `responseHeaders` includes time from requesting a range until response headers arrive. `bodyRead` measures decrypted channel reads. `diskWrite`, `diskSync`, `roomProgress` and `finalSha256` cover the local receive path. `bodyIncludingCrc` overlaps the read and checksum measurements. `finalResponseToReceipt` measures elapsed time from finishing the last checked response until the sender processes the saved-file receipt. An absent stage means it wasn't observed. Progress counters and stage totals describe the current process lifetime. A process restart can truncate them.

TLS providers and HTTP engines buffer and schedule I/O internally. The app cannot separately attribute CPU milliseconds to encryption, decryption or kernel writes using these public interfaces. The export uses null for unavailable cipher times. Compare TCP and TLS memory runs to estimate their combined cost. Compression is disabled in this baseline. [Two-phone measurement procedure](../README.md#measure-a-slow-transfer)

Decimal file sizes, two API 37 emulators and two independently pinned identities on virtual Wi-Fi. Completion includes final SHA-256 verification. The 4 MiB window is experimental.

| Configuration | 10 MB | 30 MB | 100 MB | Chat receipt p95 during 100 MB |
|---|---:|---:|---:|---:|
| Original raw ranges | 21.762 s | 53.640 s | 92.352 s | 183 ms |
| Shipping 256 KiB checked ranges | 18.721 s | 51.032 s | 159.656 s | 316 ms |
| Experimental 4 MiB window | 10.396 s | 27.297 s | 85.455 s | 2,589 ms |

The experimental run also verified 500 MB and 1 GB. Other host work overlapped those runs. Use them as integrity checks only. Physical direct-link throughput remains unmeasured.

## Concurrent range requests on virtual Wi-Fi

Two API 37 emulators transferred the same 100,000,000-byte payload with a 256 KiB request window and concurrent chat probes. Each configuration ran once in the order shown. All three passed end-to-end verification with zero logged retries. MB/s uses decimal megabytes. Receipt p95 uses nearest rank.

| Streams | Elapsed | MB/s | Chat receipts sampled | Chat receipt p95 |
|---:|---:|---:|---:|---:|
| 1 | 257.506 s | 0.388 | 210 | 443 ms |
| 2 | 100.406 s | 0.996 | 90 | 187 ms |
| 4 | 85.873 s | 1.165 | 74 | 313 ms |

In the single-stream run the receiver spent 138.903 s waiting for response headers and 87.226 s reading bodies, including 0.219 s on CRC. Disk sync took 6.180 s and Room progress writes took 5.504 s. Waiting for range responses was the largest measured cost. Four streams finished fastest here, while two gave the lowest chat p95. Production retains one stream pending repeated trials on physical phones. Host load, run order and the virtual radio limit comparisons.

On two paired phones, run Settings → Diagnostics → Transfer benchmark. Select 100 MB and then 1 GB. Export both reports. Repeat three times in each direction on the same router and again on a phone hotspot, recording phone models, OS, host, screen state and radio frequency. Compare median MB/s including retries. Measure direct-network setup separately and include it in end-to-end elapsed time when evaluating Turbo.
