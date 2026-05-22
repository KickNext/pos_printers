package com.kicknext.pos_printers.network

import android.util.Log
import com.kicknext.pos_printers.ExtendPosUdpNet
import com.kicknext.pos_printers.domain.UdpNetworkConfig
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
        
        val config = UdpNetworkConfig(
            targetMacAddress = netSettings.macAddress ?: throw IllegalArgumentException("MAC address is required"),
            ipAddress = netSettings.ipAddress,
            mask = netSettings.mask ?: throw IllegalArgumentException("Subnet mask is required"),
            gateway = netSettings.gateway ?: throw IllegalArgumentException("Gateway is required"),
            dhcp = netSettings.dhcp ?: throw IllegalArgumentException("DHCP setting is required"),
        )
        
        Log.d(TAG, "Configuring network via UDP: IP=${netSettings.ipAddress}, " +
                "Mask=${netSettings.mask}, Gateway=${netSettings.gateway}, DHCP=${config.dhcp}")
        
        // Execute UDP configuration with timeout
        val success = withTimeoutOrNull(UDP_TIMEOUT_MS) {
            try {
                posUdpNet.udpNetConfig(
                    config.targetMacBytes,
                    config.ipBytes,
                    config.maskBytes,
                    config.gatewayBytes,
                    config.dhcp,
                )
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
    
}
