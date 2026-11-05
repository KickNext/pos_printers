package com.kicknext.pos_printers.network

import android.util.Log
import com.kicknext.pos_printers.ExtendPosUdpNet
import com.kicknext.pos_printers.Utils
import com.kicknext.pos_printers.gen.NetworkParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds a single printer by IPv4 through the SDK's UDP search.
 */
class NetworkPrinterLookup {

    companion object {
        private const val TAG = "NetworkPrinterLookup"
        private const val MARGIN_MS = 500L
    }

    /**
     * Returns the printer answering from [ipAddress], or null if nobody did within [timeoutMs].
     */
    suspend fun findByIp(ipAddress: String, timeoutMs: Int): NetworkParams? = withContext(Dispatchers.IO) {
        require(Utils.isValidIpAddress(ipAddress)) { "Invalid IP address format: $ipAddress" }
        require(timeoutMs > 0) { "Timeout must be positive" }

        val found = CompletableDeferred<NetworkParams>()
        val udpNet = ExtendPosUdpNet()
        try {
            udpNet.searchNetDeviceWithTimeout({ device ->
                if (device.isIpV6) return@searchNetDeviceWithTimeout
                try {
                    if (device.ipV4Str != ipAddress) return@searchNetDeviceWithTimeout
                    found.complete(
                        NetworkParams(
                            ipAddress = device.ipV4Str,
                            macAddress = device.macStr,
                            gateway = device.gatewayStr,
                            mask = device.maskStr,
                            dhcp = device.isDhcp,
                            modelName = device.modelName?.takeIf { it.isNotBlank() },
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unreadable UDP answer: ${e.message}")
                }
            }, timeoutMs)
            withTimeoutOrNull(timeoutMs + MARGIN_MS) { found.await() }
        } finally {
            try {
                udpNet.closeNetSocket()
            } catch (_: Exception) {
            }
        }
    }
}
