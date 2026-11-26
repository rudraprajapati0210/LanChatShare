package dev.lanchatshare.connectivity

/** Optional receiver-side instrumentation for the single-stream two-device benchmark. */
internal class RangeTimings {
    private val totals = linkedMapOf<String, Long>()
    private var lastPayloadEnd: Long? = null
    @Synchronized fun payloadSent() { lastPayloadEnd = System.nanoTime() }
    @Synchronized fun receipt() {
        lastPayloadEnd?.let { record("finalResponseToReceipt",it) }
        lastPayloadEnd = null
    }
    private val calls = linkedMapOf<String, Long>()
    @Synchronized fun snapshot(): Map<String, Long> = totals.mapValues { it.value / 1_000_000 }.mapKeys { it.key + "Ms" } + calls.mapKeys { it.key + "Count" }
    @Synchronized fun record(stage: String, since: Long) {
        calls[stage] = calls.getOrDefault(stage, 0L) + 1
        totals[stage] = totals.getOrDefault(stage, 0L) + System.nanoTime() - since
    }
    @Synchronized fun summary() = totals.entries.joinToString(" ") { (stage, ns) -> "$stage=${ns / 1_000_000}ms" }
}
