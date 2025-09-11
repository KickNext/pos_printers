package com.kicknext.pos_printers.connection

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.kicknext.pos_printers.gen.*
import net.posprinter.IDeviceConnection
import net.posprinter.POSConnect

/**
 * Manages printer connections with proper resource management and thread safety
 */
class PrinterConnectionManager(private val usbManager: UsbManager) {
    
    companion object {
        private const val TAG = "PrinterConnectionManager"
        private const val CONNECTION_TIMEOUT_MS = 15000L // Увеличиваем до 15 секунд
        private const val MAX_RETRY_ATTEMPTS = 30
        private const val RETRY_DELAY_MS = 500L // Увеличиваем задержку между попытками
    }
    
    /**
     * Executes an operation with a printer connection, handling connection lifecycle automatically
     */
    fun <T> executeWithConnection(
        printer: PrinterConnectionParamsDTO,
        operation: (IDeviceConnection) -> T
    ): T {
        var lastException: Exception? = null
        
        // Retry logic for connection failures
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            val (connection, target) = createConnection(printer)
            try {
                Log.d(TAG, "Connection attempt ${attempt + 1}/$MAX_RETRY_ATTEMPTS to $target")
                connectWithTimeout(connection, target)
                
                return try {
                    val result = operation(connection)
                    Log.d(TAG, "Operation completed successfully, closing connection")
                    result
                } finally {
                    // Always close connection immediately after operation
                    try {
                        connection.close()
                        Log.d(TAG, "Connection closed successfully")
                    } catch (closeEx: Exception) {
                        Log.w(TAG, "Error closing connection after operation: ${closeEx.message}")
                    }
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Connection attempt ${attempt + 1} failed: ${e.message}")
                
                try {
                    connection.close()
                } catch (closeEx: Exception) {
                    Log.w(TAG, "Error closing connection after failed attempt: ${closeEx.message}")
                }
                
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    // Wait before retry to allow printer to reset
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
        }
        
        // All attempts failed
        throw lastException ?: Exception("Connection failed after $MAX_RETRY_ATTEMPTS attempts")
    }

    /**
     * Executes print operation with proper completion waiting (for non-suspend operations)
     */
    fun executeWithPrintCompletion(
        printer: PrinterConnectionParamsDTO,
        operation: (IDeviceConnection) -> Unit,
        callback: (Result<Unit>) -> Unit
    ) {
        var lastException: Exception? = null
        
        // Retry logic for connection failures
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            val (connection, target) = createConnection(printer)
            try {
                Log.d(TAG, "Print connection attempt ${attempt + 1}/$MAX_RETRY_ATTEMPTS to $target")
                connectWithTimeout(connection, target)
                
                try {
                    // Execute print operation
                    operation(connection)
                    Log.d(TAG, "Print operation sent, waiting for completion...")
                    
                    // Wait for print completion
                    waitForPrintCompletion(connection) { success, error ->
                        try {
                            connection.close()
                            Log.d(TAG, "Connection closed after print completion")
                        } catch (closeEx: Exception) {
                            Log.w(TAG, "Error closing connection: ${closeEx.message}")
                        }
                        
                        if (success) {
                            callback(Result.success(Unit))
                        } else {
                            callback(Result.failure(Exception("Print completion failed: $error")))
                        }
                    }
                    return
                } catch (e: Exception) {
                    try {
                        connection.close()
                    } catch (closeEx: Exception) {
                        Log.w(TAG, "Error closing connection after operation failed: ${closeEx.message}")
                    }
                    throw e
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Print connection attempt ${attempt + 1} failed: ${e.message}")
                
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    // Wait before retry to allow printer to reset
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
        }
        
        // All attempts failed
        callback(Result.failure(lastException ?: Exception("Print connection failed after $MAX_RETRY_ATTEMPTS attempts")))
    }

    /**
     * Executes suspend print operation with proper completion waiting
     */
    fun executeWithSuspendPrintCompletion(
        printer: PrinterConnectionParamsDTO,
        operation: suspend (IDeviceConnection) -> Unit,
        callback: (Result<Unit>) -> Unit
    ) {
        var lastException: Exception? = null
        
        // Retry logic for connection failures
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            val (connection, target) = createConnection(printer)
            try {
                Log.d(TAG, "Print connection attempt ${attempt + 1}/$MAX_RETRY_ATTEMPTS to $target")
                connectWithTimeout(connection, target)
                
                try {
                    // Execute print operation in coroutine
                    kotlinx.coroutines.runBlocking {
                        operation(connection)
                    }
                    Log.d(TAG, "Print operation sent, waiting for completion...")
                    
                    // Wait for print completion
                    waitForPrintCompletion(connection) { success, error ->
                        try {
                            connection.close()
                            Log.d(TAG, "Connection closed after print completion")
                        } catch (closeEx: Exception) {
                            Log.w(TAG, "Error closing connection: ${closeEx.message}")
                        }
                        
                        if (success) {
                            callback(Result.success(Unit))
                        } else {
                            callback(Result.failure(Exception("Print completion failed: $error")))
                        }
                    }
                    return
                } catch (e: Exception) {
                    try {
                        connection.close()
                    } catch (closeEx: Exception) {
                        Log.w(TAG, "Error closing connection after operation failed: ${closeEx.message}")
                    }
                    throw e
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Print connection attempt ${attempt + 1} failed: ${e.message}")
                
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    // Wait before retry to allow printer to reset
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
        }
        
        // All attempts failed
        callback(Result.failure(lastException ?: Exception("Print connection failed after $MAX_RETRY_ATTEMPTS attempts")))
    }

    /**
     * Waits for print completion using simple delay
     */
    private fun waitForPrintCompletion(
        connection: IDeviceConnection,
        callback: (Boolean, String?) -> Unit
    ) {
        // Simple approach: wait for a reasonable time for print to complete
        // This avoids interfering with the print process
        Log.d(TAG, "Waiting for print completion (simple delay approach)")
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "Print completion delay finished, assuming print completed")
            callback(true, null)
        }, 3000L) // Wait 3 seconds for print to complete
    }
    
    /**
     * Creates or retrieves a connection for the given printer
     */
    private fun createConnection(printer: PrinterConnectionParamsDTO): Pair<IDeviceConnection, String> {
        return when (printer.connectionType) {
            PosPrinterConnectionType.USB -> {
                val usbParams = printer.usbParams 
                    ?: throw IllegalArgumentException("USB params are required for USB connection")
                val device = findUsbDevice(usbParams)
                val connection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
                Pair(connection, device.deviceName)
            }
            PosPrinterConnectionType.NETWORK -> {
                val networkParams = printer.networkParams
                    ?: throw IllegalArgumentException("Network params are required for network connection")
                val connection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
                Pair(connection, networkParams.ipAddress)
            }
        }
    }
    
    /**
     * Finds USB device with proper validation
     */
    private fun findUsbDevice(params: UsbParams): UsbDevice {
        val device = usbManager.deviceList.values.find { device ->
            val vidMatch = device.vendorId == params.vendorId.toInt()
            val pidMatch = device.productId == params.productId.toInt()
            val serialMatch = params.serialNumber?.let { expectedSerial ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && usbManager.hasPermission(device)) {
                    try {
                        device.serialNumber == expectedSerial
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Cannot read USB serial number: ${e.message}")
                        true // Allow connection attempt
                    }
                } else {
                    true // Can't verify on older Android versions
                }
            } ?: true
            
            vidMatch && pidMatch && serialMatch
        } ?: throw UsbDeviceNotFoundException(params.vendorId, params.productId, params.serialNumber)
        
        if (!usbManager.hasPermission(device)) {
            throw SecurityException("USB permission denied for device ${device.deviceName}")
        }
        
        return device
    }
    
    /**
     * Connects to device with timeout handling
     */
    private fun connectWithTimeout(connection: IDeviceConnection, target: String) {
        var connected = false
        var connectionError: Exception? = null
        
        // For network printers, try to force close any existing connections
        if (target.contains(".")) { // Likely an IP address
            try {
                // Try to create and immediately close a connection to reset the printer state
                val resetConnection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
                resetConnection.close()
                Thread.sleep(500) // Brief pause
            } catch (e: Exception) {
                Log.d(TAG, "Reset connection attempt completed")
            }
        }
        
        val connectListener = net.posprinter.IConnectListener { code, _, message ->
            when (code) {
                POSConnect.CONNECT_SUCCESS -> {
                    connected = true
                    synchronized(this) { (this as Object).notifyAll() }
                }
                else -> {
                    connectionError = ConnectionException("Connection failed: $message (code: $code)")
                    synchronized(this) { (this as Object).notifyAll() }
                }
            }
        }
        
        connection.connect(target, connectListener)
        
        // Wait for connection with timeout
        synchronized(this) {
            val startTime = System.currentTimeMillis()
            while (!connected && connectionError == null && 
                   System.currentTimeMillis() - startTime < CONNECTION_TIMEOUT_MS) {
                try {
                    (this as Object).wait(1000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedException("Connection interrupted")
                }
            }
        }
        
        when {
            connectionError != null -> throw connectionError!!
            !connected -> throw ConnectionException("Connection timeout after ${CONNECTION_TIMEOUT_MS}ms")
        }
    }
}

class UsbDeviceNotFoundException(
    val vendorId: Long,
    val productId: Long,
    val serialNumber: String?
) : Exception("USB device not found (VID=$vendorId, PID=$productId, SERIAL=${serialNumber ?: "null"})")

class ConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)
