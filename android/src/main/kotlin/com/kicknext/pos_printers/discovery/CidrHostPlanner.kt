package com.kicknext.pos_printers.discovery

import java.net.InetAddress

object CidrHostPlanner {
    fun hosts(
        ipAddress: String,
        prefixLength: Short,
        exclude: Set<String>,
        maxHosts: Int,
    ): Sequence<String> {
        require(prefixLength in 1..31) { "Prefix length must be between 1 and 31" }
        require(maxHosts > 0) { "Max hosts must be positive" }

        val ipBytes = InetAddress.getByName(ipAddress).address
        if (ipBytes.size != 4) {
            return emptySequence()
        }

        val ipLong = ipBytes.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        val maskLong = (0xFFFFFFFFL shl (32 - prefixLength.toInt())) and IPV4_MASK
        val networkLong = ipLong and maskLong
        val broadcastLong = networkLong or (maskLong xor IPV4_MASK)
        val startIp = networkLong + 1
        val endIp = broadcastLong - 1

        if (startIp > endIp) {
            return emptySequence()
        }

        return sequence {
            var emitted = 0
            val localSegmentStart = maxOf(startIp, (ipLong and LOCAL_SEGMENT_MASK) + 1)
            val localSegmentEnd = minOf(endIp, (ipLong or LOCAL_SEGMENT_HOST_MASK) - 1)

            for (candidate in localSegmentStart..localSegmentEnd) {
                if (emitted >= maxHosts) {
                    break
                }
                val host = longToAddress(candidate)
                if (host == ipAddress || exclude.contains(host)) {
                    continue
                }
                yield(host)
                emitted++
            }
        }
    }

    private fun longToAddress(value: Long): String {
        val bytes = byteArrayOf(
            (value shr 24 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
        return InetAddress.getByAddress(bytes).hostAddress ?: ""
    }

    private const val IPV4_MASK = 0xFFFFFFFFL
    private const val LOCAL_SEGMENT_MASK = 0xFFFFFF00L
    private const val LOCAL_SEGMENT_HOST_MASK = 0xFFL
}
