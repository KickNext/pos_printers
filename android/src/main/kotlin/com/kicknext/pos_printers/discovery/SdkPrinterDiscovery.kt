package com.kicknext.pos_printers.discovery

import com.kicknext.pos_printers.ExtendPosUdpNet
import com.kicknext.pos_printers.Utils
import com.kicknext.pos_printers.gen.NetworkParams
import com.kicknext.pos_printers.gen.PosPrinterConnectionType
import com.kicknext.pos_printers.gen.PrinterConnectionParamsDTO

class SdkPrinterDiscovery {
    companion object {
        /** How long the SDK listens for UDP answers. */
        const val SEARCH_TIMEOUT_MS = 3000

        /** Extra wait so late answers still reach the stream before onFinish. */
        private const val FINISH_MARGIN_MS = 500L
    }

    /**
     * Blocks the calling (background) thread for about SEARCH_TIMEOUT_MS + margin:
     * the SDK method itself returns immediately and answers arrive asynchronously.
     */
    fun discover(
        onPrinterFound: (PrinterConnectionParamsDTO) -> Unit,
        onFinish: () -> Unit
    ) {
        val udpNet = ExtendPosUdpNet()
        try {
            udpNet.searchNetDeviceWithTimeout({ netPrinter ->
                if (netPrinter.isIpV6) return@searchNetDeviceWithTimeout
                val ip = try {
                    netPrinter.ipV4Str
                } catch (_: Exception) {
                    return@searchNetDeviceWithTimeout
                }
                val networkParams = NetworkParams(
                    ipAddress = ip,
                    macAddress = netPrinter.macStr, // Store MAC if available
                    gateway = netPrinter.gatewayStr,
                    mask = netPrinter.maskStr,
                    dhcp = netPrinter.isDhcp,
                    modelName = netPrinter.modelName?.takeIf { it.isNotBlank() },
                )
                val connectionParams = PrinterConnectionParamsDTO(
                    id = ip,
                    connectionType = PosPrinterConnectionType.NETWORK,
                    usbParams = null,
                    networkParams = networkParams,
                )
                Utils.runOnMainThread {
                    onPrinterFound(connectionParams)
                }
            }, SEARCH_TIMEOUT_MS)
            Thread.sleep(SEARCH_TIMEOUT_MS + FINISH_MARGIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {

        } finally {
            try {
                udpNet.closeNetSocket()
            } catch (_: Exception) {
            }
            Utils.runOnMainThread {
                onFinish()
            }
        }
    }
}
