package com.kicknext.pos_printers.domain

data class UdpNetworkConfig(
    val targetMacAddress: String,
    val ipAddress: String,
    val mask: String,
    val gateway: String,
    val dhcp: Boolean,
) {
    init {
        require(targetMacAddress.isNotBlank()) { "Target MAC address is required" }
    }

    val targetMacBytes: ByteArray = parseMacAddress(targetMacAddress)
    val ipBytes: ByteArray = parseIpAddress(ipAddress)
    val maskBytes: ByteArray = parseIpAddress(mask)
    val gatewayBytes: ByteArray = parseIpAddress(gateway)

    companion object {
        fun parseMacAddress(value: String): ByteArray {
            val clean = value.replace(":", "").replace("-", "")
            require(clean.length == 12) { "Invalid MAC address length" }
            return try {
                (0 until clean.length step 2)
                    .map { index -> clean.substring(index, index + 2).toInt(16).toByte() }
                    .toByteArray()
            } catch (error: NumberFormatException) {
                throw IllegalArgumentException("Invalid MAC address format: $value", error)
            }
        }

        fun parseIpAddress(value: String): ByteArray {
            val parts = value.split('.')
            require(parts.size == 4) { "Invalid IP address format: $value" }
            return try {
                parts.map { part ->
                    val number = part.toInt()
                    require(number in 0..255) { "Invalid IP address format: $value" }
                    number.toByte()
                }.toByteArray()
            } catch (error: NumberFormatException) {
                throw IllegalArgumentException("Invalid IP address format: $value", error)
            }
        }
    }
}
