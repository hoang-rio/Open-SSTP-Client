package kittoku.osc.service

data class SstpTrafficSnapshot(
    val inBytes: Long,
    val outBytes: Long,
    val diffInBytes: Long,
    val diffOutBytes: Long,
    val intervalMs: Long,
    val timestampMs: Long
) {
    fun inBytesPerSecond(): Long = if (intervalMs > 0L) diffInBytes * 1000L / intervalMs else 0L

    fun outBytesPerSecond(): Long = if (intervalMs > 0L) diffOutBytes * 1000L / intervalMs else 0L

    companion object {
        val EMPTY = SstpTrafficSnapshot(
            inBytes = 0L,
            outBytes = 0L,
            diffInBytes = 0L,
            diffOutBytes = 0L,
            intervalMs = 1000L,
            timestampMs = 0L
        )
    }
}
