package com.kicknext.pos_printers.discovery

import com.kicknext.pos_printers.ExtendPosUdpNet
import com.kicknext.pos_printers.Utils
import com.kicknext.pos_printers.gen.NetworkParams
import com.kicknext.pos_printers.gen.PosPrinterConnectionType
import com.kicknext.pos_printers.gen.PrinterConnectionParamsDTO

class SdkPrinterDiscovery {
    fun discover(
        onPrinterFound: (PrinterConnectionParamsDTO) -> Unit,
        onFinish: () -> Unit
    ) {
        try {
            ExtendPosUdpNet().searchNetDevice { netPrinter ->
                val networkParams = NetworkParams(
                    ipAddress = netPrinter.ipStr,
                    macAddress = netPrinter.macStr, // Store MAC if available
                    gateway = netPrinter.gatewayStr,
                    mask = netPrinter.maskStr,
                    dhcp = netPrinter.isDhcp,
                )
                val connectionParams = PrinterConnectionParamsDTO(
                    id = netPrinter.ipStr,
                    connectionType = PosPrinterConnectionType.NETWORK,
                    usbParams = null,
                    networkParams = networkParams,
                )
                Utils.runOnMainThread {
                    onPrinterFound(connectionParams)
                }
            }
        } catch (_: java.io.IOException) {

        } catch (_: Exception) {

        } finally {
            Utils.runOnMainThread {
                onFinish()
            }
        }
    }
}
