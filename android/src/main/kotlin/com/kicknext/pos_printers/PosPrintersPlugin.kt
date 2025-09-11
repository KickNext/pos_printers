package com.kicknext.pos_printers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.izettle.html2bitmap.Html2Bitmap
import com.izettle.html2bitmap.content.WebViewContent
import com.kicknext.pos_printers.gen.*
import com.kicknext.pos_printers.discovery.UsbPrinterDiscovery
import com.kicknext.pos_printers.discovery.SdkPrinterDiscovery
import com.kicknext.pos_printers.discovery.TcpPrinterDiscovery
import com.kicknext.pos_printers.connection.PrinterConnectionManager
import com.kicknext.pos_printers.printer.PrinterOperations
import com.kicknext.pos_printers.network.UdpNetworkManager
import com.kicknext.pos_printers.validation.ParameterValidator
import io.flutter.embedding.engine.plugins.FlutterPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.posprinter.POSConnect

/** 
 * Refactored PosPrintersPlugin with improved architecture and error handling
 */
class PosPrintersPlugin : FlutterPlugin, POSPrintersApi {

    companion object {
        private const val TAG = "PosPrintersPlugin"
    }

    private lateinit var applicationContext: Context
    private lateinit var usbManager: UsbManager
    private lateinit var discoveryEventsApi: PrinterDiscoveryEventsApi
    
    // Refactored components
    private lateinit var connectionManager: PrinterConnectionManager
    private lateinit var printerOperations: PrinterOperations
    private lateinit var udpNetworkManager: UdpNetworkManager
    
    private val pluginScope = CoroutineScope(Dispatchers.IO)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            
            if (!Utils.isUsbPrinter(device)) return
            
            val serial = Utils.getUsbSerialNumber(device, usbManager)
            val printerDto = PrinterConnectionParamsDTO(
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
            
            try {
                when (action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        Log.d(TAG, "USB printer attached: ${device.deviceName}")
                        discoveryEventsApi.onPrinterAttached(printerDto) {}
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        Log.d(TAG, "USB printer detached: ${device.deviceName}")
                        discoveryEventsApi.onPrinterDetached(printerDto) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error handling USB device event: ${e.message}")
            }
        }
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = flutterPluginBinding.applicationContext
        usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
        
        // Initialize SDK
        POSConnect.init(applicationContext)
        
        // Initialize refactored components
        connectionManager = PrinterConnectionManager(usbManager)
        printerOperations = PrinterOperations(applicationContext)
        udpNetworkManager = UdpNetworkManager()
        
        // Set up API interfaces
        POSPrintersApi.setUp(flutterPluginBinding.binaryMessenger, this)
        discoveryEventsApi = PrinterDiscoveryEventsApi(flutterPluginBinding.binaryMessenger)
        
        // Register USB device receiver
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        applicationContext.registerReceiver(usbReceiver, filter)
        
        Log.d(TAG, "PosPrintersPlugin initialized successfully")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        try {
            POSPrintersApi.setUp(binding.binaryMessenger, null)
            applicationContext.unregisterReceiver(usbReceiver)
            Log.d(TAG, "PosPrintersPlugin detached successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during plugin detachment: ${e.message}")
        }
    }

    override fun configureNetViaUDP(netSettings: NetworkParams, callback: (Result<Unit>) -> Unit) {
        pluginScope.launch {
            try {
                ParameterValidator.validateNetworkSettings(netSettings)
                udpNetworkManager.configureNetworkViaUdp(netSettings)
                callback(Result.success(Unit))
            } catch (e: Exception) {
                Log.e(TAG, "UDP network configuration failed", e)
                callback(Result.failure(Exception("Configure net via UDP failed: ${e.message}")))
            }
        }
    }

    override fun printData(
        printer: PrinterConnectionParamsDTO,
        data: ByteArray,
        width: Long,
        callback: (Result<Unit>) -> Unit
    ) {
        try {
            ParameterValidator.validatePrinterConnection(printer)
            ParameterValidator.validatePrintData(data, width)
            
            connectionManager.executeWithSuspendPrintCompletion(printer, { connection ->
                printerOperations.printRawData(connection, data, width)
            }, callback)
            
        } catch (e: Exception) {
            Log.e(TAG, "Print data validation failed", e)
            callback(Result.failure(Exception("Print data validation failed: ${e.message}")))
        }
    }

    override fun printHTML(
        printer: PrinterConnectionParamsDTO,
        html: String,
        width: Long,
        callback: (Result<Unit>) -> Unit
    ) {
        try {
            ParameterValidator.validatePrinterConnection(printer)
            ParameterValidator.validateHtmlContent(html, width)
            
            // Simple HTML to Bitmap conversion on main thread - like original
            val content = WebViewContent.html(html)
            val bitmap = Html2Bitmap.Builder()
                .setBitmapWidth(width.toInt())
                .setContent(content)
                .setTextZoom(100)
                .setContext(applicationContext)
                .build()
                .bitmap
            
            if (bitmap == null) {
                callback(Result.failure(Exception("Failed to generate bitmap from HTML")))
                return
            }
            
            connectionManager.executeWithPrintCompletion(printer, { connection ->
                val curPrinter = net.posprinter.POSPrinter(connection)
                curPrinter.initializePrinter()
                curPrinter.printBitmap(bitmap, net.posprinter.POSConst.ALIGNMENT_LEFT, width.toInt())
                curPrinter.cutHalfAndFeed(1)
            }, callback)
            
        } catch (e: Exception) {
            Log.e(TAG, "Print HTML validation failed", e)
            callback(Result.failure(Exception("Print HTML validation failed: ${e.message}")))
        }
    }

    override fun openCashBox(
        printer: PrinterConnectionParamsDTO, 
        callback: (Result<Unit>) -> Unit
    ) {
        pluginScope.launch {
            try {
                ParameterValidator.validatePrinterConnection(printer)
                
                connectionManager.executeWithConnection(printer) { connection ->
                    pluginScope.launch {
                        try {
                            printerOperations.openCashBox(connection)
                            callback(Result.success(Unit))
                        } catch (e: Exception) {
                            Log.e(TAG, "Open cash box failed", e)
                            callback(Result.failure(Exception("Open cash box failed: ${e.message}")))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Open cash box validation failed", e)
                callback(Result.failure(Exception("Open cash box validation failed: ${e.message}")))
            }
        }
    }

    override fun printZplRawData(
        printer: PrinterConnectionParamsDTO,
        labelCommands: ByteArray,
        width: Long,
        callback: (Result<Unit>) -> Unit
    ) {
        try {
            ParameterValidator.validatePrinterConnection(printer)
            ParameterValidator.validatePrintData(labelCommands, width)
            
            connectionManager.executeWithSuspendPrintCompletion(printer, { connection ->
                printerOperations.printZplRawData(connection, labelCommands, width)
            }, callback)
            
        } catch (e: Exception) {
            Log.e(TAG, "Print ZPL raw data validation failed", e)
            callback(Result.failure(Exception("Print ZPL raw data validation failed: ${e.message}")))
        }
    }

    override fun printZplHtml(
        printer: PrinterConnectionParamsDTO,
        html: String,
        width: Long,
        callback: (Result<Unit>) -> Unit
    ) {
        try {
            ParameterValidator.validatePrinterConnection(printer)
            ParameterValidator.validateHtmlContent(html, width)
            
            // Simple HTML to Bitmap conversion - like original
            val content = WebViewContent.html(html)
            val bitmap = Html2Bitmap.Builder()
                .setBitmapWidth(width.toInt())
                .setContent(content)
                .setTextZoom(100)
                .setStrictMode(true)
                .setContext(applicationContext)
                .build()
                .bitmap
            
            if (bitmap == null) {
                callback(Result.failure(Exception("Failed to generate bitmap from HTML")))
                return
            }
            
            connectionManager.executeWithPrintCompletion(printer, { connection ->
                val zplPrinter = net.posprinter.ZPLPrinter(connection)
                zplPrinter.setPrinterWidth(width.toInt())
                zplPrinter.addStart()
                zplPrinter.printBmpCompress(0, 0, bitmap, width.toInt(), net.posprinter.model.AlgorithmType.Dithering)
                zplPrinter.addEnd()
            }, callback)
            
        } catch (e: Exception) {
            Log.e(TAG, "Print ZPL HTML validation failed", e)
            callback(Result.failure(Exception("Print ZPL HTML validation failed: ${e.message}")))
        }
    }

    override fun getZPLPrinterStatus(
        printer: PrinterConnectionParamsDTO, 
        callback: (Result<ZPLStatusResult>) -> Unit
    ) {
        pluginScope.launch {
            try {
                ParameterValidator.validatePrinterConnection(printer)
                
                connectionManager.executeWithConnection(printer) { connection ->
                    pluginScope.launch {
                        try {
                            val result = printerOperations.getZplPrinterStatus(connection)
                            callback(Result.success(result))
                        } catch (e: Exception) {
                            Log.e(TAG, "Get ZPL printer status failed", e)
                            val errorResult = ZPLStatusResult(
                                success = false,
                                code = -1,
                                errorMessage = "Get ZPL status failed: ${e.message}"
                            )
                            callback(Result.success(errorResult))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Get ZPL printer status validation failed", e)
                val errorResult = ZPLStatusResult(
                    success = false,
                    code = -1,
                    errorMessage = "Validation failed: ${e.message}"
                )
                callback(Result.success(errorResult))
            }
        }
    }

    override fun getPrinterSN(
        printer: PrinterConnectionParamsDTO, 
        callback: (Result<StringResult>) -> Unit
    ) {
        pluginScope.launch {
            try {
                ParameterValidator.validatePrinterConnection(printer)
                
                connectionManager.executeWithConnection(printer) { connection ->
                    pluginScope.launch {
                        try {
                            val result = printerOperations.getPrinterSerialNumber(connection)
                            callback(Result.success(result))
                        } catch (e: Exception) {
                            Log.e(TAG, "Get printer SN failed", e)
                            callback(Result.failure(Exception("Get printer SN failed: ${e.message}")))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Get printer SN validation failed", e)
                callback(Result.failure(Exception("Get printer SN validation failed: ${e.message}")))
            }
        }
    }

    override fun getPrinterStatus(
        printer: PrinterConnectionParamsDTO,
        callback: (Result<StatusResult>) -> Unit
    ) {
        pluginScope.launch {
            try {
                ParameterValidator.validatePrinterConnection(printer)
                
                connectionManager.executeWithConnection(printer) { connection ->
                    pluginScope.launch {
                        try {
                            val result = printerOperations.getPrinterStatus(connection)
                            callback(Result.success(result))
                        } catch (e: Exception) {
                            Log.e(TAG, "Get printer status failed", e)
                            val errorResult = StatusResult(
                                success = false,
                                status = "Error: ${e.message}",
                                errorMessage = "Failed to get printer status: ${e.message}"
                            )
                            callback(Result.success(errorResult))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Get printer status validation failed", e)
                val errorResult = StatusResult(
                    success = false,
                    status = "Validation error",
                    errorMessage = "Validation failed: ${e.message}"
                )
                callback(Result.success(errorResult))
            }
        }
    }

    override fun checkPrinterLanguage(
        printer: PrinterConnectionParamsDTO,
        callback: (Result<CheckPrinterLanguageResponse>) -> Unit
    ) {
        pluginScope.launch {
            try {
                ParameterValidator.validatePrinterConnection(printer)
                
                connectionManager.executeWithConnection(printer) { connection ->
                    pluginScope.launch {
                        try {
                            val language = printerOperations.detectPrinterLanguage(connection)
                            val response = CheckPrinterLanguageResponse(language, printer)
                            callback(Result.success(response))
                        } catch (e: Exception) {
                            Log.e(TAG, "Check printer language failed", e)
                            callback(Result.failure(Exception("Check printer language failed: ${e.message}")))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Check printer language validation failed", e)
                callback(Result.failure(Exception("Check printer language validation failed: ${e.message}")))
            }
        }
    }

    override fun setNetSettingsToPrinter(
        printer: PrinterConnectionParamsDTO,
        netSettings: NetworkParams,
        callback: (Result<Unit>) -> Unit
    ) {
        pluginScope.launch {
            try {
                ParameterValidator.validatePrinterConnection(printer)
                ParameterValidator.validateNetworkSettings(netSettings)
                
                connectionManager.executeWithConnection(printer) { connection ->
                    pluginScope.launch {
                        try {
                            printerOperations.setNetworkSettings(connection, netSettings)
                            callback(Result.success(Unit))
                        } catch (e: Exception) {
                            Log.e(TAG, "Set network settings failed", e)
                            callback(Result.failure(Exception("Set network settings failed: ${e.message}")))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Set network settings validation failed", e)
                callback(Result.failure(Exception("Set network settings validation failed: ${e.message}")))
            }
        }
    }

    override fun startDiscoverAllUsbPrinters(callback: (Result<Unit>) -> Unit) {
        pluginScope.launch {
            try {
                val discovery = UsbPrinterDiscovery(usbManager)
                discovery.discover(
                    onPrinterFound = { printer ->
                        Log.d(TAG, "USB printer found: ${printer.id}")
                        try {
                            discoveryEventsApi.onPrinterFound(printer) {}
                        } catch (e: Exception) {
                            Log.w(TAG, "Error notifying printer found: ${e.message}")
                        }
                    },
                    onFinish = {
                        Log.d(TAG, "USB printer discovery completed")
                        callback(Result.success(Unit))
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "USB printer discovery failed", e)
                callback(Result.failure(Exception("USB printer discovery failed: ${e.message}")))
            }
        }
    }

    override fun startDiscoveryXprinterSDKNetworkPrinters(callback: (Result<Unit>) -> Unit) {
        pluginScope.launch {
            try {
                val discovery = SdkPrinterDiscovery()
                discovery.discover(
                    onPrinterFound = { printer ->
                        Log.d(TAG, "SDK printer found: ${printer.id}")
                        try {
                            discoveryEventsApi.onPrinterFound(printer) {}
                        } catch (e: Exception) {
                            Log.w(TAG, "Error notifying printer found: ${e.message}")
                        }
                    },
                    onFinish = {
                        Log.d(TAG, "SDK printer discovery completed")
                        callback(Result.success(Unit))
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "SDK printer discovery failed", e)
                callback(Result.failure(Exception("SDK printer discovery failed: ${e.message}")))
            }
        }
    }

    override fun startDiscoveryTCPNetworkPrinters(port: Long, callback: (Result<Unit>) -> Unit) {
        pluginScope.launch {
            try {
                ParameterValidator.validateTcpPort(port)
                val discovery = TcpPrinterDiscovery()
                discovery.discover(
                    onPrinterFound = { printer ->
                        Log.d(TAG, "TCP printer found: ${printer.id}")
                        try {
                            discoveryEventsApi.onPrinterFound(printer) {}
                        } catch (e: Exception) {
                            Log.w(TAG, "Error notifying printer found: ${e.message}")
                        }
                    },
                    onFinish = {
                        Log.d(TAG, "TCP printer discovery completed")
                        callback(Result.success(Unit))
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "TCP printer discovery failed", e)
                callback(Result.failure(Exception("TCP printer discovery failed: ${e.message}")))
            }
        }
    }
}
