package com.kicknext.pos_printers.network

import android.util.Log
import com.kicknext.pos_printers.ExtendPosUdpNet
import com.kicknext.pos_printers.gen.NetworkParams
import com.kicknext.pos_printers.validation.ParameterValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles UDP network configuration for printers with proper error handling
 */
class UdpNetworkManager {
    
    companion object {
        private const val TAG = "UdpNetworkManager"
        private const val UDP_TIMEOUT_MS = 10000L
    }
    
    private val posUdpNet = ExtendPosUdpNet()
    
    /**
     * Configures printer network settings via UDP broadcast
     */
    suspend fun configureNetworkViaUdp(netSettings: NetworkParams) = withContext(Dispatchers.IO) {
        // Validate parameters
        ParameterValidator.validateMacConfiguration(
            macAddress = netSettings.macAddress ?: throw IllegalArgumentException("MAC address is required"),
            ipAddress = netSettings.ipAddress,
            mask = netSettings.mask ?: throw IllegalArgumentException("Subnet mask is required"),
            gateway = netSettings.gateway ?: throw IllegalArgumentException("Gateway is required")
        )
        
        val dhcp = netSettings.dhcp ?: throw IllegalArgumentException("DHCP setting is required")
        
        Log.d(TAG, "Configuring network via UDP: IP=${netSettings.ipAddress}, " +
                "Mask=${netSettings.mask}, Gateway=${netSettings.gateway}, DHCP=$dhcp")
        
        val macBytes = parseMacAddress(netSettings.macAddress!!)
        val ipBytes = parseIpAddress(netSettings.ipAddress)
        val maskBytes = parseIpAddress(netSettings.mask!!)
        val gatewayBytes = parseIpAddress(netSettings.gateway!!)
        
        // Execute UDP configuration with timeout
        val success = withTimeoutOrNull(UDP_TIMEOUT_MS) {
            try {
                posUdpNet.udpNetConfig(macBytes, ipBytes, maskBytes, gatewayBytes, dhcp)
                true
            } catch (e: Exception) {
                Log.e(TAG, "UDP network configuration failed", e)
                false
            }
        } ?: false
        
        if (!success) {
            throw Exception("UDP network configuration failed or timed out")
        }
        
        Log.d(TAG, "Network configuration via UDP completed successfully")
    }
    
    /**
     * Parses MAC address string to byte array
     */
    private fun parseMacAddress(macString: String): ByteArray {
        val cleanMac = macString.replace(":", "").replace("-", "")
        require(cleanMac.length == 12) { "Invalid MAC address length" }
        
        return try {
            (0 until cleanMac.length step 2).map { i ->
                cleanMac.substring(i, i + 2).toInt(16).toByte()
            }.toByteArray()
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid MAC address format: $macString", e)
        }
    }
    
    /**
     * Parses IP address string to byte array
     */
    private fun parseIpAddress(ipString: String): ByteArray {
        val parts = ipString.split('.')
        require(parts.size == 4) { "Invalid IP address format: $ipString" }
        
        return try {
            parts.map { it.toInt().toByte() }.toByteArray()
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid IP address format: $ipString", e)
        }
    }
}
