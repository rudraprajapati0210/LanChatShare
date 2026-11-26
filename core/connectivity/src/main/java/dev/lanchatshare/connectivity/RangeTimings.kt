package dev.lanchatshare.connectivity

/** Optional receiver-side instrumentation for the single-stream two-device benchmark. */
internal class RangeTimings {
    private val totals = linkedMapOf<String, Long>()
    fun record(stage: String, since: Long) {
        totals[stage] = totals.getOrDefault(stage, 0L) + System.nanoTime() - since
    }
    fun summary() = totals.entries.joinToString(" ") { (stage, ns) -> "$stage=${ns / 1_000_000}ms" }
}
