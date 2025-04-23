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

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return

            // Basic check for printer interface class
            val isPrinter =
                (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == 7 } // 7 is INTERFACE_CLASS_PRINTER
            if (!isPrinter) {
                // Log.v("POSPrinters", "USB device ${device.deviceName} is not a printer (interface class != 7).")
                return
            }
            Log.d("POSPrinters", "USB device event: Action=$action, Device=${device.deviceName}, VID=${device.vendorId}, PID=${device.productId}")


            // Attempt to get serial number only if permission is granted (important for Android >= O)
            // Note: Permission might not be granted immediately upon attachment.
            // Language detection might fail if permission is missing.
            val hasPermission = usbManager.hasPermission(device)
            val serial = if (hasPermission) {
                try {
                    device.serialNumber
                } catch (e: SecurityException) {
                    Log.w("POSPrinters", "SecurityException getting serial number for ${device.deviceName}. Permission likely missing.")
                    null
                } catch (e: Exception) {
                    Log.w("POSPrinters", "Exception getting serial number for ${device.deviceName}: ${e.message}")
                    null // Catch other potential exceptions
                }
            } else {
                Log.w("POSPrinters", "No permission for USB device ${device.deviceName} yet.")
                null
            }

            // Construct the base DTO
            val baseDto = DiscoveredPrinterDTO(
                id = "${device.vendorId}:${device.productId}:${serial ?: "null"}", // Use "null" string if serial is unavailable
                connectionParams = PrinterConnectionParams(
                    connectionType = PosPrinterConnectionType.USB,
                    usbParams = UsbParams(
                        vendorId = device.vendorId.toLong(),
                        productId = device.productId.toLong(),
                        usbSerialNumber = serial, // Store null if unavailable
                        manufacturer = device.manufacturerName,
                        productName = device.productName
                    ),
                    networkParams = null
                ),
                printerLanguage = null, // Language will be detected asynchronously
            )


            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i("POSPrinters", "USB Printer Attached: ${baseDto.id}")
                    // Launch a coroutine to detect the language asynchronously
                    pluginScope.launch {
                        var detectedLanguage: PrinterLanguage? = null
                        if (hasPermission) { // Only attempt detection if we have permission
                            try {
                                Log.d("POSPrinters", "Attempting language detection for attached USB printer: ${baseDto.id}")
                                detectedLanguage = detectType(baseDto.connectionParams)
                                Log.i("POSPrinters", "Language detected for USB printer ${baseDto.id}: $detectedLanguage")
                            } catch (e: CancellationException) {
                                Log.w("POSPrinters", "Language detection cancelled for USB printer ${baseDto.id}")
                                // Don't send event if cancelled
                                return@launch
                            } catch (e: Exception) {
                                Log.e("POSPrinters", "Error detecting language for USB printer ${baseDto.id}: ${e.message}", e)
                                // Proceed to send event with null language on error
                            }
                        } else {
                            Log.w("POSPrinters", "Skipping language detection for ${baseDto.id} due to missing permission.")
                        }

                        // Create the final DTO with the detected language (or null)
                        val finalDto = baseDto.copy(printerLanguage = detectedLanguage)

                        // Send the event back to Dart on the main thread
                        withContext(Dispatchers.Main) {
                            try {
                                discoveryEventsApi.onPrinterAttached(finalDto) {}
                                Log.d("POSPrinters", "Sent onPrinterAttached event for ${finalDto.id} (Language: ${finalDto.printerLanguage})")
                            } catch (e: Exception) {
                                Log.e("POSPrinters", "Error sending onPrinterAttached event: ${e.message}", e)
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i("POSPrinters", "USB Printer Detached: ${baseDto.id}")
                    // Send detach event immediately on the main thread
                    Handler(Looper.getMainLooper()).post {
                        try {
                            discoveryEventsApi.onPrinterDetached(baseDto.id) {}
                            Log.d("POSPrinters", "Sent onPrinterDetached event for ${baseDto.id}")
                        } catch (e: Exception) {
                            Log.e("POSPrinters", "Error sending onPrinterDetached event: ${e.message}", e)
                        }
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
        POSConnect.init(this.applicationContext)
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        applicationContext.registerReceiver(usbReceiver, filter)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        POSPrintersApi.setUp(binding.binaryMessenger, null)
        pluginScope.cancel()
        applicationContext.unregisterReceiver(usbReceiver)
    }

    private val networkDispatcher = Dispatchers.IO

    // Detect printer type by probing ESC/POS then ZPL
    private suspend fun detectType(connectionParams: PrinterConnectionParams): PrinterLanguage? =
        suspendCancellableCoroutine { cont ->
            val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
            val connection: IDeviceConnection
            val target: String

            try {
                // Определение типа соединения и инициализация устройства
                when (connectionParams.connectionType) {
                    PosPrinterConnectionType.USB -> {
                        val usbParams = connectionParams.usbParams
                            ?: throw IllegalArgumentException("USB params are missing")
                        val usbDevice = findUsbDevice(
                            usbParams.vendorId.toInt(),
                            usbParams.productId.toInt(),
                            usbParams.usbSerialNumber
                        ) ?: throw Exception("USB device not found")
                        connection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
                        target = usbDevice.deviceName
                    }

                    PosPrinterConnectionType.NETWORK -> {
                        val networkParams = connectionParams.networkParams
                            ?: throw IllegalArgumentException("Network params are missing")
                        connection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
                        target = networkParams.ipAddress
                    }

                    else -> {
                        if (resumed.compareAndSet(false, true)) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                }
            } catch (e: Exception) {
                if (resumed.compareAndSet(false, true)) cont.resume(null)
                return@suspendCancellableCoroutine
            }

            // Listener подключения
            val listener = IConnectListener { code, _, _ ->
                if (code != POSConnect.CONNECT_SUCCESS) {
                    if (resumed.compareAndSet(false, true)) cont.resume(null)
                    return@IConnectListener
                }

                try {
                    val posPrinter = POSPrinter(connection)
                    posPrinter.initializePrinter()

                    posPrinter.printerStatus { escStatus ->
                        if (escStatus >= POSConst.STS_NORMAL) {
                            connection.close()
                            if (resumed.compareAndSet(false, true)) cont.resume(PrinterLanguage.ESC)
                        } else {
                            try {
                                val zplPrinter = ZPLPrinter(connection)
                                zplPrinter.printerStatus { zplCode ->
                                    val type = if (zplCode in 0..0x80) PrinterLanguage.ZPL else null
                                    connection.close()
                                    if (resumed.compareAndSet(false, true)) cont.resume(type)
                                }
                            } catch (zplEx: Exception) {
                                connection.close()
                                if (resumed.compareAndSet(false, true)) cont.resume(null)
                            }
                        }
                    }
                } catch (ex: Exception) {
                    connection.close()
                    if (resumed.compareAndSet(false, true)) cont.resume(null)
                }
            }

            try {
                connection.connect(target, listener)
            } catch (e: Exception) {
                if (resumed.compareAndSet(false, true)) cont.resume(null)
            }
        }

    override fun findPrinters(filter: PrinterDiscoveryFilter?) {

        Log.d("POSPrinters", "findPrinters (streaming) called with filter=$filter")
        pluginScope.launch {
            val foundPrinterIds = mutableSetOf<String>()
            var overallSuccess = true
            var firstError: Throwable? = null

            // Helper function to check if a connection type should be discovered
            fun shouldDiscover(type: DiscoveryConnectionType): Boolean {
                return filter?.connectionTypes == null || filter.connectionTypes.contains(type)
            }

            // Helper function to check if a printer language matches the filter
            fun languageMatches(language: PrinterLanguage?): Boolean {
                // If no language filter is set, or it's empty, always match
                if (filter?.languages == null || filter.languages.isEmpty()) {
                    return true
                }
                // If a language filter is set, the printer's language must be in the list
                // Note: This currently excludes printers where language detection failed (language is null)
                // if a specific language filter is active. Adjust if null should match somehow.
                return language != null && filter.languages.contains(language)
            }


            suspend fun sendPrinterFound(raw: DiscoveredPrinterDTO) {
                // Apply language filter *before* sending
                if (!languageMatches(raw.printerLanguage)) {
                    Log.d("POSPrinters", "Skipping printer ${raw.id} due to language filter mismatch (language: ${raw.printerLanguage}, filter: ${filter?.languages})")
                    return
                }

                // Check if already sent (handles potential duplicates across discovery methods if IDs overlap)
                if (!foundPrinterIds.add(raw.id)) {
                    Log.d("POSPrinters", "Skipping already sent printer: ${raw.id}")
                    return
                }


                val dto = DiscoveredPrinterDTO(
                    id = raw.id,
                    connectionParams = PrinterConnectionParams(
                        connectionType = raw.connectionParams.connectionType,
                        usbParams = raw.connectionParams.usbParams,
                        networkParams = raw.connectionParams.networkParams,
                    ),
                    printerLanguage = raw.printerLanguage,
                )
                withContext(Dispatchers.Main) {
                    try {
                        discoveryEventsApi.onPrinterFound(dto) {}
                        Log.d("POSPrinters", "Sent printer to Dart: ${dto.id} (Language: ${dto.printerLanguage})")
                    } catch (e: Exception) {
                        Log.e(
                            "POSPrinters",
                            "Error sending printer found event for ${dto.id}: ${e.message}",
                            e
                        )
                    }
                }
            }

            try {
                Log.d("POSPrinters", "Starting USB discovery phase...")
                // Corrected filter logic: Discover if filter is null OR connectionTypes is null OR contains USB
                if (shouldDiscover(DiscoveryConnectionType.USB)) {
                    try {
                        val usbPrinters = discoverUsbPrinters()
                        usbPrinters.forEach { sendPrinterFound(it) }
                        Log.d("POSPrinters", "USB discovery phase complete.")
                    } catch (e: Exception) {
                        Log.e("POSPrinters", "Error during USB discovery phase: ${e.message}", e)
                        overallSuccess = false
                        firstError = e
                    }
                } else {
                    Log.d("POSPrinters", "Skipping USB discovery due to filter.")
                }

                ensureActive() // Check for cancellation between phases
                var sdkFoundIps: List<String> = emptyList()
                // Corrected filter logic for SDK
                if (shouldDiscover(DiscoveryConnectionType.SDK)) {
                    Log.d("POSPrinters", "Starting SDK Network discovery phase...")

                    try {
                        val (sdkNetworkPrinters, ips) = discoverSdkNetworkPrinters()
                        sdkFoundIps = ips
                        sdkNetworkPrinters.forEach { sendPrinterFound(it) }
                        Log.d("POSPrinters", "SDK Network discovery phase complete.")
                    } catch (e: Exception) {
                        Log.e(
                            "POSPrinters",
                            "Error during SDK Network discovery phase: ${e.message}",
                            e
                        )
                        overallSuccess = false
                        if (firstError == null) firstError = e
                    }
                } else {
                    Log.d("POSPrinters", "Skipping SDK Network discovery due to filter.")
                }

                ensureActive()
                // Corrected filter logic for TCP
                if (shouldDiscover(DiscoveryConnectionType.TCP)) {
                    Log.d("POSPrinters", "Starting TCP Network discovery phase...")
                    try {
                        val tcpNetworkPrinters = discoverTcpNetworkPrinters(sdkFoundIps)
                        tcpNetworkPrinters.forEach { sendPrinterFound(it) }
                        Log.d("POSPrinters", "TCP Network discovery phase complete.")
                    } catch (e: Exception) {
                        Log.e(
                            "POSPrinters",
                            "Error during TCP Network discovery phase: ${e.message}",
                            e
                        )
                        overallSuccess = false
                        if (firstError == null) firstError = e
                    }
                } else {
                    Log.d("POSPrinters", "Skipping TCP Network discovery due to filter.")
                }


            } catch (e: CancellationException) {
                Log.w("POSPrinters", "findPrinters (streaming) cancelled: ${e.message}")
                overallSuccess = false // Mark as unsuccessful if cancelled
                if (firstError == null) firstError = e // Record cancellation as error
                // No need to rethrow, finally block will handle completion call
            } catch (e: Throwable) {
                // Catch any other unexpected errors during the orchestration
                Log.e(
                    "POSPrinters",
                    "Unexpected error during findPrinters (streaming): ${e.message}",
                    e
                )
                overallSuccess = false
                if (firstError == null) firstError = e
            } finally {
                Log.i(
                    "POSPrinters",
                    "findPrinters (streaming) finished. Overall Success: $overallSuccess. Total unique printers sent: ${foundPrinterIds.size}"
                )
                withContext(Dispatchers.Main) {
                    try {
                        discoveryEventsApi.onDiscoveryComplete(
                            overallSuccess,
                            firstError?.message
                        ) {}
                    } catch (e: Exception) {
                        Log.e(
                            "POSPrinters",
                            "Error sending discovery completion to Dart: ${e.message}",
                            e
                        )
                    }
                }
            }
        }
    }

    private suspend fun discoverUsbPrinters(): List<DiscoveredPrinterDTO> =
        withContext(networkDispatcher) {
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
                        Log.w(
                            "POSPrinters",
                            "Error accessing interfaces for USB device ${device.deviceName}: ${e.message}"
                        )
                        // Continue to next device
                        return@forEach
                    }

                    if (isLikelyPrinter) {
                        Log.d(
                            "POSPrinters",
                            "Device ${device.deviceName} (VID:${device.vendorId}, PID:${device.productId}) is likely a printer."
                        )
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
                                Log.w(
                                    "POSPrinters",
                                    "SecurityException getting serial number for ${device.deviceName}: ${se.message}"
                                )
                            } catch (e: Exception) {
                                Log.e(
                                    "POSPrinters",
                                    "Error getting serial number for ${device.deviceName}: ${e.message}"
                                )
                            }
                        } else {
                            // Log if needed, but don't prevent adding the printer
                            // Log.v("POSPrinters", "Cannot get USB serial for ${device.deviceName} due to missing permission.")
                        }
                        val id = "${device.vendorId}:${device.productId}:${device.serialNumber}"
                        val connectionParams = PrinterConnectionParams(
                            connectionType = PosPrinterConnectionType.USB,
                            usbParams = UsbParams(
                                vendorId = device.vendorId.toLong(),
                                productId = device.productId.toLong(),
                                usbSerialNumber = usbSerial,
                                manufacturer = device.manufacturerName,
                                productName = device.productName,
                            ),
                            networkParams = null,
                        )
                        val language = detectType(connectionParams)

                        // Add printer even if serial couldn't be read
                        discovered.add(
                            DiscoveredPrinterDTO(
                                id = id,
                                connectionParams = connectionParams,
                                printerLanguage = language
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
        val jobs = mutableListOf<Job>() // Keep track of launched detection jobs

        val searchTimeoutMillis = 5000L // 5 seconds timeout for SDK search

        // Timeout handling using coroutine delay
        val timeoutJob = pluginScope.launch {
            delay(searchTimeoutMillis)
            if (!future.isDone) {
                Log.w("POSPrinters", "SDK network scan timed out after ${searchTimeoutMillis}ms.")
                // Cancel any ongoing detection jobs before completing
                jobs.forEach { it.cancel() }
                future.complete(
                    Pair(
                        discoveredPrinters.toList(), // Return whatever was found and processed
                        foundIps.toList()
                    )
                )
            }
        }

        try {
            try {
                posUdpNet.searchNetDevice { netPrinter ->
                    // Check if future is already completed (e.g., by timeout)
                    if (future.isDone || netPrinter?.ipStr == null) return@searchNetDevice

                    val ip = netPrinter.ipStr
                    Log.d(
                        "POSPrinters",
                        "SDK Network device found: IP=$ip, MAC=${netPrinter.macStr}"
                    )

                    // Add to list only if IP is new
                    if (foundIps.add(ip)) {
                        // Launch a new coroutine to detect the printer type
                        val job = pluginScope.launch(networkDispatcher) { // Use appropriate dispatcher
                            try {
                                val connectionParams = PrinterConnectionParams(
                                    connectionType = PosPrinterConnectionType.NETWORK,
                                    usbParams = null,
                                    networkParams = NetworkParams(
                                        ipAddress = ip,
                                        macAddress = netPrinter.macStr, // Store MAC if available
                                        gateway =  netPrinter.gatewayStr,
                                        mask = netPrinter.maskStr,
                                        dhcp = netPrinter.isDhcp,
                                    )
                                )
                                val printerLanguage = detectType(connectionParams) // Call suspend function
                                val printer = DiscoveredPrinterDTO(
                                    id = ip,
                                    connectionParams = connectionParams,
                                    printerLanguage = printerLanguage // Use the detected language
                                )
                                // Synchronize access if adding to a shared list across coroutines
                                synchronized(discoveredPrinters) {
                                    discoveredPrinters.add(printer)
                                }
                                Log.d("POSPrinters", "SDK Printer $ip processed, language: $printerLanguage")
                            } catch (e: CancellationException) {
                                Log.w("POSPrinters", "Detection cancelled for SDK printer $ip")
                                // Don't re-throw, just let the job finish
                            } catch (e: Exception) {
                                Log.e("POSPrinters", "Error detecting type for SDK printer $ip: ${e.message}", e)
                                // Optionally add printer with null language or skip
                            }
                        }
                        // Keep track of the job
                        synchronized(jobs) {
                            jobs.add(job)
                        }
                        // Remove completed jobs to avoid memory leak
                        job.invokeOnCompletion {
                            synchronized(jobs) {
                                jobs.remove(job)
                            }
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                if (e.message?.contains("ENETUNREACH") == true) {
                    Log.w(
                        "POSPrinters",
                        "Network is unreachable during SDK scan. Skip network discovery."
                    )
                } else {
                    Log.w("POSPrinters", "I/O error during network scan: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(
                    "POSPrinters",
                    "Error during SDK network scan: ${e.javaClass.simpleName}: ${e.message}"
                )
            }

            // Monitor the SDK search completion and wait for detection jobs
            pluginScope.launch(networkDispatcher) {
                try {
                    val maxCheckTime = System.currentTimeMillis() + searchTimeoutMillis
                    // Wait until SDK search finishes or timeout occurs
                    while (posUdpNet.isSearch && !future.isDone && System.currentTimeMillis() < maxCheckTime) {
                        delay(200) // Check every 200ms
                    }

                    // Wait for all detection jobs associated with found IPs to complete
                    val currentJobs: List<Job>
                    synchronized(jobs) {
                        currentJobs = jobs.toList() // Copy list to avoid concurrent modification
                    }
                    if (currentJobs.isNotEmpty()) {
                        Log.d("POSPrinters", "Waiting for ${currentJobs.size} SDK printer type detections to complete...")
                        try {
                            currentJobs.joinAll() // Wait for detections
                            Log.d("POSPrinters", "SDK printer type detections finished.")
                        } catch (e: CancellationException) {
                            Log.w("POSPrinters", "Waiting for SDK detections cancelled.")
                            // Jobs might already be cancelled by timeoutJob
                        }
                    }

                    // If future is not done (by timeout), complete it now.
                    if (!future.isDone) {
                        Log.d("POSPrinters", "SDK network scan finished naturally.")
                        future.complete(Pair(discoveredPrinters.toList(), foundIps.toList()))
                    }
                } catch (e: CancellationException) {
                     Log.w("POSPrinters", "SDK monitoring/detection waiting cancelled.")
                     if (!future.isDone) {
                         // Ensure future completes even if this monitoring coroutine is cancelled
                         future.complete(Pair(discoveredPrinters.toList(), foundIps.toList()))
                     }
                } catch (e: Exception) {
                    Log.w(
                        "POSPrinters",
                        "Error checking network scan status or waiting for detections: ${e.javaClass.simpleName}"
                    )
                    if (!future.isDone) {
                        future.complete(Pair(discoveredPrinters.toList(), foundIps.toList()))
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(
                "POSPrinters",
                "Error starting SDK network scan: ${e.javaClass.simpleName}: ${e.message}"
            )
            if (!future.isDone) {
                future.completeExceptionally(e) // Complete with error
            }
        }

        // Wait for future completion (either naturally, by timeout, or error) and clean up timeout job
        return try {
            future.await() // Suspending await extension function for CompletableFuture
        } finally {
            timeoutJob.cancel() // Ensure timeout coroutine is cancelled
            // Ensure any remaining detection jobs are cancelled if future completed exceptionally or timed out
             if (!future.isDone || future.isCompletedExceptionally) {
                 synchronized(jobs) {
                     jobs.forEach { it.cancel() }
                 }
             }
        }
    }

    private suspend fun discoverTcpNetworkPrinters(excludeIps: List<String>): List<DiscoveredPrinterDTO> =
        withContext(networkDispatcher) {
            Log.d(
                "POSPrinters",
                "Starting TCP network scan (port 9100), excluding ${excludeIps.size} IPs."
            )
            val discovered = mutableListOf<DiscoveredPrinterDTO>()
            val localNetworks = getLocalIpAddresses()
            val port = 9100
            val timeoutMs = 300 // Short timeout for TCP check
            val semaphore = Semaphore(100) // Limit concurrent checks to 100
            val excludeIpSet = excludeIps.toSet() // Use Set for faster lookups

            val jobs = mutableListOf<Job>()

            localNetworks.forEach { networkInfo ->
                ensureActive() // Check for cancellation before starting a network range
                Log.d(
                    "POSPrinters",
                    "Scanning network: ${networkInfo.ipAddress}/${networkInfo.prefixLength}"
                )
                val range = getIpRangeFromCidr(networkInfo.ipAddress, networkInfo.prefixLength)
                if (range != null) {
                    range.forEach { ip ->
                        ensureActive() // Check for cancellation frequently during iteration
                        if (ip != networkInfo.ipAddress && !excludeIpSet.contains(ip)) {
                            jobs += pluginScope.launch(networkDispatcher) { // Launch in the correct scope/dispatcher
                                semaphore.acquire() // Limit concurrency
                                try {
                                    if (isPortOpen(ip, port, timeoutMs)) {
                                        Log.i("POSPrinters", "TCP Port $port open on $ip")
                                        val connectionParams = PrinterConnectionParams(
                                            connectionType = PosPrinterConnectionType.NETWORK,
                                            usbParams = null,
                                            networkParams = NetworkParams(
                                                ipAddress = ip,
                                                mask = null,
                                                gateway = null,
                                                macAddress = null,
                                                dhcp = null,
                                            )
                                        )
                                        val language = detectType(connectionParams) // Call detectType here
                                        val printer = DiscoveredPrinterDTO(
                                            id = ip, // Use IP as ID for TCP discovered printers
                                            connectionParams = connectionParams,
                                            printerLanguage = language // Use the detected language
                                        )
                                        // Synchronize access to the shared list
                                        synchronized(discovered) {
                                            discovered.add(printer)
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e // Re-throw cancellation exceptions
                                } catch (e: Exception) {
                                     Log.e("POSPrinters", "Error detecting type for TCP printer $ip: ${e.message}", e)
                                    // Log.v("POSPrinters", "TCP check failed for $ip: ${e.message}") // Verbose logging if needed
                                } finally {
                                    semaphore.release() // Release semaphore permit
                                }
                            }
                        }
                    }
                } else {
                    Log.w(
                        "POSPrinters",
                        "Could not determine IP range for ${networkInfo.ipAddress}/${networkInfo.prefixLength}"
                    )
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
                if (!intf.isUp || intf.isLoopback || intf.displayName.contains("vir") || intf.displayName.contains(
                        "docker"
                    )
                ) {
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
            Log.e(
                "POSPrinters",
                "Error calculating IP range for $ipAddress/$prefixLength: ${e.message}",
                e
            )
            null
        }
    }

    private suspend fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean =
        withContext(networkDispatcher) {
            var isReachable: Boolean
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                isReachable = true
            } catch (e: Exception) {
                isReachable = false
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                }
            }
            isReachable
        }

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

    private suspend fun getPrinterConnectionSuspending(printer: PrinterConnectionParams): IDeviceConnection =
        suspendCancellableCoroutine { cont ->
            try {
                val newConnection: IDeviceConnection
                val connectionTargetInfo: String

                when (printer.connectionType) {
                    PosPrinterConnectionType.USB -> {
                        val usbParams = printer.usbParams ?: throw Exception("Missing USB params")
                        val usbDevice = findUsbDevice(
                            usbParams.vendorId.toInt(),
                            usbParams.productId.toInt(),
                            usbParams.usbSerialNumber
                        ) ?: throw Exception("USB device not found")

                        if (!usbManager.hasPermission(usbDevice)) {
                            throw Exception("USB permission denied")
                        }

                        newConnection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
                        connectionTargetInfo = usbDevice.deviceName
                    }

                    PosPrinterConnectionType.NETWORK -> {
                        val ip = printer.networkParams?.ipAddress ?: throw Exception("Missing IP address")
                        newConnection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
                        connectionTargetInfo = ip
                    }
                }

                val listener = IConnectListener { code, _, _ ->
                    if (code == POSConnect.CONNECT_SUCCESS) {
                        cont.resume(newConnection)
                    } else {
                        cont.resumeWithException(Exception("Connection failed with code $code"))
                    }
                }

                newConnection.connect(connectionTargetInfo, listener)
            } catch (e: Throwable) {
                cont.resumeWithException(e)
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
                            Log.w(
                                "POSPrinters",
                                "findUsbDevice: No permission to check serial for ${device.deviceName}"
                            )
                            false
                        }
                    } catch (e: SecurityException) {
                        Log.w(
                            "POSPrinters",
                            "findUsbDevice: SecurityException getting serial for ${device.deviceName}: ${e.message}"
                        )
                        false
                    } catch (e: Exception) {
                        Log.e(
                            "POSPrinters",
                            "findUsbDevice: Error getting serial for ${device.deviceName}: ${e.message}"
                        )
                        false
                    }
                } else {
                    true
                }
            }
            vidMatch && pidMatch && serialMatch
        }
    }

    override fun printData(
        printer: PrinterConnectionParams,
        data: ByteArray,
        width: Long,
        callback: (Result<Unit>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = getPrinterConnectionSuspending(printer)
                val curPrinter = POSPrinter(connection)
                curPrinter.initializePrinter() // Ensure printer is in ESC/POS mode
                curPrinter.sendData(data)
                connection.close()
                callback(Result.success(Unit))
            } catch (platformError: Throwable) {
                Log.e(
                    "POSPrinters",
                    "Exception during printData: ${platformError.message}",
                    platformError
                )
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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = getPrinterConnectionSuspending(printer)
                val content = WebViewContent.html(html)
                val bitmap = Html2Bitmap.Builder()
                    .setBitmapWidth(width.toInt())
                    .setContent(content)
                    .setTextZoom(100)
                    .setContext(applicationContext) // Use applicationContext
                    .build()
                    .bitmap
                val curPrinter = POSPrinter(connection)
                curPrinter.initializePrinter() // Ensure printer is in ESC/POS mode
                curPrinter.printBitmap(bitmap, POSConst.ALIGNMENT_LEFT, width.toInt())
                curPrinter.cutHalfAndFeed(1)
                connection.close()
                callback(Result.success(Unit))
            } catch (platformError: Throwable) {
                Log.e(
                    "POSPrinters",
                    "Exception during printHTML: ${platformError.message}",
                    platformError
                )
                callback(Result.failure(Exception("Print HTML exception: ${platformError.message}")))
            }
        }
    }

    override fun openCashBox(printer: PrinterConnectionParams, callback: (Result<Unit>) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = getPrinterConnectionSuspending(printer)
                val curPrinter = POSPrinter(connection)
                curPrinter.initializePrinter()
                curPrinter.openCashBox(POSConst.PIN_TWO)
                connection.close()
                callback(Result.success(Unit))
            } catch (platformError: Throwable) {
                Log.e(
                    "POSPrinters",
                    "Exception during openCashBox: ${platformError.message}",
                    platformError
                )
                callback(Result.failure(Exception("Open cash box exception: ${platformError.message}")))
            }
        }
    }

    override fun getPrinterStatus(
        printer: PrinterConnectionParams,
        callback: (Result<StatusResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = getPrinterConnectionSuspending(printer)
                val pos = POSPrinter(connection)
                pos.initializePrinter() // Ensure printer is in ESC/POS mode before status check
                pos.printerStatus { status ->
                    val text = mapStatusCodeToString(status) // Use helper function
                    // Check if status indicates an error state based on the text mapping
                    val isErrorStatus =
                        status < POSConst.STS_NORMAL || status == POSConst.STS_PRINTER_ERR
                    connection.close()
                    if (isErrorStatus) {
                        callback(Result.failure(Exception(text)))
                    } else {
                        callback(Result.success(StatusResult(success = true, status = text)))
                    }
                }
            } catch (platformError: Throwable) {
                callback(Result.failure(Exception("Get status exception: ${platformError.message}")))
            }
        }
    }

    override fun getPrinterSN(
        printer: PrinterConnectionParams,
        callback: (Result<StringResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = getPrinterConnectionSuspending(printer)
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
                        } // Improved fallback
                    }
                    connection.close()
                    if (snString.isEmpty() || snString == "Error decoding SN") {
                        callback(Result.failure(Exception("Failed to decode serial number: $snString")))
                    } else {
                        callback(Result.success(StringResult(success = true, value = snString)))
                    }
                }
            } catch (platformError: Throwable) {
                Log.e(
                    "POSPrinters",
                    "Exception during getPrinterSN: ${platformError.message}",
                    platformError
                )
                callback(Result.failure(Exception("Get SN exception: ${platformError.message}")))
            }
        }
    }

    override fun setNetSettingsToPrinter(
        printer: PrinterConnectionParams,
        netSettings: NetworkParams,
        callback: (Result<Unit>) -> Unit
    ) {

        try {
            val ip = parseData(netSettings.ipAddress)
            val mask = parseData(netSettings.mask!!)
            val gateway = parseData(netSettings.gateway!!)
            if (ip == null || mask == null || gateway == null) {
                Log.e(
                    "POSPrinters",
                    "setNetSettingsToPrinter: Invalid IP/Mask/Gateway format in NetSettingsDTO"
                )
                callback(Result.failure(Exception("Invalid IP/Mask/Gateway format")))
                return
            }
            val dhcp = netSettings.dhcp


            val newPrinterConnection: IDeviceConnection
            val connectionTargetInfo: String

            when (printer.connectionType) {
                PosPrinterConnectionType.USB -> {

                    if (printer.usbParams == null) {
                        Log.e(
                            "POSPrinters",
                            "setNetSettingsToPrinter failed: Missing vendorId or productId for USB connection."
                        )
                        callback(Result.failure(Exception("Missing vendorId or productId for USB connection.")))
                        return
                    }

                    Log.d(
                        "POSPrinters",
                        "setNetSettingsToPrinter: Searching for USB device VID=${printer.usbParams.vendorId}, PID=${printer.usbParams.productId}, Serial=${printer.usbParams.usbSerialNumber}"
                    )
                    val usbDevice = findUsbDevice(
                        printer.usbParams.vendorId.toInt(),
                        printer.usbParams.productId.toInt(),
                        printer.usbParams.usbSerialNumber
                    )

                    if (usbDevice == null) {
                        Log.e(
                            "POSPrinters",
                            "setNetSettingsToPrinter failed: USB device VID=${printer.usbParams.vendorId}, PID=${printer.usbParams.productId}, Serial=${printer.usbParams.usbSerialNumber} not found."
                        )
                        callback(Result.failure(Exception("USB device not found (VID=${printer.usbParams.vendorId}, PID=${printer.usbParams.productId})")))
                        return
                    }


                    if (!usbManager.hasPermission(usbDevice)) {
                        Log.e(
                            "POSPrinters",
                            "setNetSettingsToPrinter failed: USB permission denied for device ${usbDevice.deviceName}"
                        )
                        callback(Result.failure(Exception("USB permission denied for device ${usbDevice.deviceName}")))
                        return
                    }

                    Log.d(
                        "POSPrinters",
                        "setNetSettingsToPrinter: Found matching USB device: ${usbDevice.deviceName}. Creating temp connection..."
                    )
                    newPrinterConnection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
                    connectionTargetInfo = usbDevice.deviceName
                }

                PosPrinterConnectionType.NETWORK -> {
                    if (printer.networkParams == null) {
                        Log.e(
                            "POSPrinters",
                            "setNetSettingsToPrinter failed: Missing ipAddress for Network connection."
                        )
                        callback(Result.failure(Exception("Missing ipAddress for Network connection.")))
                        return
                    }
                    Log.d(
                        "POSPrinters",
                        "setNetSettingsToPrinter: Preparing temp network connection to ${printer.networkParams.ipAddress}"
                    )
                    newPrinterConnection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
                    connectionTargetInfo = printer.networkParams.ipAddress
                }
            }

            Log.d(
                "POSPrinters",
                "Setting up temporary connection to '$connectionTargetInfo' for net settings..."
            )
            var replySubmitted = false
            val handler = IConnectListener { code, connInfo, msg ->
                Log.d(
                    "POSPrinters",
                    "setNetSettings listener invoked: code=$code, info=$connInfo, msg=$msg"
                )
                synchronized(this) {
                    if (replySubmitted) {
                        Log.w(
                            "POSPrinters",
                            "setNetSettings listener called again (code=$code), ignoring."
                        )
                        return@synchronized
                    }
                    when (code) {
                        POSConnect.CONNECT_SUCCESS -> {
                            Log.i(
                                "POSPrinters",
                                "Temp connection successful to $connInfo for net settings. Applying settings..."
                            )
                            try {
                                val p = POSPrinter(newPrinterConnection)
                                p.setNetAll(ip, mask, gateway, dhcp!!)
                                Log.i(
                                    "POSPrinters",
                                    "Net settings applied via $connInfo (assumed success). Closing temp connection after delay."
                                )
                                Handler(Looper.getMainLooper()).postDelayed({

                                    if (!replySubmitted) {
                                        callback(Result.success(Unit))
                                        replySubmitted = true
                                    }
                                    newPrinterConnection.close()
                                    Log.d(
                                        "POSPrinters",
                                        "Temp connection to $connInfo closed after net settings."
                                    )
                                }, 1000)
                            } catch (e: Exception) {
                                Log.e(
                                    "POSPrinters",
                                    "Error applying net settings via $connInfo: ${e.message}",
                                    e
                                )
                                if (!replySubmitted) {
                                    callback(Result.failure(Exception("Error applying net settings: ${e.message}")))
                                    replySubmitted = true
                                }
                                newPrinterConnection.close()
                            }
                        }

                        POSConnect.CONNECT_FAIL, POSConnect.CONNECT_INTERRUPT, POSConnect.SEND_FAIL -> {
                            Log.w(
                                "POSPrinters",
                                "Temp connection/send failed during net settings (Code: $code, Info: $connInfo, Msg: $msg)"
                            )
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
                            Log.w(
                                "POSPrinters",
                                "Unknown status code in setNetSettings listener: $code, Info: $connInfo, Msg: $msg"
                            )
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
            callback(Result.failure(Exception("Set net settings exception: ${platformError.message}")))
        }
    }

    override fun configureNetViaUDP(netSettings: NetworkParams, callback: (Result<Unit>) -> Unit) {
        try {
            val macBytes = parseMacAddress(netSettings.macAddress!!)
                ?: return callback(Result.failure(Exception("Invalid MAC address format")))
            val ipBytes = parseData(netSettings.ipAddress)
            val maskBytes = parseData(netSettings.mask!!)
            val gatewayBytes = parseData(netSettings.gateway!!)
            if (ipBytes == null || maskBytes == null || gatewayBytes == null) {
                return callback(Result.failure(Exception("Invalid IP/Mask/Gateway format")))
            }
            posUdpNet.udpNetConfig(macBytes, ipBytes, maskBytes, gatewayBytes, netSettings.dhcp!!)
            callback(Result.success(Unit))

        } catch (e: Throwable) {
            callback(Result.failure(Exception("Configure net via UDP exception: ${e.message}")))
        }
    }

    override fun printZplRawData(
        printer: PrinterConnectionParams,
        labelCommands: ByteArray,
        width: Long,
        callback: (Result<Unit>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = getPrinterConnectionSuspending(printer)
                val zpl = ZPLPrinter(connection)
                zpl.sendData(labelCommands)
                connection.close()
                callback(Result.success(Unit))
            } catch (e: Throwable) {
                Log.e("POSPrinters", "Print failed: ${e.message}", e)
                callback(Result.failure(e))
            }
        }
    }

    override fun printZplHtml(
        printer: PrinterConnectionParams,
        html: String,
        width: Long,
        height: Long,
        callback: (Result<Unit>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = getPrinterConnectionSuspending(printer)
        val content = WebViewContent.html(html)
            val bmp = Html2Bitmap.Builder()
                .setBitmapWidth(width.toInt())
                .setStrictMode(true)
                .setContent(content)
                .setTextZoom(100)
                .setContext(applicationContext) // Use applicationContext
                .build()
                .bitmap

            val zpl = ZPLPrinter(connection)
            zpl.setPrinterWidth(width.toInt())
            zpl.addStart()
            zpl.printBmpCompress(0, 0, bmp, width.toInt(), AlgorithmType.Dithering)
            zpl.addEnd()
            connection.close()
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            Log.e("POSPrinters", "Exception during printLabelHTML: ${e.message}", e)
            callback(Result.failure(Exception("Print label HTML exception: ${e.message}")))
        }}

    }

    // Получить статус ZPL‑принтера (коды 00–80)
    override fun getZPLPrinterStatus(
        printer: PrinterConnectionParams,
        callback: (Result<ZPLStatusResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
                try {
                    val connection = getPrinterConnectionSuspending(printer)
                    val zpl = ZPLPrinter(connection)
                    zpl.printerStatus(500, { code ->
                        val success = code == 0
                        connection.close()
                        val result = if (success) {
                            ZPLStatusResult(true, code.toLong(), null)
                        } else {
                            ZPLStatusResult(false, code.toLong(), "ZPL status code $code")
                        }
                        callback(Result.success(result))
                    })
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
