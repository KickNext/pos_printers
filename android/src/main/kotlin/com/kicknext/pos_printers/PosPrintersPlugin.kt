package com.kicknext.pos_printers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.suspendCancellableCoroutine
import net.posprinter.*
import net.posprinter.model.AlgorithmType
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** PosPrintersPlugin */
class PosPrintersPlugin : FlutterPlugin, POSPrintersApi {

    private lateinit var applicationContext: Context
    private val posUdpNet = ExtendPosUdpNet()
    private lateinit var usbManager: UsbManager
    private val pluginScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var discoveryEventsApi: PrinterDiscoveryEventsApi

    private val connectionsMap = mutableMapOf<String, IDeviceConnection>()

    // Фильтр для поиска принтеров, передается из Dart
    private var discoveryFilter: PrinterDiscoveryFilter = PrinterDiscoveryFilter.ALL

    // Single receiver for USB attach/detach events
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            // Filter only USB printers by interface class
            val isPrinter = (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == 7 }
            if (!isPrinter) return
            // Obtain serial if available
            val serial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && usbManager.hasPermission(device)) {
                try { device.serialNumber } catch (_: Exception) { null }
            } else null
            val dto = DiscoveredPrinterDTO(
                id = "${device.vendorId}:${device.productId}:${serial ?: "null"}",
                type = PosPrinterConnectionType.USB,
                printerType = PrinterType.UNKNOWN,
                usbParams = UsbParams(
                    vendorId = device.vendorId.toLong(),
                    productId = device.productId.toLong(),
                    usbSerialNumber = serial,
                    manufacturer = device.manufacturerName,
                    productName = device.productName
                ),
                networkParams = null
            )
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> discoveryEventsApi.onPrinterAttached(dto) {}
                UsbManager.ACTION_USB_DEVICE_DETACHED -> discoveryEventsApi.onPrinterDetached(dto.id) {}
            }
        }
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        Log.d("POSPrinters", "onAttachedToEngine called")
        applicationContext = flutterPluginBinding.applicationContext
        POSPrintersApi.setUp(flutterPluginBinding.binaryMessenger, this)
        discoveryEventsApi = PrinterDiscoveryEventsApi(flutterPluginBinding.binaryMessenger)
        usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
        POSConnect.init(this.applicationContext) // Use applicationContext
        Log.d("POSPrinters", "POSConnect initialized")

        // Register USB attach/detach receiver
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        applicationContext.registerReceiver(usbReceiver, filter)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Log.d("POSPrinters", "onDetachedFromEngine called")
        POSPrintersApi.setUp(binding.binaryMessenger, null)
        pluginScope.cancel()
        connectionsMap.values.forEach { it.close() }
        connectionsMap.clear()
        Log.d("POSPrinters", "Plugin detached, connections closed, coroutine scope cancelled.")

        // Unregister USB receiver
        applicationContext.unregisterReceiver(usbReceiver)
    }

    private val networkDispatcher = Dispatchers.IO

    private inline fun <T> withConnectionOrError(
        printer: PrinterConnectionParams,
        errorMessage: String,
        callback: (Result<T>) -> Unit,
        block: (IDeviceConnection) -> Unit
    ) {
        val key = getConnectionKey(printer)
        val connection = connectionsMap[key]
        if (connection == null) {
            Log.w("POSPrinters", "No active connection found for key=$key")
            callback(Result.failure(Exception(errorMessage)))
            return
        }
        block(connection)
    }

    // Detect printer type by probing ESC/POS then ZPL
    private suspend fun detectType(raw: DiscoveredPrinterDTO): PrinterType = suspendCancellableCoroutine { cont ->
        // Build connection
        val connection: IDeviceConnection
        val target: String
        try {
            when (raw.type) {
                PosPrinterConnectionType.USB -> {
                    // Find USB device
                    val usb = findUsbDevice(raw.usbParams!!.vendorId.toInt(), raw.usbParams.productId.toInt(), raw.usbParams.usbSerialNumber)
                    if (usb == null) throw Exception("USB device not found")
                    connection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
                    target = usb.deviceName
                }
                PosPrinterConnectionType.NETWORK -> {
                    connection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
                    target = raw.networkParams!!.ipAddress
                }
            }
        } catch (e: Exception) {
            cont.resume(PrinterType.UNKNOWN)
            return@suspendCancellableCoroutine
        }
        // connect listener
        val listener = IConnectListener { code, _, _ ->
            if (code == POSConnect.CONNECT_SUCCESS) {
                // probe ESC/POS
                try {
                    val pos = POSPrinter(connection)
                    pos.initializePrinter()
                    pos.printerStatus { status ->
                        if (status >= POSConst.STS_NORMAL) {
                            connection.close()
                            cont.resume(PrinterType.ESCPOS)
                        } else {
                            // probe ZPL
                            val zpl = ZPLPrinter(connection)
                            zpl.printerStatus { zcode ->
                                val type = if (zcode in 0..0x80) PrinterType.ZPL else PrinterType.UNKNOWN
                                connection.close()
                                cont.resume(type)
                            }
                        }
                    }
                } catch (ex: Exception) {
                    connection.close()
                    cont.resume(PrinterType.UNKNOWN)
                }
            } else {
                // could not connect
                cont.resume(PrinterType.UNKNOWN)
            }
        }
        // initiate connect
        connection.connect(target, listener)
    }

    override fun findPrinters(filter: PrinterDiscoveryFilter) {
        discoveryFilter = filter
        Log.d("POSPrinters", "findPrinters (streaming) called with filter=$filter")
        pluginScope.launch {
            val foundPrinterIds = mutableSetOf<String>()
            var overallSuccess = true
            var firstError: Throwable? = null

            suspend fun sendPrinterFound(raw: DiscoveredPrinterDTO) {
                val type = detectType(raw)
                // apply filter
                if (discoveryFilter != PrinterDiscoveryFilter.ALL &&
                    (type == PrinterType.ESCPOS && discoveryFilter != PrinterDiscoveryFilter.ESCPOS ||
                     type == PrinterType.ZPL && discoveryFilter != PrinterDiscoveryFilter.ZPL)) {
                    return
                }
                val dto = DiscoveredPrinterDTO(
                    id = raw.id, type = raw.type, printerType = type,
                    usbParams = raw.usbParams, networkParams = raw.networkParams
                )
                withContext(Dispatchers.Main) {
                    try {
                        discoveryEventsApi.onPrinterFound(dto) {}
                        Log.d("POSPrinters", "Sent printer to Dart: ${dto.id}")
                    } catch (e: Exception) {
                        Log.e("POSPrinters", "Error sending printer ${dto.id} to Dart: ${e.message}", e)
                    }
                }
            }

            try {
                Log.d("POSPrinters", "Starting USB discovery phase...")
                try {
                    val usbPrinters = discoverUsbPrinters()
                    usbPrinters.forEach { sendPrinterFound(it) }
                    Log.d("POSPrinters", "USB discovery phase complete.")
                } catch (e: Exception) {
                    Log.e("POSPrinters", "Error during USB discovery phase: ${e.message}", e)
                    overallSuccess = false
                    firstError = e
                }
                ensureActive() // Check for cancellation between phases

                Log.d("POSPrinters", "Starting SDK Network discovery phase...")
                var sdkFoundIps: List<String> = emptyList()
                try {
                    val (sdkNetworkPrinters, ips) = discoverSdkNetworkPrinters()
                    sdkFoundIps = ips
                    sdkNetworkPrinters.forEach { sendPrinterFound(it) }
                    Log.d("POSPrinters", "SDK Network discovery phase complete.")
                } catch (e: Exception) {
                    Log.e("POSPrinters", "Error during SDK Network discovery phase: ${e.message}", e)
                    overallSuccess = false
                    if (firstError == null) firstError = e
                }
                ensureActive()

                Log.d("POSPrinters", "Starting TCP Network discovery phase...")
                try {
                    val tcpNetworkPrinters = discoverTcpNetworkPrinters(sdkFoundIps)
                    tcpNetworkPrinters.forEach { sendPrinterFound(it) }
                    Log.d("POSPrinters", "TCP Network discovery phase complete.")
                } catch (e: Exception) {
                    Log.e("POSPrinters", "Error during TCP Network discovery phase: ${e.message}", e)
                    overallSuccess = false
                    if (firstError == null) firstError = e
                }

            } catch (e: CancellationException) {
                Log.w("POSPrinters", "findPrinters (streaming) cancelled: ${e.message}")
                overallSuccess = false // Mark as unsuccessful if cancelled
                if (firstError == null) firstError = e // Record cancellation as error
                // No need to rethrow, finally block will handle completion call
            } catch (e: Throwable) {
                // Catch any other unexpected errors during the orchestration
                Log.e("POSPrinters", "Unexpected error during findPrinters (streaming): ${e.message}", e)
                overallSuccess = false
                if (firstError == null) firstError = e
            } finally {
                Log.i("POSPrinters", "findPrinters (streaming) finished. Overall Success: $overallSuccess. Total unique printers sent: ${foundPrinterIds.size}")
                withContext(Dispatchers.Main) {
                    try {
                        discoveryEventsApi.onDiscoveryComplete(overallSuccess, firstError?.message) {}
                    } catch (e: Exception) {
                        Log.e("POSPrinters", "Error sending discovery completion to Dart: ${e.message}", e)
                    }
                }
            }
        }
    }

    private suspend fun discoverUsbPrinters(): List<DiscoveredPrinterDTO> = withContext(networkDispatcher) {
        Log.d("POSPrinters", "Starting USB device scan...")
        val discovered = mutableListOf<DiscoveredPrinterDTO>()
        try {
            val usbDevices = usbManager.deviceList
            Log.d("POSPrinters", "Found ${usbDevices.size} USB devices total.")
            usbDevices.values.forEach { device ->
                ensureActive() // Check for coroutine cancellation
                var isLikelyPrinter = false
                try {
                    for (i in 0 until device.interfaceCount) {
                        val usbInterface = device.getInterface(i)
                        if (usbInterface.interfaceClass == 7) { // USB Printer class
                            isLikelyPrinter = true
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w("POSPrinters", "Error accessing interfaces for USB device ${device.deviceName}: ${e.message}")
                    // Continue to next device
                    return@forEach
                }

                if (isLikelyPrinter) {
                    Log.d("POSPrinters", "Device ${device.deviceName} (VID:${device.vendorId}, PID:${device.productId}) is likely a printer.")
                    var usbSerial: String? = null
                    // Only attempt to get serial if permission exists
                    // Check permission before trying to access potentially protected info like serial number
                    val hasPermission = usbManager.hasPermission(device)
                    if (hasPermission) {
                        try {
                            // serialNumber requires API 26 (Oreo)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                usbSerial = device.serialNumber
                            }
                        } catch (se: SecurityException) {
                            Log.w("POSPrinters", "SecurityException getting serial number for ${device.deviceName}: ${se.message}")
                        } catch (e: Exception) {
                            Log.e("POSPrinters", "Error getting serial number for ${device.deviceName}: ${e.message}")
                        }
                    } else {
                        // Log if needed, but don't prevent adding the printer
                        // Log.v("POSPrinters", "Cannot get USB serial for ${device.deviceName} due to missing permission.")
                    }

                    // Add printer even if serial couldn't be read
                    discovered.add(
                        DiscoveredPrinterDTO(
                            id = "${device.vendorId}:${device.productId}:${device.serialNumber}",
                            type = PosPrinterConnectionType.USB,
                            printerType = PrinterType.UNKNOWN,
                            usbParams = UsbParams(
                                vendorId = device.vendorId.toLong(),
                                productId = device.productId.toLong(),
                                usbSerialNumber = usbSerial,
                                manufacturer = device.manufacturerName,
                                productName = device.productName,
                                ),
                            networkParams = null,
                        )
                    )
                } else {
                    // Log.v("POSPrinters", "Device ${device.deviceName} (VID:${device.vendorId}, PID:${device.productId}) is likely NOT a printer (Interface class != 7).") // Verbose log
                }
            }
        } catch (e: Exception) {
            Log.e("POSPrinters", "Error during USB scan: ${e.message}", e)
            // Optionally rethrow or handle, returning potentially partial list
        }
        Log.d("POSPrinters", "USB scan finished. Discovered: ${discovered.size}")
        discovered
    }

    // Uses CompletableFuture to bridge callback-based SDK search with coroutines
    private suspend fun discoverSdkNetworkPrinters(): Pair<List<DiscoveredPrinterDTO>, List<String>> {
        Log.d("POSPrinters", "Starting SDK network device scan...")
        val future = CompletableFuture<Pair<List<DiscoveredPrinterDTO>, List<String>>>()
        val discoveredPrinters = mutableListOf<DiscoveredPrinterDTO>()
        val foundIps = mutableSetOf<String>() // Use Set for efficient IP checking

        val searchTimeoutMillis = 5000L // 5 seconds timeout for SDK search

        // Timeout handling using coroutine delay
        val timeoutJob = pluginScope.launch {
            delay(searchTimeoutMillis)
            if (!future.isDone) {
                Log.w("POSPrinters", "SDK network scan timed out after ${searchTimeoutMillis}ms.")
                future.complete(Pair(discoveredPrinters.toList(), foundIps.toList())) // Complete with whatever was found
            }
        }

        try {
            try {
                posUdpNet.searchNetDevice { netPrinter ->
                    // Check if future is already completed (e.g., by timeout)
                    if (future.isDone || netPrinter?.ipStr == null) return@searchNetDevice

                    val ip = netPrinter.ipStr
                    Log.d("POSPrinters", "SDK Network device found: IP=$ip, MAC=${netPrinter.macStr}")

                    // Add to list only if IP is new
                    if (foundIps.add(ip)) {
                        val printer = DiscoveredPrinterDTO(
                            id = ip,
                            type = PosPrinterConnectionType.NETWORK,
                            printerType = PrinterType.UNKNOWN,
                            usbParams = null,
                            networkParams = NetworkParams(
                                ipAddress = ip,
                                macAddress = netPrinter.macStr,
                                dhcp = netPrinter.isDhcp,
                                mask = netPrinter.maskStr,
                                gateway = netPrinter.gatewayStr
                                ,)
                        )
                        discoveredPrinters.add(printer)
                    }
                }
            } catch (e: java.io.IOException) {
                if (e.message?.contains("ENETUNREACH") == true) {
                    Log.w("POSPrinters", "Network is unreachable during SDK scan. Skip network discovery.")
                } else {
                    Log.w("POSPrinters", "I/O error during network scan: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w("POSPrinters", "Error during SDK network scan: ${e.javaClass.simpleName}: ${e.message}")
            }
            pluginScope.launch(networkDispatcher) {
                try {
                    val maxCheckTime = System.currentTimeMillis() + searchTimeoutMillis
                    while (posUdpNet.isSearch && !future.isDone && System.currentTimeMillis() < maxCheckTime) {
                        delay(200) // Check every 200ms
                    }
                    // If search finished and future is not done (by timeout), complete it.
                    if (!future.isDone) {
                        Log.d("POSPrinters", "SDK network scan finished naturally.")
                        future.complete(Pair(discoveredPrinters.toList(), foundIps.toList()))
                    }
                } catch (e: Exception) {
                    Log.w("POSPrinters", "Error checking network scan status: ${e.javaClass.simpleName}")
                    if (!future.isDone) {
                        future.complete(Pair(discoveredPrinters.toList(), foundIps.toList()))
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("POSPrinters", "Error starting SDK network scan: ${e.javaClass.simpleName}: ${e.message}")
            if (!future.isDone) {
                future.completeExceptionally(e) // Complete with error
            }
        }

        // Wait for future completion (either naturally, by timeout, or error) and clean up timeout job
        return try {
            future.await() // Suspending await extension function for CompletableFuture
        } finally {
            timeoutJob.cancel() // Ensure timeout coroutine is cancelled
        }
    }

    private suspend fun discoverTcpNetworkPrinters(excludeIps: List<String>): List<DiscoveredPrinterDTO> = withContext(networkDispatcher) {
        Log.d("POSPrinters", "Starting TCP network scan (port 9100), excluding ${excludeIps.size} IPs.")
        val discovered = mutableListOf<DiscoveredPrinterDTO>()
        val localNetworks = getLocalIpAddresses()
        val port = 9100
        val timeoutMs = 300 // Short timeout for TCP check
        val semaphore = Semaphore(100) // Limit concurrent checks to 100
        val excludeIpSet = excludeIps.toSet() // Use Set for faster lookups

        val jobs = mutableListOf<Job>()

        localNetworks.forEach { networkInfo ->
            ensureActive() // Check for cancellation before starting a network range
            Log.d("POSPrinters", "Scanning network: ${networkInfo.ipAddress}/${networkInfo.prefixLength}")
            val range = getIpRangeFromCidr(networkInfo.ipAddress, networkInfo.prefixLength)
            if (range != null) {
                range.forEach { ip ->
                    ensureActive() // Check for cancellation frequently during iteration
                    if (ip != networkInfo.ipAddress && !excludeIpSet.contains(ip)) {
                        jobs += pluginScope.launch(networkDispatcher) { // Launch in the correct scope/dispatcher
                            semaphore.acquire() // Limit concurrency
                            try {
                                ensureActive() // Check again before network call
                                if (isPortOpen(ip, port, timeoutMs)) {
                                    Log.i("POSPrinters", "TCP Port $port open found at: $ip")
                                    synchronized(discovered) { // Protect list access from concurrent adds
                                        discovered.add(
                                            DiscoveredPrinterDTO(
                                                id = ip,
                                                type = PosPrinterConnectionType.NETWORK,
                                                printerType = PrinterType.UNKNOWN,
                                                usbParams = null,
                                                networkParams = NetworkParams(
                                                    ipAddress = ip,
                                                    mask = null,
                                                    gateway = null,
                                                    dhcp = false,
                                                    macAddress = null
                                                ),
                                            )
                                        )
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e // Re-throw cancellation exceptions
                            } catch (e: Exception) {
                                // Log.v("POSPrinters", "TCP check failed for $ip: ${e.message}") // Verbose logging if needed
                            } finally {
                                semaphore.release()
                            }
                        }
                    }
                }
            } else {
                Log.w("POSPrinters", "Could not determine IP range for ${networkInfo.ipAddress}/${networkInfo.prefixLength}")
            }
        }

        try {
            jobs.joinAll() // Wait for all scanning jobs to complete
        } catch (e: CancellationException) {
            Log.w("POSPrinters", "TCP scan cancelled during joinAll.")
            // Ensure remaining jobs are cancelled if the scope is cancelled
            jobs.forEach { if (it.isActive) it.cancel() }
            throw e // Re-throw
        }
        Log.d("POSPrinters", "TCP scan finished. Discovered: ${discovered.size}")
        discovered
    }

    // Removed deduplicatePrinters function - deduplication now happens via foundPrinterIds set before sending to Dart

    // --- Network Utility Functions ---

    data class NetworkInfo(val ipAddress: String, val prefixLength: Short)

    // No need for ACCESS_NETWORK_STATE permission for NetworkInterface
    private fun getLocalIpAddresses(): List<NetworkInfo> {
        val networks = mutableListOf<NetworkInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (intf in interfaces) {
                // Filter out loopback, down interfaces, and potentially virtual interfaces
                if (!intf.isUp || intf.isLoopback || intf.displayName.contains("vir") || intf.displayName.contains("docker")) {
                    continue
                }
                intf.interfaceAddresses?.forEach { addr ->
                    val ip = addr.address
                    // Ensure it's IPv4 and has a reasonable prefix length
                    if (ip is Inet4Address && addr.networkPrefixLength in 1..31) {
                        ip.hostAddress?.let { NetworkInfo(it, addr.networkPrefixLength) }
                            ?.let { networks.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("POSPrinters", "Error getting local IP addresses: ${e.message}", e)
        }
        // Remove duplicates based on IP and prefix length
        val distinctNetworks = networks.distinct()
        Log.d("POSPrinters", "Found distinct local networks: $distinctNetworks")
        return distinctNetworks
    }

    private fun getIpRangeFromCidr(ipAddress: String, prefixLength: Short): Sequence<String>? {
        return try {
            val ipBytes = InetAddress.getByName(ipAddress).address
            if (ipBytes.size != 4) return null // Only IPv4

            // Convert IP bytes to integer
            val ipInt = ipBytes.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }

            // Calculate network and broadcast addresses
            val maskInt = (-1 shl (32 - prefixLength))
            val networkInt = ipInt and maskInt
            val broadcastInt = networkInt or (maskInt.inv())

            // Exclude network and broadcast addresses from the range
            val startIpInt = networkInt + 1
            val endIpInt = broadcastInt - 1

            if (startIpInt > endIpInt) return emptySequence() // Handle /31, /32 cases

            // Generate sequence of IP strings
            sequence {
                for (currentIpInt in startIpInt..endIpInt) {
                    val bytes = ByteArray(4)
                    bytes[0] = (currentIpInt shr 24 and 0xFF).toByte()
                    bytes[1] = (currentIpInt shr 16 and 0xFF).toByte()
                    bytes[2] = (currentIpInt shr 8 and 0xFF).toByte()
                    bytes[3] = (currentIpInt and 0xFF).toByte()
                    InetAddress.getByAddress(bytes).hostAddress?.let { yield(it) }
                }
            }
        } catch (e: Exception) {
            Log.e("POSPrinters", "Error calculating IP range for $ipAddress/$prefixLength: ${e.message}", e)
            null
        }
    }

    private suspend fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean = withContext(networkDispatcher) {
        // Log.v("POSPrinters", "TCP Check: $ip:$port") // Verbose
        var isReachable: Boolean
        var socket: Socket? = null
        try {
            socket = Socket()
            // connect() with timeout is crucial for non-blocking check
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            isReachable = true
        } catch (e: Exception) {
            // Expected exceptions: ConnectException, SocketTimeoutException, etc.
            // Log.v("POSPrinters", "TCP Check failed for $ip:$port: ${e.javaClass.simpleName}") // Verbose
            isReachable = false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // Ignore close exception
            }
        }
        isReachable
    }

    // Extension function to await CompletableFuture in coroutines
    private suspend fun <T> CompletableFuture<T>.await(): T =
        suspendCoroutine<T> { cont ->
            whenComplete { result, exception ->
                if (exception == null) {
                    cont.resume(result)
                } else {
                    cont.resumeWithException(exception)
                }
            }
        }

    private fun getConnectionKey(printer: PrinterConnectionParams): String {
        return when (printer.connectionType) {
            PosPrinterConnectionType.USB -> {
                "usb:${printer.usbParams!!.vendorId}:${printer.usbParams.productId}:${printer.usbParams.usbSerialNumber ?: "null"}"
            }
            PosPrinterConnectionType.NETWORK -> {
                "net:${printer.networkParams!!.ipAddress}"
            }
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
                            Log.w("POSPrinters", "findUsbDevice: No permission to check serial for ${device.deviceName}")
                            false
                        }
                    } catch (e: SecurityException) {
                        Log.w("POSPrinters", "findUsbDevice: SecurityException getting serial for ${device.deviceName}: ${e.message}")
                        false
                    } catch (e: Exception) {
                        Log.e("POSPrinters", "findUsbDevice: Error getting serial for ${device.deviceName}: ${e.message}")
                        false
                    }
                } else {
                    true
                }
            }
            vidMatch && pidMatch && serialMatch
        }
    }

    override fun connectPrinter(printer: PrinterConnectionParams, callback: (Result<Unit>) -> Unit) {
        Log.d("POSPrinters", "connectPrinter called for params: $printer")
        val key = getConnectionKey(printer)
        Log.d("POSPrinters", "Generated connection key: $key")

        try {

            connectionsMap[key]?.let {
                Log.d("POSPrinters", "Closing existing connection for key: $key")
                it.close()
                connectionsMap.remove(key)
            }

            val newConnection: IDeviceConnection
            val connectionTargetInfo: String

            when (printer.connectionType) {
                PosPrinterConnectionType.USB -> {

                    if (printer.usbParams == null) {
                        Log.e("POSPrinters", "Connect failed: Missing vendorId or productId for USB connection.")
                        callback(Result.failure(Exception("Missing vendorId or productId for USB connection")))
                        return
                    }

                    Log.d("POSPrinters", "Searching for USB device VID=${printer.usbParams.vendorId}, PID=${printer.usbParams.productId}, Serial=${printer.usbParams.usbSerialNumber}")
                    val usbDevice = findUsbDevice(printer.usbParams.vendorId.toInt(), printer.usbParams.productId.toInt(), printer.usbParams.usbSerialNumber)

                    if (usbDevice == null) {
                        Log.e("POSPrinters", "Connect failed: USB device VID=${printer.usbParams.vendorId}, PID=${printer.usbParams.productId}, Serial=${printer.usbParams.usbSerialNumber} not found.")
                        callback(Result.failure(Exception("USB device not found")))
                        return
                    }


                    if (!usbManager.hasPermission(usbDevice)) {
                        Log.e("POSPrinters", "Connect failed: USB permission denied for device ${usbDevice.deviceName}")

                        callback(Result.failure(Exception("USB permission denied")))
                        return
                    }

                    Log.d("POSPrinters", "Found matching USB device: ${usbDevice.deviceName}. Creating connection...")
                    newConnection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
                    connectionTargetInfo = usbDevice.deviceName
                }
                PosPrinterConnectionType.NETWORK -> {
                    if (printer.networkParams?.ipAddress == null) {
                        Log.e("POSPrinters", "Connect failed: Missing ipAddress for Network connection.")
                        callback(Result.failure(Exception("Missing ipAddress for Network connection")))
                        return
                    }
                    Log.d("POSPrinters", "Preparing network connection to ${printer.networkParams.ipAddress}")
                    newConnection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
                    connectionTargetInfo = printer.networkParams.ipAddress
                }

            }

            var replySubmitted = false
            val listener = IConnectListener { code, connInfo, msg ->
                Log.d("POSPrinters", "connectPrinter listener invoked: code=$code, info=$connInfo, msg=$msg")
                synchronized(this) {
                    if (replySubmitted) {
                        Log.w("POSPrinters", "connectPrinter listener called again (code=$code), ignoring as reply was already submitted.")
                        return@synchronized
                    }

                    when (code) {
                        POSConnect.CONNECT_SUCCESS -> {
                            Log.i("POSPrinters", "CONNECT_SUCCESS for key=$key, info=$connInfo")
                            connectionsMap[key] = newConnection
                            callback(Result.success(Unit))
                            replySubmitted = true
                        }
                        POSConnect.CONNECT_FAIL -> {
                            Log.w("POSPrinters", "CONNECT_FAIL for key=$key, info=$connInfo, msg=$msg")
                            connectionsMap.remove(key)
                            callback(Result.failure(Exception("Connection failed for $connInfo: $msg")))
                            replySubmitted = true
                        }
                        POSConnect.CONNECT_INTERRUPT -> {
                            Log.w("POSPrinters", "CONNECT_INTERRUPT for key=$key, info=$connInfo, msg=$msg")
                            connectionsMap.remove(key)
                            callback(Result.failure(Exception("Connection interrupted for $connInfo: $msg")))
                            replySubmitted = true
                        }

                    }
                }
            }

            Log.d("POSPrinters", "Initiating connection to '$connectionTargetInfo' with key '$key'...")
            newConnection.connect(connectionTargetInfo, listener)

        } catch (platformError: Throwable) {
            Log.e("POSPrinters", "Exception during connectPrinter setup for key=$key: ${platformError.message}", platformError)
            callback(Result.failure(Exception("Connection setup exception: ${platformError.message}")))
        }
    }

    override fun disconnectPrinter(
        printer: PrinterConnectionParams,
        callback: (Result<Unit>) -> Unit
    ) {
        Log.d("POSPrinters", "disconnectPrinter called for params: $printer")
        val key = getConnectionKey(printer)
        Log.d("POSPrinters", "Disconnecting using key: $key")

        try {
            val connection = connectionsMap[key]
            if (connection != null) {
                connection.close()
                Log.i("POSPrinters", "Disconnected and removed connection for key=$key")
                connectionsMap.remove(key)
                callback(Result.success(Unit))
            } else {
                Log.w("POSPrinters", "disconnectPrinter: No active connection found for key=$key")
                callback(Result.failure(Exception("No active connection found for key=$key")))
            }
        } catch (platformError: Throwable) {
            Log.e("POSPrinters", "Exception during disconnectPrinter for key=$key: ${platformError.message}", platformError)
            callback(Result.failure(Exception("Disconnect exception: ${platformError.message}")))
        }
    }

    override fun printData(
        printer: PrinterConnectionParams,
        data: ByteArray,
        width: Long,
        callback: (Result<Unit>) -> Unit
    ) {
        Log.d("POSPrinters", "printData called for type: ${printer.connectionType}, SN/IP: ${printer.usbParams?.usbSerialNumber ?: printer.networkParams?.ipAddress}, data size: ${data.size}")
        withConnectionOrError(
            printer,
            "No active connection found for key=${getConnectionKey(printer)}",
            callback
        ) { connection ->
            try {
                val curPrinter = POSPrinter(connection)
                Log.d("POSPrinters", "Initializing printer for raw data...")
                curPrinter.initializePrinter() // Ensure printer is in ESC/POS mode

                Log.d("POSPrinters", "Sending raw data...")
                curPrinter.sendData(data)
                
                Log.d("POSPrinters", "Raw data sent (assuming success).")
                // Return success immediately after sending, without waiting for status.
                // Status check can be done separately via getPrinterStatus if needed.
                callback(Result.success(Unit))
            } catch (platformError: Throwable) {
                Log.e("POSPrinters", "Exception during printData: ${platformError.message}", platformError)
                callback(Result.failure(Exception("Print data exception: ${platformError.message}")))
            }
        }
    }

    override fun printHTML(
        printer: PrinterConnectionParams,
        html: String,
        width: Long,
        callback: (Result<Unit>) -> Unit
    ) {
        Log.d("POSPrinters", "printHTML called for type: ${printer.connectionType}, SN/IP: ${printer.usbParams?.usbSerialNumber ?: printer.networkParams?.ipAddress}, width: $width")
        withConnectionOrError(
            printer,
            "No active connection found for key=${getConnectionKey(printer)}",
            callback
        ) { connection ->
            try {
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

                curPrinter.printBitmap(bitmap, POSConst.ALIGNMENT_LEFT, width.toInt())

                curPrinter.cutHalfAndFeed(1)
                
                Log.d("POSPrinters", "Bitmap printed and cut (assuming success).")
                // Return success immediately after sending commands, without waiting for status.
                callback(Result.success(Unit))
            } catch (platformError: Throwable) {
                Log.e("POSPrinters", "Exception during printHTML: ${platformError.message}", platformError)
                callback(Result.failure(Exception("Print HTML exception: ${platformError.message}")))
            }
        }
    }

    override fun openCashBox(printer: PrinterConnectionParams, callback: (Result<Unit>) -> Unit) {
        Log.d("POSPrinters", "openCashBox called for type: ${printer.connectionType}, SN/IP: ${printer.usbParams?.usbSerialNumber ?: printer.networkParams?.ipAddress}")
        withConnectionOrError(
            printer,
            "No active connection found for key=${getConnectionKey(printer)}",
            callback
        ) { connection ->
            try {
                val curPrinter = POSPrinter(connection)
                Log.d("POSPrinters", "Initializing printer for open cash box...")
                curPrinter.initializePrinter() // Ensure printer is in ESC/POS mode
                // Assume success if no exception
                Log.d("POSPrinters", "Sending open cash box command...")
                curPrinter.openCashBox(POSConst.PIN_TWO)
                Log.d("POSPrinters", "Open cash box command sent (assumed success).")
                // Status check might not be relevant/reliable immediately after cashbox open
                callback(Result.success(Unit))
            } catch (platformError: Throwable) {
                Log.e("POSPrinters", "Exception during openCashBox: ${platformError.message}", platformError)
                callback(Result.failure(Exception("Open cash box exception: ${platformError.message}")))
            }
        }
    }

    override fun getPrinterStatus(printer: PrinterConnectionParams, callback: (Result<StatusResult>) -> Unit) {
        withConnectionOrError(
            printer,
            "No active connection found for key=${getConnectionKey(printer)}",
            callback
        ) { connection ->
            try {
                val pos = POSPrinter(connection)
                Log.d("POSPrinters", "Initializing printer and requesting status...")
                pos.initializePrinter() // Ensure printer is in ESC/POS mode before status check
                pos.printerStatus { status ->
                    Log.d("POSPrinters", "getPrinterStatus callback received: status code = $status")
                    val text = mapStatusCodeToString(status) // Use helper function
                    // Check if status indicates an error state based on the text mapping
                    val isErrorStatus = status < POSConst.STS_NORMAL || status == POSConst.STS_PRINTER_ERR
                    Log.d("POSPrinters", "Mapped status: '$text', IsError: $isErrorStatus")
                    if (isErrorStatus) {
                        callback(Result.failure(Exception(text)))
                    } else {
                        callback(Result.success(StatusResult(success = true, status = text)))
                    }
                }
            } catch (platformError: Throwable) {
                Log.e("POSPrinters", "Exception during getPrinterStatus: ${platformError.message}", platformError)
                callback(Result.failure(Exception("Get status exception: ${platformError.message}")))
            }
        }
    }

    override fun getPrinterSN(printer: PrinterConnectionParams, callback: (Result<StringResult>) -> Unit) {
        withConnectionOrError(
            printer,
            "No active connection found for key=${getConnectionKey(printer)}",
            callback
        ) { connection ->
            try {
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
                    if (snString.isEmpty() || snString == "Error decoding SN") {
                        callback(Result.failure(Exception("Failed to decode serial number: $snString")))
                    } else {
                        callback(Result.success(StringResult(success = true, value = snString)))
                    }
                }
            } catch (platformError: Throwable) {
                Log.e("POSPrinters", "Exception during getPrinterSN: ${platformError.message}", platformError)
                callback(Result.failure(Exception("Get SN exception: ${platformError.message}")))
            }
        }
    }

    override fun setNetSettingsToPrinter(
        printer: PrinterConnectionParams,
        netSettings: NetSettingsDTO,
        callback: (Result<Unit>) -> Unit
    ) {
        Log.d("POSPrinters", "setNetSettingsToPrinter called for params: $printer, settings: $netSettings")
        try {
            val ip = parseData(netSettings.ipAddress)
            val mask = parseData(netSettings.mask)
            val gateway = parseData(netSettings.gateway)
            if (ip == null || mask == null || gateway == null) {
                Log.e("POSPrinters", "setNetSettingsToPrinter: Invalid IP/Mask/Gateway format in NetSettingsDTO")
                callback(Result.failure(Exception("Invalid IP/Mask/Gateway format")))
                return
            }
            val dhcp = netSettings.dhcp


            val newPrinterConnection: IDeviceConnection
            val connectionTargetInfo: String

            when (printer.connectionType) {
                PosPrinterConnectionType.USB -> {

                    if (printer.usbParams == null) {
                        Log.e("POSPrinters", "setNetSettingsToPrinter failed: Missing vendorId or productId for USB connection.")
                        callback(Result.failure(Exception("Missing vendorId or productId for USB connection.")))
                        return
                    }

                    Log.d("POSPrinters", "setNetSettingsToPrinter: Searching for USB device VID=${printer.usbParams.vendorId}, PID=${printer.usbParams.productId}, Serial=${printer.usbParams.usbSerialNumber}")
                    val usbDevice = findUsbDevice(printer.usbParams.vendorId.toInt(), printer.usbParams.productId.toInt(), printer.usbParams.usbSerialNumber)

                    if (usbDevice == null) {
                        Log.e("POSPrinters", "setNetSettingsToPrinter failed: USB device VID=${printer.usbParams.vendorId}, PID=${printer.usbParams.productId}, Serial=${printer.usbParams.usbSerialNumber} not found.")
                        callback(Result.failure(Exception("USB device not found (VID=${printer.usbParams.vendorId}, PID=${printer.usbParams.productId})")))
                        return
                    }


                    if (!usbManager.hasPermission(usbDevice)) {
                        Log.e("POSPrinters", "setNetSettingsToPrinter failed: USB permission denied for device ${usbDevice.deviceName}")
                        callback(Result.failure(Exception("USB permission denied for device ${usbDevice.deviceName}")))
                        return
                    }

                    Log.d("POSPrinters", "setNetSettingsToPrinter: Found matching USB device: ${usbDevice.deviceName}. Creating temp connection...")
                    newPrinterConnection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
                    connectionTargetInfo = usbDevice.deviceName
                }
                PosPrinterConnectionType.NETWORK -> {
                    if (printer.networkParams == null) {
                        Log.e("POSPrinters", "setNetSettingsToPrinter failed: Missing ipAddress for Network connection.")
                        callback(Result.failure(Exception("Missing ipAddress for Network connection.")))
                        return
                    }
                    Log.d("POSPrinters", "setNetSettingsToPrinter: Preparing temp network connection to ${printer.networkParams.ipAddress}")
                    newPrinterConnection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
                    connectionTargetInfo = printer.networkParams.ipAddress
                }
            }

            Log.d("POSPrinters", "Setting up temporary connection to '$connectionTargetInfo' for net settings...")
            var replySubmitted = false
            val handler = IConnectListener { code, connInfo, msg ->
                Log.d("POSPrinters", "setNetSettings listener invoked: code=$code, info=$connInfo, msg=$msg")
                synchronized(this) {
                    if (replySubmitted) {
                        Log.w("POSPrinters", "setNetSettings listener called again (code=$code), ignoring.")
                        return@synchronized
                    }
                    when (code) {
                        POSConnect.CONNECT_SUCCESS -> {
                            Log.i("POSPrinters", "Temp connection successful to $connInfo for net settings. Applying settings...")
                            try {
                                val p = POSPrinter(newPrinterConnection)
                                p.setNetAll(ip, mask, gateway, dhcp)
                                Log.i("POSPrinters", "Net settings applied via $connInfo (assumed success). Closing temp connection after delay.")
                                Handler(Looper.getMainLooper()).postDelayed({

                                    if (!replySubmitted) {
                                        callback(Result.success(Unit))
                                        replySubmitted = true
                                    }
                                    newPrinterConnection.close()
                                    Log.d("POSPrinters", "Temp connection to $connInfo closed after net settings.")
                                }, 1000)
                            } catch (e: Exception) {
                                Log.e("POSPrinters", "Error applying net settings via $connInfo: ${e.message}", e)
                                if (!replySubmitted) {
                                    callback(Result.failure(Exception("Error applying net settings: ${e.message}")))
                                    replySubmitted = true
                                }
                                newPrinterConnection.close()
                            }
                        }
                        POSConnect.CONNECT_FAIL, POSConnect.CONNECT_INTERRUPT, POSConnect.SEND_FAIL -> {
                            Log.w("POSPrinters", "Temp connection/send failed during net settings (Code: $code, Info: $connInfo, Msg: $msg)")
                            if (!replySubmitted) {
                                val errorMsg = when(code) {
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
                            Log.w("POSPrinters", "Unknown status code in setNetSettings listener: $code, Info: $connInfo, Msg: $msg")
                            if (!replySubmitted) {
                                callback(Result.failure(Exception("Unknown status ($code) during net settings: $msg")))
                                replySubmitted = true
                            }
                        }
                    }
                } // end synchronized
            }

            newPrinterConnection.connect(connectionTargetInfo, handler)

        } catch (platformError: Throwable) {
            Log.e("POSPrinters", "Exception during setNetSettingsToPrinter setup: ${platformError.message}", platformError)
            callback(Result.failure(Exception("Set net settings exception: ${platformError.message}")))
        }
    }

    override fun configureNetViaUDP(
        macAddress: String,
        netSettings: NetSettingsDTO,
        callback: (Result<Unit>) -> Unit
    ) {
        Log.d("POSPrinters", "configureNetViaUDP called for MAC: $macAddress, Settings: IP=${netSettings.ipAddress}, Mask=${netSettings.mask}, GW=${netSettings.gateway}, DHCP=${netSettings.dhcp}")
        try {
            val macBytes = parseMacAddress(macAddress)
            if (macBytes == null) {
                Log.e("POSPrinters", "configureNetViaUDP: Invalid MAC address format: $macAddress")
                return callback(Result.failure(Exception("Invalid MAC address format")))
            }

            val ipBytes = parseData(netSettings.ipAddress)
            val maskBytes = parseData(netSettings.mask)
            val gatewayBytes = parseData(netSettings.gateway)

            if (ipBytes == null || maskBytes == null || gatewayBytes == null) {
                Log.e("POSPrinters", "configureNetViaUDP: Invalid IP/Mask/Gateway format")
                return callback(Result.failure(Exception("Invalid IP/Mask/Gateway format")))
            }

            Log.d("POSPrinters", "Calling posUdpNet.udpNetConfig...")
            posUdpNet.udpNetConfig(macBytes, ipBytes, maskBytes, gatewayBytes, netSettings.dhcp)
            Log.i("POSPrinters", "udpNetConfig command sent successfully for MAC: $macAddress")
            callback(Result.success(Unit))

        } catch (e: Throwable) {
            Log.e("POSPrinters", "Exception during configureNetViaUDP for MAC $macAddress: ${e.message}", e)
            callback(Result.failure(Exception("Configure net via UDP exception: ${e.message}")))
        }
    }

    override fun printLabelData(
        printer: PrinterConnectionParams,
        language: LabelPrinterLanguage,
        labelCommands: ByteArray,
        width: Long, // Note: width might be ignored for raw label commands
        callback: (Result<Unit>) -> Unit
    ) {
        withConnectionOrError(
            printer,
            "No active connection found.",
            callback
        ) { connection ->
            try {
                when (language) {
                    LabelPrinterLanguage.CPCL -> {
                        val cpcl = CPCLPrinter(connection)

                        Log.d("POSPrinters", "Sending CPCL data...")

                        cpcl.sendData(labelCommands)
                        Log.d("POSPrinters", "CPCL data sent (assumed success).")
                    }
                    LabelPrinterLanguage.TSPL -> {
                        val tspl = TSPLPrinter(connection)
                        Log.d("POSPrinters", "Sending TSPL data...")

                        tspl.sendData(labelCommands)
                        Log.d("POSPrinters", "TSPL data sent (assumed success).")
                    }
                    LabelPrinterLanguage.ZPL -> {
                        Log.d("POSPrinters", "Sending ZPL data...")
                        val zpl = ZPLPrinter(connection)
                        zpl.sendData(labelCommands)
                        Log.d("POSPrinters", "ZPL data sent (assumed success).")
                    }
                }
                // Assume success if no exception during sendData
                callback(Result.success(Unit))
            } catch (e: Throwable) {
                Log.e("POSPrinters", "Exception during printLabelData: ${e.message}", e)
                callback(Result.failure(Exception("Print label data exception: ${e.message}")))
            }
        }
    }

    override fun printLabelHTML(
        printer: PrinterConnectionParams,
        language: LabelPrinterLanguage,
        html: String,
        width: Long,
        height: Long,
        callback: (Result<Unit>) -> Unit
    ) {
        withConnectionOrError(
            printer,
            "No active connection found.",
            callback
        ) { connection ->
            try {
                Log.d("POSPrinters", "Generating bitmap from HTML for label...")
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

                when (language) {
                    LabelPrinterLanguage.CPCL -> {
                        val cpcl = CPCLPrinter(connection)

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
                        zpl.printBmpCompress(0, 0, bmp, width.toInt(), AlgorithmType.Dithering)
                        zpl.addEnd()
                        Log.d("POSPrinters", "ZPL label print commands sent.")
                    }
                }
                // Assume success if no exception during bitmap creation or printing
                callback(Result.success(Unit))
            } catch (e: Throwable) {
                Log.e("POSPrinters", "Exception during printLabelHTML: ${e.message}", e)
                callback(Result.failure(Exception("Print label HTML exception: ${e.message}")))
            }
        }
    }

    // Получить статус ZPL‑принтера (коды 00–80)
    override fun getZPLPrinterStatus(
        printer: PrinterConnectionParams,
        callback: (Result<ZPLStatusResult>) -> Unit
    ) {
        withConnectionOrError(
            printer,
            "No active connection found for key=${getConnectionKey(printer)}",
            callback
        ) { connection ->
            try {
                val zpl = ZPLPrinter(connection)
                zpl.printerStatus { code ->
                    val success = code in 0..0x80
                    val result = if (success) {
                        ZPLStatusResult(true, code.toLong(), null)
                    } else {
                        ZPLStatusResult(false, code.toLong(), "ZPL status code $code")
                    }
                    callback(Result.success(result))
                }
            } catch (e: Throwable) {
                callback(Result.failure(Exception("Get ZPL status exception: ${e.message}")))
            }
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

    // Helper to parse MAC address string (e.g., "00:1A:2B:3C:4D:5E") to ByteArray
    private fun parseMacAddress(mac: String): ByteArray? {
        val parts = mac.split(":", "-") // Allow both : and - as separators
        if (parts.size != 6) {
            return null
        }
        return try {
            parts.map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: NumberFormatException) {
            null
        }
    }

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
