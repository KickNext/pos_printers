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

        val ipInt = ipBytes.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
        val maskInt = -1 shl (32 - prefixLength)
        val networkInt = ipInt and maskInt
        val broadcastInt = networkInt or maskInt.inv()
        val startIp = networkInt + 1
        val endIp = broadcastInt - 1

        if (startIp > endIp) {
            return emptySequence()
        }

        return sequence {
            var emitted = 0
            for (candidate in startIp..endIp) {
                if (emitted >= maxHosts) {
                    break
                }
                val host = intToAddress(candidate)
                if (host == ipAddress || exclude.contains(host)) {
                    continue
                }
                yield(host)
                emitted++
            }
        }
    }

    private fun intToAddress(value: Int): String {
        val bytes = byteArrayOf(
            (value shr 24 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
        return InetAddress.getByAddress(bytes).hostAddress ?: ""
    }
}
