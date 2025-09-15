package com.kicknext.pos_printers.printer

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.izettle.html2bitmap.Html2Bitmap
import com.izettle.html2bitmap.content.WebViewContent
import com.kicknext.pos_printers.gen.*
import kotlinx.coroutines.*
import net.posprinter.*
import net.posprinter.model.AlgorithmType
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Handles printer operations with proper error handling and validation
 */
class PrinterOperations(private val context: Context) {
    
    companion object {
        private const val TAG = "PrinterOperations"
        private const val DEFAULT_TIMEOUT_MS = 5000L
        private const val STATUS_CHECK_TIMEOUT_MS = 3000L
    }
    
    /**
     * Prints raw data to ESC/POS printer
     */
    suspend fun printRawData(
        connection: IDeviceConnection,
        data: ByteArray,
        width: Long
    ) = withContext(Dispatchers.IO) {
        validatePrinterReady(connection)
        
        val printer = POSPrinter(connection)
        printer.initializePrinter()
        printer.sendData(data)
        
        Log.d(TAG, "Raw data printed successfully, ${data.size} bytes")
    }
    
    /**
     * Prints HTML content to ESC/POS printer
     */
    suspend fun printHtml(
        connection: IDeviceConnection,
        html: String,
        width: Long
    ) = withContext(Dispatchers.IO) {
        validatePrinterReady(connection)
        
        // Generate bitmap from HTML in background thread but with proper synchronization
        val bitmap = suspendCoroutine<Bitmap> { continuation ->
            // Запускаем на Main потоке через Handler
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val content = WebViewContent.html(html)
                    val builder = Html2Bitmap.Builder()
                        .setBitmapWidth(width.toInt())
                        .setContent(content)
                        .setTextZoom(100)
                        .setStrictMode(false)
                        .setContext(context)
                    
                    // Даем WebView время отобразить HTML перед получением bitmap
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            val bitmapResult = builder.build().bitmap
                            
                            if (bitmapResult != null) {
                                Log.d(TAG, "HTML to Bitmap conversion successful, size: ${bitmapResult.width}x${bitmapResult.height}")
                                continuation.resume(bitmapResult)
                            } else {
                                Log.e(TAG, "Generated bitmap is null, trying again...")
                                // Повторная попытка через еще большую задержку
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    try {
                                        val retryBitmap = builder.build().bitmap
                                        if (retryBitmap != null) {
                                            continuation.resume(retryBitmap)
                                        } else {
                                            continuation.resumeWith(kotlin.Result.failure(IllegalStateException("Generated bitmap is null after retry")))
                                        }
                                    } catch (e: Exception) {
                                        continuation.resumeWith(kotlin.Result.failure(e))
                                    }
                                }, 2000) // 2 секунды дополнительно
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "HTML to Bitmap conversion failed", e)
                            continuation.resumeWith(kotlin.Result.failure(e))
                        }
                    }, 1000) // 1 секунда базовая задержка
                } catch (e: Exception) {
                    Log.e(TAG, "HTML to Bitmap setup failed", e)
                    continuation.resumeWith(kotlin.Result.failure(e))
                }
            }
        }
        
        val printer = POSPrinter(connection)
        printer.initializePrinter()
        printer.printBitmap(bitmap, POSConst.ALIGNMENT_LEFT, width.toInt())
        printer.cutHalfAndFeed(1)
        
        Log.d(TAG, "HTML printed successfully")
    }
    
    /**
     * Opens cash drawer
     */
    suspend fun openCashBox(connection: IDeviceConnection) = withContext(Dispatchers.IO) {
        validatePrinterReady(connection)
        
        val printer = POSPrinter(connection)
        printer.initializePrinter()
        // Некоторые модели требуют более длинного или повторного импульса (соленоид не всегда успевает сработать).
        // Стратегия: несколько попыток с экспоненциальной задержкой + проверка статуса (если есть канал IN) между.
        val attempts = 4
        var opened = false
        var lastError: Exception? = null
        for (i in 0 until attempts) {
            val start = System.currentTimeMillis()
            try {
                printer.openCashBox(POSConst.PIN_TWO)
                Log.d(TAG, "Cash drawer pulse sent (attempt=${i + 1}/$attempts)")
                // Минимальная пауза чтобы дать реле/соленоиду энергию
                delay(120 + i * 80L) // 120ms, 200ms, 280ms, 360ms
                // (Опционально) можно запросить статус принтера, но не все модели возвращают
                try {
                    printer.printerStatus { code ->
                        Log.d(TAG, "Status after attempt ${i + 1}: code=$code")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Status check skipped: ${e.message}")
                }
                // Мы не имеем прямого API узнать открылся ли ящик; считаем что если команда отправлена без ошибки — успех
                opened = true
                break
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Cash drawer pulse failed attempt ${i + 1}: ${e.message}")
                val elapsed = System.currentTimeMillis() - start
                // Ждём чуть больше перед следующей попыткой
                if (i < attempts - 1) delay((200 - elapsed).coerceAtLeast(50))
            }
        }
        if (!opened) {
            throw lastError ?: Exception("Failed to open cash drawer after $attempts attempts")
        }
        Log.d(TAG, "Cash drawer command sequence completed (opened=$opened)")
    }
    
    /**
     * Prints ZPL raw data
     */
    suspend fun printZplRawData(
        connection: IDeviceConnection,
        labelCommands: ByteArray,
        width: Long
    ) = withContext(Dispatchers.IO) {
        validateZplPrinterReady(connection)
        
        val zplPrinter = ZPLPrinter(connection)
        zplPrinter.sendData(labelCommands)
        
        Log.d(TAG, "ZPL raw data printed successfully, ${labelCommands.size} bytes")
    }
    
    /**
     * Prints HTML as ZPL label
     */
    suspend fun printZplHtml(
        connection: IDeviceConnection,
        html: String,
        width: Long
    ) = withContext(Dispatchers.IO) {
        validateZplPrinterReady(connection)
        
        // Generate bitmap from HTML with proper synchronization
        val bitmap = suspendCoroutine<Bitmap> { continuation ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val content = WebViewContent.html(html)
                    val bitmapResult = Html2Bitmap.Builder()
                        .setBitmapWidth(width.toInt())
                        .setContent(content)
                        .setTextZoom(100)
                        .setStrictMode(true)
                        .setContext(context)
                        .build()
                        .bitmap
                    
                    if (bitmapResult != null) {
                        Log.d(TAG, "ZPL HTML to Bitmap conversion successful, size: ${bitmapResult.width}x${bitmapResult.height}")
                        continuation.resume(bitmapResult)
                    } else {
                        continuation.resumeWith(kotlin.Result.failure(IllegalStateException("Generated bitmap is null")))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ZPL HTML to Bitmap conversion failed", e)
                    continuation.resumeWith(kotlin.Result.failure(e))
                }
            }
        }
        
        val zplPrinter = ZPLPrinter(connection)
        zplPrinter.setPrinterWidth(width.toInt())
        zplPrinter.addStart()
        zplPrinter.printBmpCompress(0, 0, bitmap, width.toInt(), AlgorithmType.Dithering)
        zplPrinter.addEnd()
        
        Log.d(TAG, "ZPL HTML printed successfully")
    }
    
    /**
     * Gets ESC/POS printer status
     */
    suspend fun getPrinterStatus(connection: IDeviceConnection): StatusResult = withContext(Dispatchers.IO) {
        val printer = POSPrinter(connection)
        
        val status = suspendCoroutine<Int> { continuation ->
            try {
                printer.printerStatus { statusCode ->
                    continuation.resume(statusCode)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting printer status", e)
                continuation.resume(-1) // Error status
            }
        }
        
        val statusText = mapStatusCodeToString(status)
        val isSuccess = status >= POSConst.STS_NORMAL
        
        StatusResult(
            success = isSuccess,
            status = statusText,
            errorMessage = if (isSuccess) null else statusText
        )
    }
    
    /**
     * Gets ZPL printer status
     */
    suspend fun getZplPrinterStatus(connection: IDeviceConnection): ZPLStatusResult = withContext(Dispatchers.IO) {
        val zplPrinter = ZPLPrinter(connection)
        
        val statusCode = suspendCoroutine<Int> { continuation ->
            try {
                zplPrinter.printerStatus(STATUS_CHECK_TIMEOUT_MS.toInt()) { code ->
                    continuation.resume(code)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting ZPL printer status", e)
                continuation.resume(-1) // Error status
            }
        }
        
        val isSuccess = statusCode == 0
        val errorMessage = if (isSuccess) null else "ZPL status code $statusCode"
        
        ZPLStatusResult(
            success = isSuccess,
            code = statusCode.toLong(),
            errorMessage = errorMessage
        )
    }
    
    /**
     * Gets printer serial number
     */
    suspend fun getPrinterSerialNumber(connection: IDeviceConnection): StringResult = withContext(Dispatchers.IO) {
        val printer = POSPrinter(connection)
        printer.initializePrinter()
        
        val serialBytes = suspendCoroutine<ByteArray?> { continuation ->
            try {
                printer.getSerialNumber { sn ->
                    continuation.resume(sn)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting printer serial number", e)
                continuation.resume(null)
            }
        }
        
        if (serialBytes == null) {
            throw Exception("Failed to retrieve printer serial number")
        }
        
        val serialNumber = try {
            String(serialBytes, charset("GBK")).takeIf { it.isNotBlank() }
                ?: String(serialBytes, Charsets.UTF_8).takeIf { it.isNotBlank() }
                ?: throw Exception("Empty serial number")
        } catch (e: Exception) {
            throw Exception("Failed to decode serial number: ${e.message}")
        }
        
        StringResult(success = true, value = serialNumber)
    }
    
    /**
     * Sets network settings to printer
     */
    suspend fun setNetworkSettings(
        connection: IDeviceConnection,
        netSettings: NetworkParams
    ) = withContext(Dispatchers.IO) {
        validatePrinterReady(connection)
        
        val ip = parseIpAddress(netSettings.ipAddress)
        val mask = parseIpAddress(netSettings.mask ?: throw IllegalArgumentException("Mask is required"))
        val gateway = parseIpAddress(netSettings.gateway ?: throw IllegalArgumentException("Gateway is required"))
        val dhcp = netSettings.dhcp ?: throw IllegalArgumentException("DHCP setting is required")
        
        val printer = POSPrinter(connection)
        printer.setNetAll(ip, mask, gateway, dhcp)
        
        // Wait a bit for settings to be applied
        delay(1000)
        
        Log.d(TAG, "Network settings applied successfully")
    }
    
    /**
     * Detects printer language (ESC/POS or ZPL)
     */
    suspend fun detectPrinterLanguage(
        connection: IDeviceConnection,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): PrinterLanguage = withContext(Dispatchers.IO) {
        
        // Try ESC/POS status command first (more reliable)
        val escResponse = sendCommandWithTimeout(
            connection,
            byteArrayOf(0x1D, 0x49, 0x43, 0x00), // GS ( I command for status
            timeoutMs
        )
        
        if (escResponse != null && escResponse.isNotEmpty()) {
            Log.d(TAG, "ESC/POS printer detected via status command")
            return@withContext PrinterLanguage.ESC
        }
        
        // Try ZPL status command
        val zplResponse = sendCommandWithTimeout(
            connection,
            "~HI\r\n".toByteArray(Charsets.US_ASCII), // ZPL host identification
            timeoutMs
        )
        
        if (zplResponse != null && zplResponse.isNotEmpty()) {
            Log.d(TAG, "ZPL printer detected via ~HI command")
            return@withContext PrinterLanguage.ZPL
        }
        
        // Try Xprinter identification command as fallback
        val xprinterResponse = sendCommandWithTimeout(
            connection,
            byteArrayOf(0x1B, 0x69, 0x01), // ESC i command
            timeoutMs
        )
        
        if (xprinterResponse != null && xprinterResponse.isNotEmpty()) {
            val responseText = String(xprinterResponse, Charsets.US_ASCII)
            Log.d(TAG, "Xprinter response: $responseText")
            return@withContext when {
                responseText.contains("ZPL", ignoreCase = true) -> PrinterLanguage.ZPL
                else -> PrinterLanguage.ESC
            }
        }
        
        // Default to ESC/POS if no response
        Log.d(TAG, "No response to language detection commands, defaulting to ESC/POS")
        return@withContext PrinterLanguage.ESC
    }
    
    /**
     * Sends command and waits for response with timeout
     */
    private suspend fun sendCommandWithTimeout(
        connection: IDeviceConnection,
        command: ByteArray,
        timeoutMs: Long
    ): ByteArray? = withTimeoutOrNull(timeoutMs) {
        suspendCoroutine<ByteArray?> { continuation ->
            var responseReceived = false
            
            // Set up data listener
            connection.readData { data ->
                if (!responseReceived && data != null && data.isNotEmpty()) {
                    responseReceived = true
                    continuation.resume(data)
                }
            }
            
            // Send command
            connection.sendData(command)
            
            // If no response after timeout, the withTimeoutOrNull will handle it
        }
    }
    
    private fun validatePrinterReady(connection: IDeviceConnection) {
        // Add any general printer validation logic here
        // For now, just ensure connection is not null
        requireNotNull(connection) { "Printer connection is null" }
    }
    
    private fun validateZplPrinterReady(connection: IDeviceConnection) {
        validatePrinterReady(connection)
        // Add ZPL-specific validation if needed
    }
    
    private fun parseIpAddress(ipString: String): ByteArray {
        val parts = ipString.split('.')
        if (parts.size != 4) {
            throw IllegalArgumentException("Invalid IP address format: $ipString")
        }
        
        return try {
            byteArrayOf(
                parts[0].toInt().toByte(),
                parts[1].toInt().toByte(),
                parts[2].toInt().toByte(),
                parts[3].toInt().toByte()
            )
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid IP address format: $ipString", e)
        }
    }
    
    private fun mapStatusCodeToString(status: Int): String = when (status) {
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
