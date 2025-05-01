package com.kicknext.pos_printers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.izettle.html2bitmap.Html2Bitmap
import com.izettle.html2bitmap.content.WebViewContent
import com.kicknext.pos_printers.gen.*
import com.kicknext.pos_printers.discovery.UsbPrinterDiscovery
import com.kicknext.pos_printers.discovery.SdkPrinterDiscovery
import com.kicknext.pos_printers.discovery.TcpPrinterDiscovery
import io.flutter.embedding.engine.plugins.FlutterPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import net.posprinter.*
import net.posprinter.model.AlgorithmType
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.milliseconds

/** PosPrintersPlugin */
class PosPrintersPlugin : FlutterPlugin, POSPrintersApi {

    private lateinit var applicationContext: Context
    private val posUdpNet = ExtendPosUdpNet()
    private lateinit var usbManager: UsbManager
    private lateinit var discoveryEventsApi: PrinterDiscoveryEventsApi
    private var connections = mutableMapOf<String, IDeviceConnection?>()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            val isPrinter = Utils.isUsbPrinter(device)
            if (!isPrinter) return
            val serial = Utils.getUsbSerialNumber(device, usbManager)
            val baseDto = PrinterConnectionParamsDTO(
                id = "${device.vendorId}:${device.productId}:${serial ?: "null"}",
                connectionType = PosPrinterConnectionType.USB,
                usbParams = UsbParams(
                    vendorId = device.vendorId.toLong(),
                    productId = device.productId.toLong(),
                    serialNumber = serial,
                    manufacturer = device.manufacturerName,
                    productName = device.productName
                ),
                networkParams = null
            )
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    try {
                        discoveryEventsApi.onPrinterAttached(baseDto) {}
                    } catch (_: Exception) {
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    try {
                        discoveryEventsApi.onPrinterDetached(baseDto) {}
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = flutterPluginBinding.applicationContext
        POSPrintersApi.setUp(flutterPluginBinding.binaryMessenger, this)
        discoveryEventsApi = PrinterDiscoveryEventsApi(flutterPluginBinding.binaryMessenger)
        usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
        POSConnect.init(applicationContext)
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        applicationContext.registerReceiver(usbReceiver, filter)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        POSPrintersApi.setUp(binding.binaryMessenger, null)
        applicationContext.unregisterReceiver(usbReceiver)
        connections.forEach { (_, connection) ->
            connection?.close()
        }
    }

//    private fun detectType(connectionParams: PrinterConnectionParams, callback: (PrinterLanguage?) -> Unit) {
//        val connection: IDeviceConnection
//        val target: String
//        try {
//            when (connectionParams.connectionType) {
//                PosPrinterConnectionType.USB -> {
//                    val usbParams = connectionParams.usbParams
//                        ?: throw IllegalArgumentException("USB params are missing")
//                    val usbDevice = findUsbDevice(
//                        usbParams.vendorId.toInt(),
//                        usbParams.productId.toInt(),
//                        usbParams.usbSerialNumber
//                    ) ?: throw Exception("USB device not found")
//                    connection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
//                    target = usbDevice.deviceName
//                }
//                PosPrinterConnectionType.NETWORK -> {
//                    val networkParams = connectionParams.networkParams
//                        ?: throw IllegalArgumentException("Network params are missing")
//                    connection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
//                    target = networkParams.ipAddress
//                }
//                else -> {
//                    callback(null)
//                    return
//                }
//            }
//        } catch (e: Exception) {
//            callback(null)
//            return
//        }
//        val listener = IConnectListener { code, _, _ ->
//            if (code != POSConnect.CONNECT_SUCCESS) {
//                callback(null)
//                return@IConnectListener
//            }
//            try {
//                val posPrinter = POSPrinter(connection)
//                posPrinter.printerStatus { escStatus ->
//                    if (escStatus >= POSConst.STS_NORMAL) {
//                        callback(PrinterLanguage.ESC)
//                    } else {
//                        try {
//                            val zplPrinter = ZPLPrinter(connection)
//                            zplPrinter.printerStatus { zplCode ->
//                                val type = if (zplCode in 0..0x80) PrinterLanguage.ZPL else null
//                                callback(type)
//                            }
//                        } catch (zplEx: Exception) {
//                            callback(null)
//                        }
//                    }
//                }
//            } catch (ex: Exception) {
//                callback(null)
//            }
//        }
//        try {
//            connection.connect(target, listener)
//        } catch (e: Exception) {
//            callback(null)
//        }
//    }


    override fun configureNetViaUDP(netSettings: NetworkParams, callback: (Result<Unit>) -> Unit) {
        try {
            val macBytes = Utils.parseMacAddress(netSettings.macAddress!!)
            val ipBytes = Utils.parseData(netSettings.ipAddress)
            val maskBytes = Utils.parseData(netSettings.mask!!)
            val gatewayBytes = Utils.parseData(netSettings.gateway!!)
            if (ipBytes == null || maskBytes == null || gatewayBytes == null) {
                return callback(Result.failure(Exception("Invalid IP/Mask/Gateway format")))
            }
            val dhcp = netSettings.dhcp
            posUdpNet.udpNetConfig(macBytes, ipBytes, maskBytes, gatewayBytes, dhcp!!)
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(Exception("Configure net via UDP exception: ${e.message}")))
        }
    }

    override fun printData(
        printer: PrinterConnectionParamsDTO,
        data: ByteArray,
        width: Long,
        callback: (Result<Unit>) -> Unit
    ) {
        try {
            val (connection, target) = preparePrinterConnection(printer)
            handlePrinterConnection(printer, connection, target) {
                try {
                    val curPrinter = POSPrinter(connection)
                    curPrinter.initializePrinter()
                    curPrinter.sendData(data)
                    callback(Result.success(Unit))
                } catch (e: Throwable) {
                    callback(Result.failure(e))
                }
            }
        } catch (e: Throwable) {
            callback(Result.failure(Exception("Print ESC Raw data Exception: ${e.message}")))
        }
    }

    override fun printHTML(
        printer: PrinterConnectionParamsDTO,
        html: String,
        width: Long,
        callback: (Result<Unit>) -> Unit
    ) {
        try {
            val content = WebViewContent.html(html)
            val bitmap =
                Html2Bitmap.Builder().setBitmapWidth(width.toInt()).setContent(content)
                    .setTextZoom(100).setContext(applicationContext).build().bitmap
            val (connection, target) = preparePrinterConnection(printer)
            handlePrinterConnection(printer, connection, target) {
                try {
                    val curPrinter = POSPrinter(connection)
                    curPrinter.initializePrinter()
                    curPrinter.printBitmap(bitmap, POSConst.ALIGNMENT_LEFT, width.toInt())
                    curPrinter.cutHalfAndFeed(1)
                    callback(Result.success(Unit))
                } catch (e: Throwable) {
                    callback(Result.failure(e))
                }
            }
        } catch (e: Throwable) {
            callback(Result.failure(Exception("Print ESC HTML Exception: ${e.message}")))
        }

    }

    override fun openCashBox(
        printer: PrinterConnectionParamsDTO, callback: (Result<Unit>) -> Unit
    ) {
        try {
            val (connection, target) = preparePrinterConnection(printer)
            handlePrinterConnection(printer, connection, target) {
                try {
                    val curPrinter = POSPrinter(connection)
                    curPrinter.initializePrinter()
                    curPrinter.openCashBox(POSConst.PIN_TWO)
                    callback(Result.success(Unit))
                } catch (e: Throwable) {
                    callback(Result.failure(e))
                }
            }
        } catch (e: Throwable) {
            callback(Result.failure(Exception("Open cashbox Exception: ${e.message}")))
        }
    }

    override fun printZplRawData(
        printer: PrinterConnectionParamsDTO,
        labelCommands: ByteArray,
        width: Long,
        callback: (Result<Unit>) -> Unit
    ) {
        try {
            val (connection, target) = preparePrinterConnection(printer)
            handlePrinterConnection(printer, connection, target) {
                try {
                    connection.setSendCallback({})
                    connection.isConnect()
                    val zpl = ZPLPrinter(connection)
                    zpl.sendData(labelCommands)
                    callback(Result.success(Unit))
                } catch (e: Throwable) {
                    callback(Result.failure(e))
                }
            }
        } catch (e: Throwable) {
            callback(Result.failure(Exception("Print ZPL Raw data exception: ${e.message}")))
        }

    }

    override fun printZplHtml(
        printer: PrinterConnectionParamsDTO,
        html: String,
        width: Long,
        callback: (Result<Unit>) -> Unit
    ) {
        try {
            val (connection, target) = preparePrinterConnection(printer)
            handlePrinterConnection(printer, connection, target) {
                try {
                    val content = WebViewContent.html(html)
                    val bmp =
                        Html2Bitmap.Builder().setBitmapWidth(width.toInt()).setStrictMode(true)
                            .setContent(content).setTextZoom(100).setContext(applicationContext)
                            .build().bitmap
                    val zpl = ZPLPrinter(connection)
                    zpl.setPrinterWidth(width.toInt())
                    zpl.addStart()
                    zpl.printBmpCompress(0, 0, bmp, width.toInt(), AlgorithmType.Dithering)
                    zpl.addEnd()
                    callback(Result.success(Unit))
                } catch (e: Throwable) {
                    callback(Result.failure(Exception("Print label HTML exception: ${e.message}")))
                }
            }
        } catch (e: Throwable) {
            callback(Result.failure(Exception("Print ZPL HTML exception: ${e.message}")))
        }
    }

    override fun getZPLPrinterStatus(
        printer: PrinterConnectionParamsDTO, callback: (Result<ZPLStatusResult>) -> Unit
    ) {
        try {
            val (connection, target) = preparePrinterConnection(printer)
            handlePrinterConnection(printer, connection, target) {
                try {
                    val zpl = ZPLPrinter(connection)
                    zpl.printerStatus(500) { code ->
                        val success = code == 0
                        val resultObj = if (success) {
                            ZPLStatusResult(true, code.toLong(), null)
                        } else {
                            ZPLStatusResult(false, code.toLong(), "ZPL status code $code")
                        }
                        callback(Result.success(resultObj))
                    }
                } catch (e: Throwable) {
                    callback(Result.failure(Exception("Get ZPL status exception: ${e.message}")))
                }
            }
        } catch (e: UsbDeviceNotFoundException) {
            callback(
                Result.success(
                    ZPLStatusResult(
                        false,
                        0,
                        "USB device not found"
                    )
                )
            )
        } catch (e: Throwable) {
            callback(Result.failure(Exception("Get ZPL printer status exception: ${e.message}")))
        }
    }

    override fun getPrinterSN(
        printer: PrinterConnectionParamsDTO, callback: (Result<StringResult>) -> Unit
    ) {
        try {
            val (connection, target) = preparePrinterConnection(printer)
            handlePrinterConnection(printer, connection, target) {
                val pos = POSPrinter(connection)
                pos.initializePrinter()
                pos.getSerialNumber { sn ->
                    val snString = try {
                        String(sn, charset("GBK"))
                    } catch (e: Exception) {
                        try {
                            String(sn, Charsets.UTF_8)
                        } catch (e2: Exception) {
                            "Error decoding SN"
                        }
                    }
                    if (snString.isEmpty() || snString == "Error decoding SN") {
                        callback(Result.failure(Exception("Failed to decode serial number: $snString")))
                    } else {
                        callback(Result.success(StringResult(success = true, value = snString)))
                    }
                }
            }
        } catch (e: Throwable) {
            callback(Result.failure(Exception("Get printer SN exception: ${e.message}")))
        }
    }

    override fun startDiscoverAllUsbPrinters(callback: (Result<Unit>) -> Unit) {
        val discovery = UsbPrinterDiscovery(usbManager)
        try {
            discovery.discover(onPrinterFound = { printer ->
                Log.d("PosPrintersPlugin", "USB printer found: $printer")
                discoveryEventsApi.onPrinterFound(printer) {}
            }, onFinish = {
                callback(Result.success(Unit))
            })
        } catch (e: Exception) {
            callback(Result.failure(e))
        } finally {
            Log.d("PosPrintersPlugin", "endDiscoveryUsbPrinters")
        }
    }

    override fun startDiscoveryXprinterSDKNetworkPrinters(callback: (Result<Unit>) -> Unit) {
        val discovery = SdkPrinterDiscovery()
        try {
            discovery.discover(onPrinterFound = { printer ->
                Log.d("PosPrintersPlugin", "SDK printer found: $printer")
                discoveryEventsApi.onPrinterFound(printer) {}
            }, onFinish = {
                callback(Result.success(Unit))
            })
        } catch (e: Exception) {
            callback(Result.failure(e))
        } finally {
            Log.d("PosPrintersPlugin", "endDiscoveryXprinterSDKNetworkPrinters")
        }
    }

    override fun startDiscoveryTCPNetworkPrinters(port: Long, callback: (Result<Unit>) -> Unit) {
        val discovery = TcpPrinterDiscovery()
        try {
            discovery.discover(onPrinterFound = { printer ->
                Log.d("PosPrintersPlugin", "TCP printer found: $printer")
                discoveryEventsApi.onPrinterFound(printer) {}
            }, onFinish = {
                callback(Result.success(Unit))
            })
        } catch (e: Exception) {
            callback(Result.failure(e))
        } finally {
            Log.d("PosPrintersPlugin", "endDiscoveryTCPNetworkPrinters")
        }
    }


    private suspend fun readPrinterResponse(connection: IDeviceConnection): ByteArray =
        suspendCancellableCoroutine { cont ->
            connection.readData { data ->
                cont.resume(data ?: ByteArray(0))
            }
        }

    private fun logHex(prefix: String, data: ByteArray) {
        val hex = data.joinToString(" ") { "%02X".format(it) }
        Log.d("PosPrintersPlugin", "$prefix: $hex")
    }


    override fun checkPrinterLanguage(
        printer: PrinterConnectionParamsDTO,
        callback: (Result<CheckPrinterLanguageResponse>) -> Unit
    ) {
        try {
            val (connection, target) = preparePrinterConnection(printer)

            handlePrinterConnection(printer, connection, target) {   // подключаемся
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val lang = detectPrinterLanguage(connection, 500)

                        callback(
                            Result.success(
                                CheckPrinterLanguageResponse(lang, printer)
                            )
                        )
                    } catch (e: Exception) {
                        callback(
                            Result.failure(
                                Exception(
                                    "Check printer language exception: ${e.message}",
                                    e
                                )
                            )
                        )
                    } finally {
                        runCatching { connection.close() }
                    }
                }
            }
        } catch (e: Throwable) {
            callback(Result.failure(Exception("Check printer language exception: ${e.message}", e)))
        }
    }

    private suspend fun IDeviceConnection.sendAndRead(
        cmd: ByteArray,
        timeoutMs: Long
    ): ByteArray? = suspendCancellableCoroutine { cont ->
        // 1. Cтартуем таймер‐«будильник»
        val timer = CoroutineScope(Dispatchers.IO).launch {
            delay(timeoutMs)
            if (cont.isActive) cont.resume(null)         // ⇒ возврат по таймауту
        }

        // 2. Сначала подписываемся на данные…
        readData { data ->
            if (cont.isActive && data != null && data.isNotEmpty()) {
                timer.cancel()                           // ответ пришёл – тушим таймер
                cont.resume(data)                        // ⇒ возврат с данными
            }
        }

        // 3. …потом шлём команду
        sendData(cmd)

        // 4. Чистим ресурсы, если корутину отменили извне
        cont.invokeOnCancellation { timer.cancel() }
    }


    private suspend fun detectPrinterLanguage(
        conn: IDeviceConnection,
        toMs: Long
    ): PrinterLanguage {

        // 1) ZPL  ~HI ---------------------------------------------------------
        val zpl = conn.sendAndRead("~HI\r\n".toByteArray(Charsets.US_ASCII), toMs)
        if (zpl != null) {
            logHex("⇠ ZPL", zpl); return PrinterLanguage.ZPL
        }

        // 2) ESC/POS GS ( I 3 -----------------------------------------------
        val esc = conn.sendAndRead(byteArrayOf(0x1D, 0x49, 0x43, 0x00), toMs)
        if (esc != null) {
            logHex("⇠ ESC GS(I)", esc); return PrinterLanguage.ESC
        }

        // 3) Xprinter ESC i 01 ----------------------------------------------
        val xp = conn.sendAndRead(byteArrayOf(0x1B, 0x69, 0x01), toMs)
        if (xp != null) {
            logHex("⇠ Xprinter", xp)
            val txt = xp.toString(Charsets.US_ASCII)
            return when {
                txt.contains("ZPL", true) -> PrinterLanguage.ZPL
                txt.contains("ESC", true) -> PrinterLanguage.ESC
                else -> PrinterLanguage.ESC
            }
        }

        return PrinterLanguage.ESC
    }

    private fun handlePrinterConnection(
        printer: PrinterConnectionParamsDTO,
        connection: IDeviceConnection,
        target: String,
        onSuccess: () -> Unit
    ) {
        connection.connect(target) { code, connectionInfo, message ->
            Log.d("PosPrintersPlugin", "handlePrinterConnection: $code, $connectionInfo, $message")
            try {
                when (code) {
                    POSConnect.CONNECT_SUCCESS -> {
                        discoveryEventsApi.onPrinterAttached(printer) {}
                        onSuccess()
                    }

                    POSConnect.USB_ATTACHED -> {}
                    else -> {
                        discoveryEventsApi.onPrinterDetached(printer) {}
                    }
                }
            } catch (e: Throwable) {
                discoveryEventsApi.onPrinterDetached(printer) {}
            }
        }
    }

    override fun getPrinterStatus(
        printer: PrinterConnectionParamsDTO,
        callback: (Result<StatusResult>) -> Unit
    ) {
        try {
            val (connection, target) = preparePrinterConnection(printer)
            handlePrinterConnection(printer, connection, target) {
                val pos = POSPrinter(connection)
                pos.printerStatus { status ->
                    val text = Utils.mapStatusCodeToString(status)
                    callback(Result.success(StatusResult(true, text, text)))
                }
            }
        } catch (e: UsbDeviceNotFoundException) {
            callback(
                Result.success(
                    StatusResult(
                        false,
                        "USB device not found",
                        "USB device not found"
                    )
                )
            )
        } catch (e: Throwable) {
            callback(Result.failure(Exception("Get printer status exception: ${e.message}")))
        }
    }

    override fun setNetSettingsToPrinter(
        printer: PrinterConnectionParamsDTO,
        netSettings: NetworkParams,
        callback: (Result<Unit>) -> Unit
    ) {
        try {
            val ip = Utils.parseData(netSettings.ipAddress)
            val mask = Utils.parseData(netSettings.mask!!)
            val gateway = Utils.parseData(netSettings.gateway!!)
            if (ip == null || mask == null || gateway == null) {
                callback(Result.failure(Exception("Invalid IP/Mask/Gateway format")))
                return
            }
            val dhcp = netSettings.dhcp
            val newPrinterConnection: IDeviceConnection
            val connectionTargetInfo: String
            when (printer.connectionType) {
                PosPrinterConnectionType.USB -> {
                    val usbDevice = getValidatedUsbDevice(printer.usbParams!!)
                    newPrinterConnection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
                    connectionTargetInfo = usbDevice.deviceName
                }

                PosPrinterConnectionType.NETWORK -> {
                    if (printer.networkParams == null) {
                        callback(Result.failure(Exception("Missing ipAddress for Network connection.")))
                        return
                    }
                    newPrinterConnection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
                    connectionTargetInfo = printer.networkParams.ipAddress
                }
            }
            var replySubmitted = false
            val handler = IConnectListener { code, connInfo, msg ->
                synchronized(this) {
                    if (replySubmitted) return@synchronized
                    when (code) {
                        POSConnect.CONNECT_SUCCESS -> {
                            try {
                                val p = POSPrinter(newPrinterConnection)
                                p.setNetAll(ip, mask, gateway, dhcp!!)
                                // Ждём 1 секунду перед закрытием соединения и возвратом результата
                                Thread {
                                    Thread.sleep(1000)
                                    if (!replySubmitted) {
                                        callback(Result.success(Unit))
                                        replySubmitted = true
                                    }
                                    newPrinterConnection.close()
                                }.start()
                            } catch (e: Exception) {
                                if (!replySubmitted) {
                                    callback(Result.failure(Exception("Error applying net settings: ${e.message}")))
                                    replySubmitted = true
                                }
                                newPrinterConnection.close()
                            }
                        }

                        POSConnect.CONNECT_FAIL, POSConnect.CONNECT_INTERRUPT, POSConnect.SEND_FAIL -> {
                            if (!replySubmitted) {
                                val errorMsg = when (code) {
                                    POSConnect.CONNECT_FAIL -> "Connection failed during net settings: $msg"
                                    POSConnect.CONNECT_INTERRUPT -> "Connection interrupted during net settings: $msg"
                                    POSConnect.SEND_FAIL -> "Failed to send net settings command: $msg"
                                    else -> "Unknown connection error ($code) during net settings: $msg"
                                }
                                callback(Result.failure(Exception(errorMsg)))
                                replySubmitted = true
                            }
                        }

                        else -> {
                            if (!replySubmitted) {
                                callback(Result.failure(Exception("Unknown status ($code) during net settings: $msg")))
                                replySubmitted = true
                            }
                        }
                    }
                }
            }
            newPrinterConnection.connect(connectionTargetInfo, handler)
        } catch (platformError: Throwable) {
            callback(Result.failure(Exception("Set net settings exception: ${platformError.message}")))
        }
    }

    private fun findUsbDevice(vendorId: Int, productId: Int, serialNumber: String?): UsbDevice? {
        val devices = usbManager.deviceList.values
        return devices.find { device ->
            val vidMatch = device.vendorId == vendorId
            val pidMatch = device.productId == productId
            val serialMatch = if (serialNumber == null) {
                true
            } else {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {

                        if (usbManager.hasPermission(device)) {
                            device.serialNumber == serialNumber
                        } else {
                            false
                        }
                    } catch (e: SecurityException) {
                        false
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    true
                }
            }
            vidMatch && pidMatch && serialMatch
        }
    }

    private fun preparePrinterConnection(printer: PrinterConnectionParamsDTO): Pair<IDeviceConnection, String> {
        val id: String
        val device: IDeviceConnection
        val target: String

        when (printer.connectionType) {
            PosPrinterConnectionType.USB -> {
                val usbParams = printer.usbParams!!
                id =
                    "usb:${usbParams.vendorId}:${usbParams.productId}:${usbParams.serialNumber ?: "null"}"
                val usb = getValidatedUsbDevice(usbParams)
                device = connections.getOrPut(id) {
                    POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
                }!!
                target = usb.deviceName
            }

            PosPrinterConnectionType.NETWORK -> {
                val ip = printer.networkParams!!.ipAddress
                id = "net:$ip"
                device = connections.getOrPut(id) {
                    POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
                }!!
                target = ip
            }
        }

        return Pair(device, target)
    }


    private fun getValidatedUsbDevice(params: UsbParams): UsbDevice {
        val device = findUsbDevice(
            params.vendorId.toInt(), params.productId.toInt(), params.serialNumber
        )
            ?: throw UsbDeviceNotFoundException(
                params.vendorId,
                params.productId,
                params.serialNumber
            )
        if (!usbManager.hasPermission(device)) {
            throw SecurityException("USB permission denied for device ${device.deviceName}")
        }
        return device
    }

    class UsbDeviceNotFoundException(
        val vendorId: Long,
        val productId: Long,
        val serialNumber: String?
    ) : Exception("USB device not found (VID=$vendorId, PID=$productId, SERIAL=${serialNumber ?: "null"})")

}
