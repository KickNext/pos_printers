package com.kicknext.pos_printers.discovery

import android.hardware.usb.UsbManager
import com.kicknext.pos_printers.Utils
import com.kicknext.pos_printers.gen.PosPrinterConnectionType
import com.kicknext.pos_printers.gen.PrinterConnectionParamsDTO
import com.kicknext.pos_printers.gen.UsbParams

class UsbPrinterDiscovery(private val usbManager: UsbManager) {
    fun discover(
        onPrinterFound: (PrinterConnectionParamsDTO) -> Unit,
        onFinish: () -> Unit
    ) {
        try {
            val usbDevices = usbManager.deviceList
            usbDevices.values.forEach { device ->
                if (!Utils.isUsbPrinter(device)) return@forEach
                val serial = Utils.getUsbSerialNumber(device, usbManager)
                val id = "${device.vendorId}:${device.productId}:${serial ?: "null"}"
                val connectionParams = PrinterConnectionParamsDTO(
                    id = id,
                    connectionType = PosPrinterConnectionType.USB,
                    usbParams = UsbParams(
                        vendorId = device.vendorId.toLong(),
                        productId = device.productId.toLong(),
                        serialNumber = serial,
                        manufacturer = device.manufacturerName,
                        productName = device.productName,
                    ),
                    networkParams = null,
                )
                Utils.runOnMainThread {
                    onPrinterFound(connectionParams)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            Utils.runOnMainThread {
                onFinish()
            }
        }
    }
}
