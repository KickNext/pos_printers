package com.kicknext.pos_printers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.izettle.html2bitmap.Html2Bitmap
import com.izettle.html2bitmap.content.WebViewContent
import com.kicknext.pos_printers.gen.ConnectResult
import com.kicknext.pos_printers.gen.NetSettingsDTO
import com.kicknext.pos_printers.gen.POSPrintersApi
import com.kicknext.pos_printers.gen.POSPrintersReceiverApi
import com.kicknext.pos_printers.gen.PosPrinterConnectionType
import com.kicknext.pos_printers.gen.XPrinterDTO
import io.flutter.embedding.engine.plugins.FlutterPlugin
import net.posprinter.IConnectListener
import net.posprinter.IDeviceConnection
import net.posprinter.POSConnect
import net.posprinter.POSConst
import net.posprinter.POSPrinter
import net.posprinter.utils.StringUtils

/** PosPrintersPlugin */
class PosPrintersPlugin : FlutterPlugin, POSPrintersApi {

    private lateinit var context: Context
    private lateinit var receiver: POSPrintersReceiverApi
    private val posUdpNet = ExtendPosUdpNet()
    private var currentConnection: IDeviceConnection? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        POSPrintersApi.setUp(flutterPluginBinding.binaryMessenger, this)
        receiver = POSPrintersReceiverApi(flutterPluginBinding.binaryMessenger)
        context = flutterPluginBinding.applicationContext
        POSConnect.init(this.context)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        POSPrintersApi.setUp(binding.binaryMessenger, null)
    }

    override fun getPrinters(callback: (Result<Boolean>) -> Unit) {
        try {
            val usbPaths = POSConnect.getUsbDevices(context)
            for (path in usbPaths) {
                returnPrinterDTO(
                    XPrinterDTO(
                        connectionType = PosPrinterConnectionType.USB,
                        usbPath = path
                    )
                )
            }
            posUdpNet.searchNetDevice {
                returnPrinterDTO(
                    XPrinterDTO(
                        connectionType = PosPrinterConnectionType.NETWORK,
                        macAddress = it.macStr,
                        ipAddress = it.ipStr,
                        mask = it.maskStr,
                        gateway = it.gatewayStr,
                        dhcp = it.isDhcp,
                    )
                )
            }
            while (posUdpNet.isSearch) {
                Thread.sleep(100)
            }
            callback(Result.success(true))
        } catch (platformError: Throwable) {
            callback(Result.failure(platformError))
        }
    }

    override fun connectPrinter(printer: XPrinterDTO, callback: (Result<ConnectResult>) -> Unit) {
        try {
            currentConnection?.close()
            when (printer.connectionType) {
                PosPrinterConnectionType.USB -> {
                    currentConnection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
                    currentConnection!!.connect(printer.usbPath, connectListener)
                }

                PosPrinterConnectionType.NETWORK -> {
                    currentConnection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
                    currentConnection!!.connect(printer.ipAddress, connectListener)
                }
            }
            callback(Result.success(ConnectResult(true)))
        } catch (platformError: Throwable) {
            callback(Result.failure(platformError))
        }
    }

    override fun getPrinterStatus(printer: XPrinterDTO, callback: (Result<String>) -> Unit) {
        try {
            val curPrinter = POSPrinter(currentConnection)
            curPrinter.printerStatus { status ->
                when (status) {
                    -1 -> {
                        callback(Result.success("Unknown errors"))
                    }
                    -3 -> {
                        callback(Result.success("Connection disconnected"))
                    }
                    -4 -> {
                        callback(Result.success("Receiving data timed out"))
                    }
                    POSConst.STS_NORMAL -> {
                        callback(Result.success("Normal status"))
                    }
                    POSConst.STS_COVEROPEN -> {
                        callback(Result.success("Cover open"))
                    }
                    POSConst.STS_PAPEREMPTY -> {
                        callback(Result.success("Paper empty"))
                    }
                    POSConst.STS_PRESS_FEED -> {
                        callback(Result.success("Press the paper feed button"))
                    }
                    POSConst.STS_PRINTER_ERR -> {
                        callback(Result.success("Printer error"))
                    }
                    else -> {
                        callback(Result.success("Unknown status"))
                    }
                }
            }
        } catch (platformError: Throwable) {
            callback(Result.failure(platformError))
        }
    }

    override fun getPrinterSN(printer: XPrinterDTO, callback: (Result<String>) -> Unit) {
        try {
            val curPrinter = POSPrinter(currentConnection)
            curPrinter.getSerialNumber { sn ->
                callback(Result.success(StringUtils.bytes2String(sn,"gbk")))
            }
        } catch (platformError: Throwable) {
            callback(Result.failure(platformError))
        }
    }

    override fun openCashBox(printer: XPrinterDTO, callback: (Result<String>) -> Unit) {
        try {
            val curPrinter = POSPrinter(currentConnection)
            curPrinter.openCashBox(POSConst.PIN_TWO)
            callback(Result.success("Cash box opened"))
        } catch (platformError: Throwable) {
            callback(Result.failure(platformError))
        }
    }

    override fun printHTML(html: String, width: Long,  callback: (Result<Boolean>) -> Unit) {
        val content = WebViewContent.html(html)
        val bitmap = Html2Bitmap.Builder().setBitmapWidth(width.toInt()).setContent(content).setTextZoom(100).setContext(context).build().bitmap
        val curPrinter = POSPrinter(currentConnection)
        curPrinter.initializePrinter()
            .printBitmap(bitmap, POSConst.ALIGNMENT_LEFT, width.toInt()).cutHalfAndFeed(1)
        callback(Result.success(true))
    }

    override fun setNetSettingsToPrinter(
        printer: XPrinterDTO,
        netSettings: NetSettingsDTO,
        callback: (Result<Boolean>) -> Unit
    ) {
        try {
            val ip = parseData(netSettings.ipAddress)
            val mask = parseData(netSettings.mask)
            val gateway = parseData(netSettings.gateway)
            val dhcp = netSettings.dhcp
            val newPrinterConnection = when (printer.connectionType) {
                PosPrinterConnectionType.USB -> POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
                PosPrinterConnectionType.NETWORK -> POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
            }
            val handler = IConnectListener { code, _, _ ->
                when (code) {
                    POSConnect.CONNECT_SUCCESS -> {
                        val p = POSPrinter(newPrinterConnection)
                        p.setNetAll(ip, mask, gateway, dhcp)
                        Handler(Looper.getMainLooper()).postDelayed({
                            callback(Result.success(true))
                            newPrinterConnection?.close()
                        }, 2000)
                    }
                    POSConnect.CONNECT_FAIL -> {
                        callback(Result.failure(Throwable("Connect failed")))
                    }

                    POSConnect.CONNECT_INTERRUPT -> {
                        callback(Result.failure(Throwable("Connect failed")))
                    }

                    POSConnect.SEND_FAIL -> {
                        callback(Result.failure(Throwable("Set new settings failed")))
                    }

                    else -> callback(Result.failure(Throwable("Unknown error")))
                }
            }
            when (printer.connectionType) {
                PosPrinterConnectionType.USB -> {
                    newPrinterConnection!!.connect(printer.usbPath, handler)
                }
                PosPrinterConnectionType.NETWORK -> {
                    newPrinterConnection!!.connect(printer.ipAddress, handler)
                }
            }
        } catch (platformError: Throwable) {
            callback(Result.failure(platformError))
        }
    }


    private fun containsPrinter(models: MutableList<XPrinterDTO>, macAddress: String): Boolean {
        return models.any { it.macAddress == macAddress }
    }

    private fun parseData(str: String): ByteArray? {
        val arr = str.split(".")
        if (arr.size != 4) {
            return null
        }
        return byteArrayOf(
            arr[0].toInt().toByte(),
            arr[1].toInt().toByte(),
            arr[2].toInt().toByte(),
            arr[3].toInt().toByte()
        )
    }

    private fun returnPrinterDTO(
        printerDTO: XPrinterDTO,
        callback: ((Result<Unit>) -> Unit)? = null
    ) {
        val defaultCallback: (Result<Unit>) -> Unit = { result ->
            result.onSuccess {
                Log.d("POSPrinters", "XPrinterDTO sent to Dart successfully")
            }.onFailure { error ->
                Log.e("POSPrinters", "Error send XPrinterDTO to Dart: $error")
            }
        }
        val effectiveCallback = callback ?: defaultCallback
        Handler(Looper.getMainLooper()).post {
            receiver.newPrinter(printerDTO, effectiveCallback)
        }
    }

    private fun sendStatus(status: ConnectResult, callback: ((Result<Unit>) -> Unit)? = null) {
        val defaultCallback: (Result<Unit>) -> Unit = { result ->
            result.onSuccess {
                Log.d("POSPrinters", "Сообщение успешно отправлено")
            }.onFailure { error ->
                Log.e("POSPrinters", "Ошибка при отправке сообщения: $error")
            }
        }
        val effectiveCallback = callback ?: defaultCallback
        receiver.connectionHandler(status, effectiveCallback)
    }


    private val connectListener = IConnectListener { code, connInfo, msg ->
        when (code) {
            POSConnect.CONNECT_SUCCESS -> {
                println(connInfo)
                println(msg)
                sendStatus(ConnectResult(true, msg))
            }

            POSConnect.CONNECT_FAIL -> {
                println(connInfo)
                println(msg)
                sendStatus(ConnectResult(false, msg))
            }

            POSConnect.CONNECT_INTERRUPT -> {
                println(connInfo)
                println(msg)
                sendStatus(ConnectResult(false, msg))
            }

            POSConnect.SEND_FAIL -> {
                println(connInfo)
                println(msg)
                sendStatus(ConnectResult(false, msg))
            }

            POSConnect.USB_DETACHED -> {
                println(connInfo)
                println(msg)
                sendStatus(ConnectResult(false, msg))
            }

            POSConnect.USB_ATTACHED -> {
                println(connInfo)
                println(msg)
                sendStatus(ConnectResult(true, msg))
            }
        }
    }
}


