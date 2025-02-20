package com.kicknext.pos_printers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.izettle.html2bitmap.Html2Bitmap
import com.izettle.html2bitmap.content.WebViewContent
import com.kicknext.pos_printers.gen.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import net.posprinter.*
import net.posprinter.model.AlgorithmType

/** PosPrintersPlugin */
class PosPrintersPlugin : FlutterPlugin, POSPrintersApi {

    private lateinit var context: Context
    private lateinit var receiver: POSPrintersReceiverApi
    private val posUdpNet = ExtendPosUdpNet()

    // Вместо одного currentConnection храним Map
    private val connectionsMap = mutableMapOf<String, IDeviceConnection>()

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        POSPrintersApi.setUp(flutterPluginBinding.binaryMessenger, this)
        receiver = POSPrintersReceiverApi(flutterPluginBinding.binaryMessenger)
        context = flutterPluginBinding.applicationContext
        POSConnect.init(this.context)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        POSPrintersApi.setUp(binding.binaryMessenger, null)
        // Закрывать соединения при необходимости
        // connectionsMap.values.forEach { it.close() }
        // connectionsMap.clear()
    }

    // =========================== Метод поиска ===========================
    override fun getPrinters(callback: (Result<Boolean>) -> Unit) {
        try {
            val usbPaths = POSConnect.getUsbDevices(context)
            for (path in usbPaths) {

                returnPrinterDTO(
                    PrinterConnectionParams(
                        connectionType = PosPrinterConnectionType.USB,
                        usbPath = path
                    )
                )
            }
            posUdpNet.searchNetDevice {
                returnPrinterDTO(
                    PrinterConnectionParams(
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

    private fun getConnectionKey(printer: PrinterConnectionParams): String {
        return when (printer.connectionType) {
            PosPrinterConnectionType.USB -> "usb:${printer.usbPath}"
            PosPrinterConnectionType.NETWORK -> "net:${printer.ipAddress}"
        }
    }

    // =========================== Подключение ===========================
    override fun connectPrinter(printer: PrinterConnectionParams, callback: (Result<ConnectResult>) -> Unit) {
        try {
            val key = getConnectionKey(printer)
            // Закрываем предыдущее соединение
            connectionsMap[key]?.close()
            connectionsMap.remove(key)

            val newConnection = when (printer.connectionType) {
                PosPrinterConnectionType.USB -> POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
                PosPrinterConnectionType.NETWORK -> POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
            }
            val info = when (printer.connectionType) {
                PosPrinterConnectionType.USB -> printer.usbPath
                PosPrinterConnectionType.NETWORK -> printer.ipAddress
            }
            newConnection.connect(info, connectListener)
            connectionsMap[key] = newConnection
            callback(Result.success(ConnectResult(true)))
        } catch (platformError: Throwable) {
            callback(Result.failure(platformError))
        }
    }

    override fun disconnectPrinter(
        printer: PrinterConnectionParams,
        callback: (Result<Boolean>) -> Unit
    ) {
        try {
            val key = getConnectionKey(printer)
            val connection = connectionsMap[key]
            if (connection != null) {
                connection.close()
                connectionsMap.remove(key)
                callback(Result.success(true))
            } else {
                callback(Result.failure(Throwable("No active connection for key=$key")))
            }
        } catch (platformError: Throwable) {
            callback(Result.failure(platformError))
        }
    }

    // ====================== Методы ESC/POS ======================
    override fun printData(
        printer: PrinterConnectionParams,
        data: ByteArray,
        width: Long,
        callback: (Result<Boolean>) -> Unit
    ) {
        try {
            val key = getConnectionKey(printer)
            val connection = connectionsMap[key]
                ?: return callback(Result.failure(Throwable("No active connection for key=$key")))

            val curPrinter = POSPrinter(connection)
            curPrinter.initializePrinter()
            curPrinter.sendData(data)
            callback(Result.success(true))
        } catch (platformError: Throwable) {
            callback(Result.failure(platformError))
        }
    }

    override fun printHTML(
        printer: PrinterConnectionParams,
        html: String,
        width: Long,
        callback: (Result<Boolean>) -> Unit
    ) {
        try {
            val key = getConnectionKey(printer)
            val connection = connectionsMap[key]
                ?: return callback(Result.failure(Throwable("No active connection for key=$key")))

            val content = WebViewContent.html(html)
            val bitmap = Html2Bitmap.Builder()
                .setBitmapWidth(width.toInt())
                .setContent(content)
                .setTextZoom(100)
                .setContext(context)
                .build()
                .bitmap

            val curPrinter = POSPrinter(connection)
            curPrinter.initializePrinter()
                .printBitmap(bitmap, POSConst.ALIGNMENT_LEFT, width.toInt())
                .cutHalfAndFeed(1)

            callback(Result.success(true))
        } catch (platformError: Throwable) {
            callback(Result.failure(platformError))
        }
    }

    override fun openCashBox(printer: PrinterConnectionParams, callback: (Result<String>) -> Unit) {
        try {
            val key = getConnectionKey(printer)
            val connection = connectionsMap[key]
                ?: return callback(Result.failure(Throwable("No active connection for key=$key")))

            val curPrinter = POSPrinter(connection)
            curPrinter.openCashBox(POSConst.PIN_TWO)
            callback(Result.success("Cash box opened"))
        } catch (platformError: Throwable) {
            callback(Result.failure(platformError))
        }
    }

    // ====================== Методы общие ======================
    override fun getPrinterStatus(printer: PrinterConnectionParams, callback: (Result<String>) -> Unit) {
        try {
            val key = getConnectionKey(printer)
            val connection = connectionsMap[key]
                ?: return callback(Result.failure(Throwable("No active connection for key=$key")))

            // Для ESC/POS мы делаем POSPrinter(connection).printerStatus(...)
            // Для лейбл-принтеров (CPCL/TSPL/ZPL) тоже есть printerStatus, но код возврата другой.
            // Если вы хотите унифицировать, можно оставить тот же подход:
            val pos = POSPrinter(connection)
            pos.printerStatus { status ->
                // Мапим коды
                val text = when (status) {
                    -1 -> "Unknown errors"
                    -3 -> "Connection disconnected"
                    -4 -> "Receiving data timed out"
                    POSConst.STS_NORMAL -> "Normal status"
                    POSConst.STS_COVEROPEN -> "Cover open"
                    POSConst.STS_PAPEREMPTY -> "Paper empty"
                    POSConst.STS_PRESS_FEED -> "Press the paper feed button"
                    POSConst.STS_PRINTER_ERR -> "Printer error"
                    else -> "Unknown status"
                }
                callback(Result.success(text))
            }
        } catch (platformError: Throwable) {
            callback(Result.failure(platformError))
        }
    }

    override fun getPrinterSN(printer: PrinterConnectionParams, callback: (Result<String>) -> Unit) {
        try {
            val key = getConnectionKey(printer)
            val connection = connectionsMap[key]
                ?: return callback(Result.failure(Throwable("No active connection for key=$key")))

            val pos = POSPrinter(connection)
            pos.getSerialNumber { sn ->
                val snString = try {
                    String(sn, charset("GBK"))
                } catch(e: Exception) {
                    String(sn, Charsets.UTF_8)
                }
                callback(Result.success(snString))
            }
        } catch (platformError: Throwable) {
            callback(Result.failure(platformError))
        }
    }

    override fun setNetSettingsToPrinter(
        printer: PrinterConnectionParams,
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
                            newPrinterConnection.close()
                        }, 2000)
                    }
                    POSConnect.CONNECT_FAIL,
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
                    newPrinterConnection.connect(printer.usbPath, handler)
                }
                PosPrinterConnectionType.NETWORK -> {
                    newPrinterConnection.connect(printer.ipAddress, handler)
                }
            }
        } catch (platformError: Throwable) {
            callback(Result.failure(platformError))
        }
    }

    // ====================== Методы для лейбл-принтера ======================
    override fun printLabelData(
        printer: PrinterConnectionParams,
        language: PrinterLanguage,
        labelCommands: ByteArray,
        width: Long,
        callback: (Result<Boolean>) -> Unit
    ) {
        try {
            val connection = connectionsMap[getConnectionKey(printer)]
                ?: return callback(Result.failure(Throwable("No active connection.")))

            // В зависимости от language создаём CPCLPrinter / TSPLPrinter / ZPLPrinter
            when (language) {
                PrinterLanguage.CPCL -> {
                    val cpcl = CPCLPrinter(connection)
                    // Обычно перед отправкой нужно инициализировать (initializePrinter).
                    // Но если labelCommands уже содержит всё, можно только sendData.
                    cpcl.sendData(labelCommands)
                }
                PrinterLanguage.TSPL -> {
                    val tspl = TSPLPrinter(connection)
                    // TSPL может потребовать tspl.cls() и т. д., но если labelCommands содержит всё — ок.
                    tspl.sendData(labelCommands)
                }
                PrinterLanguage.ZPL -> {
                    val zpl = ZPLPrinter(connection)
                    zpl.sendData(labelCommands)
                }
                else -> {
                    return callback(Result.failure(Throwable("Unsupported or unknown language: $language")))
                }
            }
            callback(Result.success(true))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun printLabelHTML(
        printer: PrinterConnectionParams,
        language: PrinterLanguage,
        html: String,
        width: Long,
        height: Long,
        callback: (Result<Boolean>) -> Unit
    ) {
        try {
            val connection = connectionsMap[getConnectionKey(printer)]
                ?: return callback(Result.failure(Throwable("No active connection.")))

            // 1) Рендерим HTML -> Bitmap
            val content = WebViewContent.html(html)
            val bmp = Html2Bitmap.Builder()
                .setBitmapWidth(width.toInt())
                .setStrictMode(true)
                .setContent(content)
                .setTextZoom(100)
                .setContext(context)
                .build()
                .bitmap

            // 2) Печатаем картинку в зависимости от language
            when (language) {
                PrinterLanguage.CPCL -> {
                    val cpcl = CPCLPrinter(connection)
                    // Пример установки, если нужно:
                    // cpcl.initializePrinter(height.toInt())
                    // cpcl.addCGraphics(0, 0, width.toInt(), bmp)
                    // cpcl.addPrint()
                    cpcl.initializePrinter(height.toInt())
                    cpcl.addCGraphics(0, 0, width.toInt(), bmp)
                    cpcl.addPrint()
                }
                PrinterLanguage.TSPL -> {
                    val tspl = TSPLPrinter(connection)
                    // tspl.cls()
                    // tspl.bitmap(0, 0, TSPLConst.BMP_MODE_OVERWRITE, width.toInt(), bmp)
                    // tspl.print(1)
                    tspl.sizeMm(58.0, 40.0)
                    tspl.cls()
                    tspl.bitmap(0, 0, TSPLConst.BMP_MODE_OVERWRITE, width.toInt(), bmp, AlgorithmType.Dithering)
                    tspl.print(1)
                }
                PrinterLanguage.ZPL -> {
                    val zpl = ZPLPrinter(connection)
                    // zpl.addStart()
                    // zpl.printBitmap(x, y, bmp, width.toInt())
                    // zpl.addEnd()
                    zpl.setPrinterWidth(width.toInt())
                    zpl.addStart()
                    zpl.printBmpCompress(0, 0, bmp, width.toInt(), net.posprinter.model.AlgorithmType.Dithering)
                    zpl.addEnd()
                }
                else -> {
                    return callback(Result.failure(Throwable("Unsupported or unknown language: $language")))
                }
            }
            callback(Result.success(true))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun setupLabelParams(
        printer: PrinterConnectionParams,
        language: PrinterLanguage,
        labelWidth: Long,
        labelHeight: Long,
        densityOrDarkness: Long,
        speed: Long,
        callback: (Result<Boolean>) -> Unit
    ) {
        try {
            val connection = connectionsMap[getConnectionKey(printer)]
                ?: return callback(Result.failure(Throwable("No active connection.")))

            when (language) {
                PrinterLanguage.CPCL -> {
                    val cpcl = CPCLPrinter(connection)
                    // CPCLPrinter:
                    //   initializePrinter(offset=0, height=..., qty=1)
                    //   addSpeed(level)  (0..5)
                    cpcl.initializePrinter(labelHeight.toInt(), 1)
                    cpcl.addSpeed(speed.toInt())
                    // densityOrDarkness в CPCL нет явного, но можно adjust
                    // cpcl.sendData(...) если нужно
                }
                PrinterLanguage.TSPL -> {
                    val tspl = TSPLPrinter(connection)
                    // TSPLPrinter:
                    //   sizeMm(width, height) или sizeInch(...)
                    //   density(...)
                    //   speed(...)
                    //   cls()
                    tspl.sizeMm(labelWidth.toDouble(), labelHeight.toDouble())
                    tspl.gapMm(5.0, 5.0)
                    tspl.reference(5,5)
                    tspl.offsetMm(0.0)
                    tspl.density(densityOrDarkness.toInt())
                    tspl.speed(speed.toDouble()) // speed(...) обычно double
                    tspl.cls()
                }
                PrinterLanguage.ZPL -> {
                    val zpl = ZPLPrinter(connection)
                    // ZPLPrinter:
                    //   setPrinterWidth(...)
                    //   setPrintSpeed(int speed)
                    //   setPrintDensity(int density)
                    zpl.setPrinterWidth(labelWidth.toInt())
                    zpl.setPrintSpeed(speed.toInt())     // speed in in/sec
                    zpl.setPrintDensity(densityOrDarkness.toInt())
                    // Нет отдельного \"height\", ZPL не требует
                }
                else -> {
                    return callback(Result.failure(Throwable("Unsupported or unknown language: $language")))
                }
            }
            callback(Result.success(true))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    // =============== Вспомогательные ===============
    private fun parseData(str: String): ByteArray? {
        val arr = str.split('.')
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
        printerDTO: PrinterConnectionParams,
        callback: ((Result<Unit>) -> Unit)? = null
    ) {
        val defaultCallback: (Result<Unit>) -> Unit = { result ->
            result.onSuccess {
                Log.d("POSPrinters", "PrinterConnectionParams sent to Dart successfully")
            }.onFailure { error ->
                Log.e("POSPrinters", "Error send PrinterConnectionParams to Dart: $error")
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
            POSConnect.CONNECT_FAIL,
            POSConnect.CONNECT_INTERRUPT,
            POSConnect.SEND_FAIL,
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
