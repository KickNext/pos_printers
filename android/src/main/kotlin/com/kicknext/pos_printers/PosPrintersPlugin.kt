package com.kicknext.pos_printers

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
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

    private lateinit var applicationContext: Context // Rename to avoid confusion with Activity context if used later
    private lateinit var receiver: POSPrintersReceiverApi
    private val posUdpNet = ExtendPosUdpNet()
    private lateinit var usbManager: UsbManager

    // Вместо одного currentConnection храним Map
    private val connectionsMap = mutableMapOf<String, IDeviceConnection>()

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        Log.d("POSPrinters", "onAttachedToEngine called")
        applicationContext = flutterPluginBinding.applicationContext
        POSPrintersApi.setUp(flutterPluginBinding.binaryMessenger, this)
        receiver = POSPrintersReceiverApi(flutterPluginBinding.binaryMessenger)
        usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
        POSConnect.init(this.applicationContext) // Use applicationContext
        Log.d("POSPrinters", "POSConnect initialized")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Log.d("POSPrinters", "onDetachedFromEngine called")
        POSPrintersApi.setUp(binding.binaryMessenger, null)
        // Закрывать соединения при необходимости
        // connectionsMap.values.forEach { it.close() }
        // connectionsMap.clear()
        Log.d("POSPrinters", "Plugin detached, connections should be cleaned up if needed.")
    }

    // =========================== Метод поиска ===========================
    override fun getPrinters(callback: (Result<ScanInitiationResult>) -> Unit) {
        Log.d("POSPrinters", "getPrinters called")
        var scanError: Throwable? = null
        try {
            // --- Поиск USB принтеров с деталями ---
            Log.d("POSPrinters", "Starting USB device scan...")
            val usbDevices = usbManager.deviceList // Might throw SecurityException if permission missing? Unlikely for list.
            Log.d("POSPrinters", "Found ${usbDevices.size} USB devices total.")
            usbDevices.values.forEach { device ->
                 var isLikelyPrinter = false
                 for (i in 0 until device.interfaceCount) {
                     val usbInterface = device.getInterface(i)
                     if (usbInterface.interfaceClass == 7) { // USB Printer class
                         isLikelyPrinter = true
                         break
                     }
                 }

                 if (isLikelyPrinter) {
                     Log.d("POSPrinters", "Device ${device.deviceName} (VID:${device.vendorId}, PID:${device.productId}) is likely a printer.")
                     var usbSerial: String? = null
                     // Only attempt to get serial if permission exists
                     if (usbManager.hasPermission(device)) {
                         try {
                             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                 usbSerial = device.serialNumber
                             }
                         } catch (se: SecurityException) {
                             Log.w("POSPrinters", "SecurityException getting serial number for ${device.deviceName}: ${se.message}")
                         } catch (e: Exception) {
                             Log.e("POSPrinters", "Error getting serial number for ${device.deviceName}: ${e.message}")
                         }
                     } else {
                          Log.w("POSPrinters", "Cannot get USB serial for ${device.deviceName} due to missing permission.")
                     }

                     // Send discovered USB printer back to Dart
                     returnPrinterDTO(
                         PrinterConnectionParams(
                             connectionType = PosPrinterConnectionType.USB,
                             usbPath = device.deviceName,
                             vendorId = device.vendorId.toLong(),
                             productId = device.productId.toLong(),
                             manufacturer = device.manufacturerName,
                             productName = device.productName,
                             usbSerialNumber = usbSerial
                         )
                     )
                 } else {
                      Log.d("POSPrinters", "Device ${device.deviceName} (VID:${device.vendorId}, PID:${device.productId}) is likely NOT a printer (Interface class != 7).")
                 }
            } // End of USB device loop

            // --- Поиск сетевых принтеров ---
            Log.d("POSPrinters", "USB scan finished. Signaling initiation success and starting network scan...")
            // Signal initiation success *before* starting potentially long network scan
            callback(Result.success(ScanInitiationResult(success = true)))
 
            // Start network search
            Log.d("POSPrinters", "Starting network device scan...")
            posUdpNet.searchNetDevice {
                // Send discovered network printer back to Dart
                returnPrinterDTO(
                    PrinterConnectionParams(
                        connectionType = PosPrinterConnectionType.NETWORK,
                        macAddress = it.macStr,
                        ipAddress = it.ipStr,
                        mask = it.maskStr,
                        gateway = it.gatewayStr,
                        dhcp = it.isDhcp,
                        vendorId = null, productId = null, manufacturer = null, productName = null, usbSerialNumber = null
                    )
                )
                Log.d("POSPrinters", "Network device found: IP=${it.ipStr}, MAC=${it.macStr}")
            }

            // Wait for network search completion (blocking the background thread Pigeon uses)
            var waitTime = 0
            val maxWaitTime = 5000 // 5 seconds timeout
            while (posUdpNet.isSearch && waitTime < maxWaitTime) {
                try {
                    Thread.sleep(100)
                    waitTime += 100
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt() // Re-interrupt thread
                    scanError = ie // Record the error
                    break // Exit loop if interrupted
                }
            }
            if (waitTime >= maxWaitTime && posUdpNet.isSearch) {
                 Log.w("POSPrinters", "Network scan timed out after $maxWaitTime ms.")
                 // Optionally stop the search if it's still running? Depends on SDK behavior.
                 // posUdpNet.stopSearch() // If available
            }

        } catch (e: Throwable) {
            // Catch errors during USB scan or initial setup
            scanError = e
            // Signal initiation failure ONLY if the callback hasn't been called yet
            // (It should have been called in the try block before network scan)
            // This catch block might now only catch errors before the callback(success) line
             Log.e("POSPrinters", "Error during printer scan initiation: ${e.message}", e)
             // Ensure callback is called with failure if it hasn't succeeded yet
             callback(Result.success(ScanInitiationResult(success = false, errorMessage = e.message ?: "Failed to start scan")))
             // Don't proceed to scanCompleted if initiation failed critically
             return
        } finally {
            // This block executes regardless of exceptions in the try block
            // Signal completion status AFTER all scanning attempts (USB + Network wait)
            val scanSuccess = scanError == null
            val errorMessage = if (!scanSuccess) scanError?.message ?: "Scan failed" else null
            // Ensure the call to Dart happens on the main thread
            Handler(Looper.getMainLooper()).post {
                receiver.scanCompleted(scanSuccess, errorMessage) {}
            }
            Log.d("POSPrinters", "Scan completed. Success: $scanSuccess, Error: $errorMessage")
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
        Log.d("POSPrinters", "connectPrinter called for type: ${printer.connectionType}, path/IP: ${printer.usbPath ?: printer.ipAddress}")
        val key = getConnectionKey(printer) // Define key outside try
        try {
            // Close previous connection for this key, if any
            connectionsMap[key]?.close()
            connectionsMap.remove(key)

            val newConnection = when (printer.connectionType) {
                PosPrinterConnectionType.USB -> POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
                PosPrinterConnectionType.NETWORK -> POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
            }

            val connectionInfo = when (printer.connectionType) {
                PosPrinterConnectionType.USB -> printer.usbPath ?: "" // Use empty string if null, though usbPath should exist
                PosPrinterConnectionType.NETWORK -> printer.ipAddress ?: "" // Use empty string if null
            }

            if (connectionInfo.isEmpty()) {
                 Log.e("POSPrinters", "Connect failed: Missing connection info (usbPath or ipAddress) for type ${printer.connectionType}")
                 callback(Result.success(ConnectResult(success = false, message = "Missing connection info (usbPath or ipAddress)")))
                 return
            }

            // Define the listener specifically for this connection attempt
            // Use a flag to ensure the callback is only called once
            var replySubmitted = false
            val listener = IConnectListener { code, connInfo, msg -> // Explicitly name parameters
                 Log.d("POSPrinters", "connectPrinter listener invoked: code=$code, info=$connInfo, msg=$msg") // Log raw info/msg too
                 synchronized(this) { // Synchronize access to the flag
                     if (replySubmitted) {
                         Log.w("POSPrinters", "connectPrinter listener called again (code=$code), ignoring as reply was already submitted.")
                         return@synchronized // Exit synchronized block and listener lambda
                     }

                     when (code) {
                         POSConnect.CONNECT_SUCCESS -> {
                             // Store the successful connection
                             Log.i("POSPrinters", "CONNECT_SUCCESS for key=$key")
                             connectionsMap[key] = newConnection
                             callback(Result.success(ConnectResult(success = true, message = "Connected successfully")))
                             replySubmitted = true
                         }
                         POSConnect.CONNECT_FAIL -> {
                             Log.w("POSPrinters", "CONNECT_FAIL for key=$key")
                             connectionsMap.remove(key) // Ensure failed connection isn't stored
                             callback(Result.success(ConnectResult(success = false, message = "Connection failed")))
                             replySubmitted = true
                         }
                         POSConnect.CONNECT_INTERRUPT -> {
                              Log.w("POSPrinters", "CONNECT_INTERRUPT for key=$key")
                              connectionsMap.remove(key)
                              callback(Result.success(ConnectResult(success = false, message = "Connection interrupted")))
                              replySubmitted = true
                         }
                         // Ignore other potential intermediate codes unless they signify a final failure
                         // else -> {
                         //      connectionsMap.remove(key)
                         //      callback(Result.success(ConnectResult(success = false, message = "Connection failed with unknown code: $code")))
                         //      replySubmitted = true
                         // }
                     }
                 } // End synchronized block
            }

            Log.d("POSPrinters", "Initiating connection to '$connectionInfo'...")
            // Initiate the connection with the specific listener
            newConnection.connect(connectionInfo, listener)
            // DO NOT call callback here; it's handled by the listener

        } catch (platformError: Throwable) {
            Log.e("POSPrinters", "Exception during connectPrinter setup for key=$key: ${platformError.message}", platformError)
            // Catch exceptions during device creation or connect initiation
            callback(Result.success(ConnectResult(success = false, message = "Connect exception: ${platformError.message}")))
        }
    }

    override fun disconnectPrinter(
        printer: PrinterConnectionParams,
        callback: (Result<OperationResult>) -> Unit
    ) {
        Log.d("POSPrinters", "disconnectPrinter called for type: ${printer.connectionType}, path/IP: ${printer.usbPath ?: printer.ipAddress}")
        val key = getConnectionKey(printer) // Define key outside try
        try {
            // val key = getConnectionKey(printer) // Removed duplicate definition
            // val key = getConnectionKey(printer) // Removed duplicate definition
            val connection = connectionsMap[key]
            if (connection != null) {
                connection.close() // Assuming close() doesn't throw critical errors often
                Log.i("POSPrinters", "Disconnected and removed connection for key=$key")
                connectionsMap.remove(key)
                callback(Result.success(OperationResult(success = true)))
            } else {
                Log.w("POSPrinters", "disconnectPrinter: No active connection found for key=$key")
                // Return success=false if no connection found, rather than failing the call
                callback(Result.success(OperationResult(success = false, errorMessage = "No active connection found for key=$key")))
            }
        } catch (platformError: Throwable) {
            Log.e("POSPrinters", "Exception during disconnectPrinter for key=$key: ${platformError.message}", platformError)
            callback(Result.success(OperationResult(success = false, errorMessage = "Disconnect exception: ${platformError.message}")))
        }
    }

    // ====================== Методы ESC/POS ======================
    override fun printData(
        printer: PrinterConnectionParams,
        data: ByteArray,
        width: Long, // Note: width is often unused for raw data, but kept for API consistency
        callback: (Result<OperationResult>) -> Unit
    ) {
        Log.d("POSPrinters", "printData called for type: ${printer.connectionType}, path/IP: ${printer.usbPath ?: printer.ipAddress}, data size: ${data.size}")
        val key = getConnectionKey(printer) // Define key outside try
        try {
            // val key = getConnectionKey(printer) // Removed duplicate definition
            // val key = getConnectionKey(printer) // Removed duplicate definition
            val connection = connectionsMap[key]
            if (connection == null) {
                Log.w("POSPrinters", "printData: No active connection found for key=$key")
                return callback(Result.success(OperationResult(success = false, errorMessage = "No active connection found for key=$key")))
            }

            val curPrinter = POSPrinter(connection)
            Log.d("POSPrinters", "Initializing printer for raw data...")
            curPrinter.initializePrinter() // Ensure printer is in ESC/POS mode
            // Assuming sendData doesn't provide direct success/failure feedback easily
            // We'll assume success if no exception is thrown.
            // For more robust handling, the SDK might need specific result checks.
            Log.d("POSPrinters", "Sending raw data...")
            curPrinter.sendData(data)
            Log.d("POSPrinters", "Raw data sent. Checking status afterwards...")
            // Check status after sending to catch potential command incompatibility
            curPrinter.printerStatus { status ->
                val statusText = mapStatusCodeToString(status)
                val isError = status < POSConst.STS_NORMAL || status == POSConst.STS_PRINTER_ERR
                Log.d("POSPrinters", "Post-printData status check: code=$status, text='$statusText', isError=$isError")
                if (isError) {
                     callback(Result.success(OperationResult(success = false, errorMessage = "Post-print status error: $statusText")))
                } else {
                     callback(Result.success(OperationResult(success = true)))
                }
            }
        } catch (platformError: Throwable) {
            Log.e("POSPrinters", "Exception during printData for key=$key: ${platformError.message}", platformError)
            callback(Result.success(OperationResult(success = false, errorMessage = "Print data exception: ${platformError.message}")))
        }
    }

    override fun printHTML(
        printer: PrinterConnectionParams,
        html: String,
        width: Long,
        callback: (Result<OperationResult>) -> Unit
    ) {
        Log.d("POSPrinters", "printHTML called for type: ${printer.connectionType}, path/IP: ${printer.usbPath ?: printer.ipAddress}, width: $width")
        val key = getConnectionKey(printer) // Define key outside try
        try {
            // val key = getConnectionKey(printer) // Removed duplicate definition
            // val key = getConnectionKey(printer) // Removed duplicate definition
            val connection = connectionsMap[key]
            if (connection == null) {
                Log.w("POSPrinters", "printHTML: No active connection found for key=$key")
                return callback(Result.success(OperationResult(success = false, errorMessage = "No active connection found for key=$key")))
            }
            Log.d("POSPrinters", "Generating bitmap from HTML...")
            val content = WebViewContent.html(html)
            val bitmap = Html2Bitmap.Builder()
                .setBitmapWidth(width.toInt())
                .setContent(content)
                .setTextZoom(100)
                .setContext(applicationContext) // Use applicationContext
                .build()
                .bitmap

            val curPrinter = POSPrinter(connection)
            Log.d("POSPrinters", "Bitmap generated. Initializing printer for HTML print...")
            curPrinter.initializePrinter() // Ensure printer is in ESC/POS mode
                .printBitmap(bitmap, POSConst.ALIGNMENT_LEFT, width.toInt())
                .cutHalfAndFeed(1)
            Log.d("POSPrinters", "Bitmap printed and cut. Checking status afterwards...")
            // Check status after printing to catch potential command incompatibility
            curPrinter.printerStatus { status ->
                val statusText = mapStatusCodeToString(status)
                val isError = status < POSConst.STS_NORMAL || status == POSConst.STS_PRINTER_ERR
                Log.d("POSPrinters", "Post-printHTML status check: code=$status, text='$statusText', isError=$isError")
                if (isError) {
                     callback(Result.success(OperationResult(success = false, errorMessage = "Post-print status error: $statusText")))
                } else {
                     callback(Result.success(OperationResult(success = true)))
                }
            }
        } catch (platformError: Throwable) {
            Log.e("POSPrinters", "Exception during printHTML for key=$key: ${platformError.message}", platformError)
            callback(Result.success(OperationResult(success = false, errorMessage = "Print HTML exception: ${platformError.message}")))
        }
    }

    override fun openCashBox(printer: PrinterConnectionParams, callback: (Result<OperationResult>) -> Unit) {
        Log.d("POSPrinters", "openCashBox called for type: ${printer.connectionType}, path/IP: ${printer.usbPath ?: printer.ipAddress}")
        val key = getConnectionKey(printer) // Define key outside try
        try {
            // val key = getConnectionKey(printer) // Removed duplicate definition
            val connection = connectionsMap[key]
            if (connection == null) {
                Log.w("POSPrinters", "openCashBox: No active connection found for key=$key")
                return callback(Result.success(OperationResult(success = false, errorMessage = "No active connection found for key=$key")))
            }
            val curPrinter = POSPrinter(connection)
            Log.d("POSPrinters", "Initializing printer for open cash box...")
            curPrinter.initializePrinter() // Ensure printer is in ESC/POS mode
            // Assume success if no exception
            Log.d("POSPrinters", "Sending open cash box command...")
            curPrinter.openCashBox(POSConst.PIN_TWO)
            Log.d("POSPrinters", "Open cash box command sent (assumed success).")
            // Status check might not be relevant/reliable immediately after cashbox open
            callback(Result.success(OperationResult(success = true)))
        } catch (platformError: Throwable) {
            Log.e("POSPrinters", "Exception during openCashBox for key=$key: ${platformError.message}", platformError)
            callback(Result.success(OperationResult(success = false, errorMessage = "Open cash box exception: ${platformError.message}")))
        }
    }

    // ====================== Методы общие ======================
    override fun getPrinterStatus(printer: PrinterConnectionParams, callback: (Result<StatusResult>) -> Unit) {
        Log.d("POSPrinters", "getPrinterStatus called for type: ${printer.connectionType}, path/IP: ${printer.usbPath ?: printer.ipAddress}")
        val key = getConnectionKey(printer) // Define key outside try
        try {
            // val key = getConnectionKey(printer) // Removed duplicate definition
            val connection = connectionsMap[key]
            if (connection == null) {
                Log.w("POSPrinters", "getPrinterStatus: No active connection found for key=$key")
                return callback(Result.success(StatusResult(success = false, errorMessage = "No active connection found for key=$key")))
            }
            // Для ESC/POS мы делаем POSPrinter(connection).printerStatus(...)
            // Для лейбл-принтеров (CPCL/TSPL/ZPL) тоже есть printerStatus, но код возврата другой.
            // Если вы хотите унифицировать, можно оставить тот же подход:
            val pos = POSPrinter(connection)
            Log.d("POSPrinters", "Initializing printer and requesting status...")
            pos.initializePrinter() // Ensure printer is in ESC/POS mode before status check
            pos.printerStatus { status ->
                Log.d("POSPrinters", "getPrinterStatus callback received: status code = $status")
                val text = mapStatusCodeToString(status) // Use helper function
                // Check if status indicates an error state based on the text mapping
                val isErrorStatus = status < POSConst.STS_NORMAL || status == POSConst.STS_PRINTER_ERR || status < 0 // Include SDK errors
                Log.d("POSPrinters", "Mapped status: '$text', IsError: $isErrorStatus")
                if (isErrorStatus) {
                    // Report success=false if status indicates an error, even if the call succeeded technically
                    callback(Result.success(StatusResult(success = false, errorMessage = text, status = text)))
                } else {
                    callback(Result.success(StatusResult(success = true, status = text)))
                }
            }
        } catch (platformError: Throwable) {
            Log.e("POSPrinters", "Exception during getPrinterStatus for key=$key: ${platformError.message}", platformError)
            callback(Result.success(StatusResult(success = false, errorMessage = "Get status exception: ${platformError.message}")))
        }
    }

    override fun getPrinterSN(printer: PrinterConnectionParams, callback: (Result<StringResult>) -> Unit) {
        Log.d("POSPrinters", "getPrinterSN called for type: ${printer.connectionType}, path/IP: ${printer.usbPath ?: printer.ipAddress}")
        val key = getConnectionKey(printer) // Define key outside try
        try {
            // val key = getConnectionKey(printer) // Removed duplicate definition
            val connection = connectionsMap[key]
            if (connection == null) {
                Log.w("POSPrinters", "getPrinterSN: No active connection found for key=$key")
                return callback(Result.success(StringResult(success = false, errorMessage = "No active connection found for key=$key")))
            }
            Log.d("POSPrinters", "Initializing printer and requesting serial number...")
            val pos = POSPrinter(connection)
            pos.initializePrinter() // Ensure printer is in ESC/POS mode before getting SN
            pos.getSerialNumber { sn ->
                Log.d("POSPrinters", "getSerialNumber callback received.")
                val snString = try {
                    String(sn, charset("GBK"))
                } catch(e: Exception) {
                    try { String(sn, Charsets.UTF_8) } catch (e2: Exception) { "Error decoding SN" } // Improved fallback
                }
                Log.d("POSPrinters", "Decoded SN: '$snString'")
                // Assume success if callback is invoked without error, unless SN is empty/error string?
                if (snString.isEmpty() || snString == "Error decoding SN") {
                     callback(Result.success(StringResult(success = false, errorMessage = "Failed to decode serial number", value = snString)))
                } else {
                     callback(Result.success(StringResult(success = true, value = snString)))
                }
            }
        } catch (platformError: Throwable) {
            Log.e("POSPrinters", "Exception during getPrinterSN for key=$key: ${platformError.message}", platformError)
            callback(Result.success(StringResult(success = false, errorMessage = "Get SN exception: ${platformError.message}")))
        }
    }

    override fun setNetSettingsToPrinter(
        printer: PrinterConnectionParams,
        netSettings: NetSettingsDTO,
        callback: (Result<OperationResult>) -> Unit
    ) {
        Log.d("POSPrinters", "setNetSettingsToPrinter called for type: ${printer.connectionType}, path/IP: ${printer.usbPath ?: printer.ipAddress}")
        try {
            val ip = parseData(netSettings.ipAddress)
            val mask = parseData(netSettings.mask)
            val gateway = parseData(netSettings.gateway)
            val dhcp = netSettings.dhcp

            val newPrinterConnection = when (printer.connectionType) {
                PosPrinterConnectionType.USB -> POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
                PosPrinterConnectionType.NETWORK -> POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
            }
            Log.d("POSPrinters", "Setting up temporary connection for net settings...")
            val handler = IConnectListener { code, connInfo, msg ->
                Log.d("POSPrinters", "setNetSettings listener invoked: code=$code, info=$connInfo, msg=$msg")
                when (code) {
                    POSConnect.CONNECT_SUCCESS -> {
                        Log.i("POSPrinters", "Temp connection successful for net settings. Applying settings...")
                        val p = POSPrinter(newPrinterConnection)
                        p.setNetAll(ip, mask, gateway, dhcp)
                        Log.i("POSPrinters", "Net settings applied (assumed success). Closing temp connection after delay.")
                        // Assume setNetAll succeeded if no immediate error
                        Handler(Looper.getMainLooper()).postDelayed({
                            callback(Result.success(OperationResult(success = true)))
                            newPrinterConnection.close()
                            Log.d("POSPrinters", "Temp connection closed after net settings.")
                        }, 2000)
                    }
                    POSConnect.CONNECT_FAIL -> {
                        Log.w("POSPrinters", "Temp connection failed during net settings.")
                        callback(Result.success(OperationResult(success = false, errorMessage = "Connection failed during net settings")))
                    }
                    POSConnect.CONNECT_INTERRUPT -> {
                        Log.w("POSPrinters", "Temp connection interrupted during net settings.")
                        callback(Result.success(OperationResult(success = false, errorMessage = "Connection interrupted during net settings")))
                    }
                    POSConnect.SEND_FAIL -> {
                        Log.w("POSPrinters", "Send failed during net settings.")
                        callback(Result.success(OperationResult(success = false, errorMessage = "Failed to send net settings command")))
                    }
                    else -> {
                        Log.w("POSPrinters", "Unknown connection error during net settings (Code: $code)")
                        callback(Result.success(OperationResult(success = false, errorMessage = "Unknown connection error during net settings (Code: $code)")))
                    }
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
            Log.e("POSPrinters", "Exception during setNetSettingsToPrinter setup: ${platformError.message}", platformError)
            callback(Result.success(OperationResult(success = false, errorMessage = "Set net settings exception: ${platformError.message}")))
        }
    }

    // ====================== Методы для лейбл-принтера ======================
    override fun printLabelData(
        printer: PrinterConnectionParams,
        language: LabelPrinterLanguage,
        labelCommands: ByteArray,
        width: Long, // Note: width might be ignored for raw label commands
        callback: (Result<OperationResult>) -> Unit
    ) {
        Log.d("POSPrinters", "printLabelData called for type: ${printer.connectionType}, path/IP: ${printer.usbPath ?: printer.ipAddress}, lang: $language, data size: ${labelCommands.size}")
        val key = getConnectionKey(printer) // Define key outside try
        try {
            val connection = connectionsMap[getConnectionKey(printer)]
            if (connection == null) {
                Log.w("POSPrinters", "printLabelData: No active connection found for key=${getConnectionKey(printer)}")
                return callback(Result.success(OperationResult(success = false, errorMessage = "No active connection found.")))
            }

            // В зависимости от language создаём CPCLPrinter / TSPLPrinter / ZPLPrinter
            when (language) {
                LabelPrinterLanguage.CPCL -> {
                    val cpcl = CPCLPrinter(connection)
                    // Обычно перед отправкой нужно инициализировать (initializePrinter).
                    Log.d("POSPrinters", "Sending CPCL data...")
                    // Но если labelCommands уже содержит всё, можно только sendData.
                    cpcl.sendData(labelCommands)
                    Log.d("POSPrinters", "CPCL data sent (assumed success).")
                }
                LabelPrinterLanguage.TSPL -> {
                    val tspl = TSPLPrinter(connection)
                    Log.d("POSPrinters", "Sending TSPL data...")
                    // TSPL может потребовать tspl.cls() и т. д., но если labelCommands содержит всё — ок.
                    tspl.sendData(labelCommands)
                    Log.d("POSPrinters", "TSPL data sent (assumed success).")
                }
                LabelPrinterLanguage.ZPL -> {
                    Log.d("POSPrinters", "Sending ZPL data...")
                    val zpl = ZPLPrinter(connection)
                    zpl.sendData(labelCommands)
                    Log.d("POSPrinters", "ZPL data sent (assumed success).")
                }
//                else -> { // else ветка не нужна, т.к. enum покрывает все случаи
//                    return callback(Result.failure(Throwable("Unsupported or unknown language: $language")))
//                }
            }
            // Assume success if no exception during sendData
            callback(Result.success(OperationResult(success = true)))
        } catch (e: Throwable) {
            Log.e("POSPrinters", "Exception during printLabelData for key=$key: ${e.message}", e)
            callback(Result.success(OperationResult(success = false, errorMessage = "Print label data exception: ${e.message}")))
        }
    }

    override fun printLabelHTML(
        printer: PrinterConnectionParams,
        language: LabelPrinterLanguage,
        html: String,
        width: Long,
        height: Long,
        callback: (Result<OperationResult>) -> Unit
    ) {
        Log.d("POSPrinters", "printLabelHTML called for type: ${printer.connectionType}, path/IP: ${printer.usbPath ?: printer.ipAddress}, lang: $language, width: $width, height: $height")
        val key = getConnectionKey(printer) // Define key outside try
        try {
            val connection = connectionsMap[getConnectionKey(printer)]
            if (connection == null) {
                Log.w("POSPrinters", "printLabelHTML: No active connection found for key=${getConnectionKey(printer)}")
                return callback(Result.success(OperationResult(success = false, errorMessage = "No active connection found.")))
            }

            Log.d("POSPrinters", "Generating bitmap from HTML for label...")
            // 1) Рендерим HTML -> Bitmap
            val content = WebViewContent.html(html)
            val bmp = Html2Bitmap.Builder()
                .setBitmapWidth(width.toInt())
                .setStrictMode(true)
                .setContent(content)
                .setTextZoom(100)
                .setContext(applicationContext) // Use applicationContext
                .build()
                .bitmap
            Log.d("POSPrinters", "Bitmap generated for label.")

            // 2) Печатаем картинку в зависимости от language
            when (language) {
                LabelPrinterLanguage.CPCL -> {
                    val cpcl = CPCLPrinter(connection)
                    // Пример установки, если нужно:
                    // cpcl.initializePrinter(height.toInt())
                    // cpcl.addCGraphics(0, 0, width.toInt(), bmp)
                    Log.d("POSPrinters", "Printing label bitmap via CPCL...")
                    // cpcl.addPrint()
                    cpcl.initializePrinter(height.toInt())
                    cpcl.addCGraphics(0, 0, width.toInt(), bmp)
                    cpcl.addPrint()
                    Log.d("POSPrinters", "CPCL label print commands sent.")
                }
                LabelPrinterLanguage.TSPL -> {
                    val tspl = TSPLPrinter(connection)
                    // tspl.cls()
                    // tspl.bitmap(0, 0, TSPLConst.BMP_MODE_OVERWRITE, width.toInt(), bmp)
                    Log.d("POSPrinters", "Printing label bitmap via TSPL...")
                    // tspl.print(1)
                    tspl.sizeMm(58.0, 40.0) // TODO: Should use labelWidth/labelHeight from params?
                    tspl.cls()
                    tspl.bitmap(0, 0, TSPLConst.BMP_MODE_OVERWRITE, width.toInt(), bmp, AlgorithmType.Dithering)
                    tspl.print(1)
                    Log.d("POSPrinters", "TSPL label print commands sent.")
                }
                LabelPrinterLanguage.ZPL -> {
                    val zpl = ZPLPrinter(connection)
                    // zpl.addStart()
                    // zpl.printBitmap(x, y, bmp, width.toInt())
                    Log.d("POSPrinters", "Printing label bitmap via ZPL...")
                    // zpl.addEnd()
                    zpl.setPrinterWidth(width.toInt())
                    zpl.addStart()
                    zpl.printBmpCompress(0, 0, bmp, width.toInt(), net.posprinter.model.AlgorithmType.Dithering)
                    zpl.addEnd()
                    Log.d("POSPrinters", "ZPL label print commands sent.")
                }
//                else -> { // else ветка не нужна, т.к. enum покрывает все случаи
//                    return callback(Result.failure(Throwable("Unsupported or unknown language: $language")))
//                }
            }
            // Assume success if no exception during bitmap creation or printing
            callback(Result.success(OperationResult(success = true)))
        } catch (e: Throwable) {
            Log.e("POSPrinters", "Exception during printLabelHTML for key=$key: ${e.message}", e)
            callback(Result.success(OperationResult(success = false, errorMessage = "Print label HTML exception: ${e.message}")))
        }
    }

    override fun setupLabelParams(
        printer: PrinterConnectionParams,
        language: LabelPrinterLanguage,
        labelWidth: Long,
        labelHeight: Long,
        densityOrDarkness: Long,
        speed: Long,
        callback: (Result<OperationResult>) -> Unit
    ) {
        Log.d("POSPrinters", "setupLabelParams called for type: ${printer.connectionType}, path/IP: ${printer.usbPath ?: printer.ipAddress}, lang: $language, w:$labelWidth, h:$labelHeight, density:$densityOrDarkness, speed:$speed")
        val key = getConnectionKey(printer) // Define key outside try
        try {
            val connection = connectionsMap[getConnectionKey(printer)]
            if (connection == null) {
                Log.w("POSPrinters", "setupLabelParams: No active connection found for key=${getConnectionKey(printer)}")
                return callback(Result.success(OperationResult(success = false, errorMessage = "No active connection found.")))
            }

            when (language) {
                LabelPrinterLanguage.CPCL -> {
                    val cpcl = CPCLPrinter(connection)
                    // CPCLPrinter:
                    //   initializePrinter(offset=0, height=..., qty=1)
                    Log.d("POSPrinters", "Setting up CPCL params...")
                    //   addSpeed(level)  (0..5)
                    cpcl.initializePrinter(labelHeight.toInt(), 1)
                    cpcl.addSpeed(speed.toInt())
                    // densityOrDarkness в CPCL нет явного, но можно adjust
                    // cpcl.sendData(...) если нужно
                    Log.d("POSPrinters", "CPCL params set (assumed success).")
                }
                LabelPrinterLanguage.TSPL -> {
                    val tspl = TSPLPrinter(connection)
                    // TSPLPrinter:
                    //   sizeMm(width, height) или sizeInch(...)
                    Log.d("POSPrinters", "Setting up TSPL params...")
                    //   density(...)
                    //   speed(...)
                    //   cls()
                    tspl.sizeMm(labelWidth.toDouble(), labelHeight.toDouble())
                    tspl.gapMm(5.0, 5.0) // TODO: Make gap configurable?
                    tspl.reference(5,5) // TODO: Make reference configurable?
                    tspl.offsetMm(0.0) // TODO: Make offset configurable?
                    tspl.density(densityOrDarkness.toInt())
                    tspl.speed(speed.toDouble()) // speed(...) обычно double
                    tspl.cls()
                    Log.d("POSPrinters", "TSPL params set (assumed success).")
                }
                LabelPrinterLanguage.ZPL -> {
                    val zpl = ZPLPrinter(connection)
                    // ZPLPrinter:
                    //   setPrinterWidth(...)
                    //   setPrintSpeed(int speed)
                    Log.d("POSPrinters", "Setting up ZPL params...")
                    //   setPrintDensity(int density)
                    zpl.setPrinterWidth(labelWidth.toInt())
                    zpl.setPrintSpeed(speed.toInt())     // speed in in/sec
                    zpl.setPrintDensity(densityOrDarkness.toInt())
                    // Нет отдельного \"height\", ZPL не требует
                    Log.d("POSPrinters", "ZPL params set (assumed success).")
                }
//                else -> { // else ветка не нужна, т.к. enum покрывает все случаи
//                    return callback(Result.failure(Throwable("Unsupported or unknown language: $language")))
//                }
            }
            // Assume success if no exception during setup commands
            callback(Result.success(OperationResult(success = true)))
        } catch (e: Throwable) {
            Log.e("POSPrinters", "Exception during setupLabelParams for key=$key: ${e.message}", e)
            callback(Result.success(OperationResult(success = false, errorMessage = "Setup label params exception: ${e.message}")))
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
        // Ensure this runs on the main thread if UI updates depend on it in Dart
        Handler(Looper.getMainLooper()).post {
            receiver.connectionHandler(status, effectiveCallback)
        }
    }

    // ====================== Получение деталей ======================
    // Реализация нового метода
    // Note: This implementation fetches details asynchronously.
    // Consider using Kotlin Coroutines for cleaner async handling if complexity increases.
    override fun getPrinterDetails(printer: PrinterConnectionParams, callback: (Result<PrinterDetailsDTO>) -> Unit) {
        Log.d("POSPrinters", "getPrinterDetails called for type: ${printer.connectionType}, path/IP: ${printer.usbPath ?: printer.ipAddress}")
        val key = getConnectionKey(printer)
        val connection = connectionsMap[key]

        if (connection == null) {
            Log.w("POSPrinters", "getPrinterDetails: No active connection found for key=$key")
            // Use Result.failure for consistency, though Pigeon might map it back anyway
            callback(Result.failure(Exception("No active connection found for key=$key")))
            return
        }

        // Variables to hold results and track completion
        var serialNumber: String? = null
        var currentStatus: String? = null
        var snError: String? = null
        var statusError: String? = null
        var callbacksCompleted = 0
        val totalCallbacksExpected = 2 // SN + Status

        // Function to call when both async operations (SN, Status) are done
        val onComplete = {
            // Ensure this callback runs on the main thread for safety with Pigeon callback
             Handler(Looper.getMainLooper()).post {
                 Log.d("POSPrinters", "getPrinterDetails onComplete: SN Error: $snError, Status Error: $statusError")
                 if (snError != null || statusError != null) {
                     // Combine error messages if any occurred
                     val combinedErrorMessage = listOfNotNull(snError, statusError).joinToString("; ")
                     Log.e("POSPrinters", "Failed to get full printer details: $combinedErrorMessage")
                     callback(Result.failure(Exception("Failed to get full printer details: $combinedErrorMessage")))
                 } else {
                     // Both succeeded, build and return the DTO
                     val details = PrinterDetailsDTO(
                         serialNumber = serialNumber,
                         currentStatus = currentStatus,
                         // TODO: Add firmwareVersion and deviceModel if the SDK provides methods to get them
                         firmwareVersion = null, // Placeholder
                         deviceModel = null      // Placeholder
                     )
                     Log.i("POSPrinters", "Successfully retrieved printer details: SN=$serialNumber, Status=$currentStatus")
                     callback(Result.success(details))
                 }
             }
        }

        Log.d("POSPrinters", "getPrinterDetails: Requesting SN...")
        // --- Get Serial Number ---
        try {
            val posSN = POSPrinter(connection)
            posSN.getSerialNumber { snBytes ->
                Log.d("POSPrinters", "getPrinterDetails: SN callback received.")
                serialNumber = try {
                    String(snBytes, charset("GBK")) // Try GBK first as per original code
                } catch (e: Exception) {
                    try { String(snBytes, Charsets.UTF_8) } catch (e2: Exception) { "Error decoding SN" }
                }
                Log.d("POSPrinters", "getPrinterDetails: Decoded SN: $serialNumber")
                synchronized(this) { // Synchronize access to shared counter
                    callbacksCompleted++
                    Log.d("POSPrinters", "getPrinterDetails: SN callback complete. Total completed: $callbacksCompleted/$totalCallbacksExpected")
                    if (callbacksCompleted == totalCallbacksExpected) onComplete()
                }
            }
            // Note: The SDK's getSerialNumber might need better error handling if the callback itself can signal failure.
            // If the callback *can* fail, we'd need an error parameter in the lambda.
        } catch (e: Exception) {
             Log.e("POSPrinters", "getPrinterDetails: Exception requesting SN: ${e.message}", e)
             synchronized(this) {
                 snError = "Get SN exception: ${e.message}"
                 callbacksCompleted++
                 Log.d("POSPrinters", "getPrinterDetails: SN exception caught. Total completed: $callbacksCompleted/$totalCallbacksExpected")
                 if (callbacksCompleted == totalCallbacksExpected) onComplete()
             }
        }

        Log.d("POSPrinters", "getPrinterDetails: Requesting Status...")
        // --- Get Printer Status ---
        try {
            val posStatus = POSPrinter(connection)
            posStatus.printerStatus { status ->
                Log.d("POSPrinters", "getPrinterDetails: Status callback received: code=$status")
                currentStatus = when (status) {
                    POSConst.STS_NORMAL -> "Normal status"
                    POSConst.STS_COVEROPEN -> "Cover open"
                    POSConst.STS_PAPEREMPTY -> "Paper empty"
                    POSConst.STS_PRESS_FEED -> "Press the paper feed button"
                    POSConst.STS_PRINTER_ERR -> "Printer error"
                    -1 -> "Status check: Unknown errors" // More specific error text
                    -3 -> "Status check: Connection disconnected"
                    -4 -> "Status check: Receiving data timed out"
                    else -> "Unknown status code: $status"
                }
                Log.d("POSPrinters", "getPrinterDetails: Mapped status: '$currentStatus'")
                // If the status itself indicates an error, record it
                if (status < POSConst.STS_NORMAL || status == POSConst.STS_PRINTER_ERR) {
                     Log.w("POSPrinters", "getPrinterDetails: Printer reported error status: $currentStatus (code=$status)")
                     synchronized(this) { // Synchronize access to shared error variable
                         if (statusError == null) statusError = "Printer reported error status: $currentStatus"
                     }
                }
                 synchronized(this) { // Synchronize access to shared counter
                    callbacksCompleted++
                    Log.d("POSPrinters", "getPrinterDetails: Status callback complete. Total completed: $callbacksCompleted/$totalCallbacksExpected")
                    if (callbacksCompleted == totalCallbacksExpected) onComplete()
                 }
            }
             // Note: The SDK's printerStatus might need better error handling if the callback itself can signal failure.
        } catch (e: Exception) {
             Log.e("POSPrinters", "getPrinterDetails: Exception requesting Status: ${e.message}", e)
             synchronized(this) {
                 statusError = "Get status exception: ${e.message}"
                 callbacksCompleted++
                 Log.d("POSPrinters", "getPrinterDetails: Status exception caught. Total completed: $callbacksCompleted/$totalCallbacksExpected")
                 if (callbacksCompleted == totalCallbacksExpected) onComplete()
             }
        }
    }


    // Listener for connection events (including USB attach/detach)
    private val connectListener = IConnectListener { code, connInfo, msg ->
        Log.d("POSPrinters", "IConnectListener: code=$code, connInfo=$connInfo, msg=$msg")
        when (code) {
            POSConnect.CONNECT_SUCCESS -> {
                // Successfully connected (or reconnected after attach)
                println(connInfo)
                println(msg)
                // Send standard success message
                sendStatus(ConnectResult(true, "Connected: $connInfo"))
            }
            POSConnect.CONNECT_FAIL,
            POSConnect.CONNECT_INTERRUPT -> {
                 // Connection failed or interrupted
                println(connInfo)
                println(msg)
                sendStatus(ConnectResult(false, "Connection failed/interrupted: $connInfo - $msg"))
                // Clean up connection if it exists for this key
                // Note: connInfo might be null or different here, need robust key finding if possible
                // connectionsMap.remove(...) - Requires careful handling
            }
             POSConnect.SEND_FAIL -> {
                 // Failed to send data (might happen during operations)
                 println(connInfo)
                 println(msg)
                 // This might not warrant a full disconnect event, depends on desired behavior
                 sendStatus(ConnectResult(false, "Send failed: $connInfo - $msg"))
             }
            POSConnect.USB_DETACHED -> {
                // USB device was detached
                println(connInfo) // connInfo here is usually the USB path that was detached
                println(msg)
                val detachedPath = connInfo ?: "unknown path"
                val key = "usb:$detachedPath"
                // Remove the connection from our map
                connectionsMap[key]?.close() // Attempt to close just in case
                connectionsMap.remove(key)
                // Send specific status to Dart
                sendStatus(ConnectResult(false, "USB detached: $detachedPath"))
            }
            POSConnect.USB_ATTACHED -> {
                // A USB device was attached (could be the same one or a different one)
                println(connInfo) // connInfo might be null or generic here
                println(msg)
                // Send a generic "USB attached" message first
                 sendStatus(ConnectResult(true, "USB attached, rescanning..."))

                // Rescan USB devices to find the new path(s)
                try {
                    val currentUsbPaths = POSConnect.getUsbDevices(applicationContext)
                    Log.d("POSPrinters", "USB Attached: Found paths: $currentUsbPaths")
                    // Повторно сканируем USB устройства и отправляем DTO с деталями
                    val attachedUsbDevices = usbManager.deviceList
                    Log.d("POSPrinters", "USB Attached: Found paths: ${attachedUsbDevices.keys}")
                    attachedUsbDevices.values.forEach { device ->
                        // Проверяем, принтер ли это (как в getPrinters)
                        var isLikelyPrinter = false
                        for (i in 0 until device.interfaceCount) {
                            if (device.getInterface(i).interfaceClass == 7) {
                                isLikelyPrinter = true
                                break
                            }
                        }

                        if (isLikelyPrinter) {
                             var usbSerial: String? = null
                             if (usbManager.hasPermission(device)) {
                                 try {
                                     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                         usbSerial = device.serialNumber
                                     }
                                 } catch (e: Exception) { /* ignore */ }
                             }

                             returnPrinterDTO(
                                 PrinterConnectionParams(
                                     connectionType = PosPrinterConnectionType.USB,
                                     usbPath = device.deviceName,
                                     vendorId = device.vendorId.toLong(),
                                     productId = device.productId.toLong(),
                                     manufacturer = device.manufacturerName,
                                     productName = device.productName,
                                     usbSerialNumber = usbSerial
                                 )
                             )
                        }
                    }
                } catch (e: Throwable) {
                     Log.e("POSPrinters", "Error rescanning USB devices after attach: $e")
                     sendStatus(ConnectResult(false,"Error rescanning USB: ${e.message}"))
                }
            }
            else -> {
                 // Handle other potential codes if necessary
                 println("Unknown connectListener code: $code, Info: $connInfo, Msg: $msg")
                 sendStatus(ConnectResult(false, "Unknown event: code=$code, msg=$msg"))
            }
        }
    }

    // Helper function to map status codes consistently
    private fun mapStatusCodeToString(status: Int): String {
        return when (status) {
            POSConst.STS_NORMAL -> "Normal status"
            POSConst.STS_COVEROPEN -> "Cover open"
            POSConst.STS_PAPEREMPTY -> "Paper empty"
            POSConst.STS_PRESS_FEED -> "Press the paper feed button"
            POSConst.STS_PRINTER_ERR -> "Printer error"
            -1 -> "Status check: Unknown errors"
            -3 -> "Status check: Connection disconnected"
            -4 -> "Status check: Receiving data timed out"
            else -> "Unknown status code: $status"
        }
    }
}
