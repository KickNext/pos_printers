package com.kicknext.pos_printers.discovery

import android.util.Log
import com.kicknext.pos_printers.Utils
import com.kicknext.pos_printers.gen.NetworkParams
import com.kicknext.pos_printers.gen.PosPrinterConnectionType
import com.kicknext.pos_printers.gen.PrinterConnectionParamsDTO
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.net.Inet4Address
import java.net.NetworkInterface

class TcpPrinterDiscovery(
    private val excludeIpSet: Set<String> = emptySet(),
    private val maxConcurrency: Int = 100
) {

    fun discover(
        onPrinterFound: (PrinterConnectionParamsDTO) -> Unit,
        onFinish: () -> Unit,
        port: Int = 9100,
    ) {
        runBlocking {
            val timeoutMs = 1000
            val localNetworks = getLocalIpAddresses()
            val semaphore = Semaphore(maxConcurrency)
            val jobs = mutableListOf<Job>()
            for (network in localNetworks) {
                val range = Utils.getIpRangeFromCidr(network.ipAddress, network.prefixLength) ?: continue
                for (ip in range) {
                    if (ip == network.ipAddress || excludeIpSet.contains(ip)) continue

                    val job = launch(Dispatchers.IO) {
                        semaphore.acquire()
                        try {
                            if (Utils.isPortOpen(ip, port, timeoutMs)) {
                                val printerParams = PrinterConnectionParamsDTO(
                                    id = ip,
                                    connectionType = PosPrinterConnectionType.NETWORK,
                                    usbParams = null,
                                    networkParams = NetworkParams(ipAddress = ip)
                                )
                                Utils.runOnMainThread {
                                    onPrinterFound(printerParams)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            semaphore.release()
                        }
                    }
                    jobs += job
                }
            }

            jobs.joinAll()
            onFinish()
        }
    }

    private fun getLocalIpAddresses(): List<NetworkInfo> {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !"vir|docker".toRegex().containsMatchIn(it.displayName) }
                .flatMap { it.interfaceAddresses.orEmpty() }
                .mapNotNull { addr ->
                    val ip = addr.address
                    if (ip is Inet4Address && addr.networkPrefixLength in 1..31)
                        ip.hostAddress?.let { NetworkInfo(it, addr.networkPrefixLength) }
                    else null
                }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

data class NetworkInfo(val ipAddress: String, val prefixLength: Short)
