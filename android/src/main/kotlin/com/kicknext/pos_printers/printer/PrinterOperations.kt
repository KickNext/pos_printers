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
        // Стратегия по требованию: всегда отправляем N (по умолчанию 3) импульсов вне зависимости от статуса.
        // Цель – повысить шанс срабатывания слабого или «тугого» ящика.
        val attemptsCount = 3
        val delayBetweenMs = 100L // фиксированная задержка между импульсами
        repeat(attemptsCount) { i ->
            try {
                printer.openCashBox(POSConst.PIN_TWO)
                Log.d(
                    TAG,
                    "Cash drawer pulse ${i + 1}/$attemptsCount sent (nextDelay=${if (i < attemptsCount - 1) delayBetweenMs else 0}ms)"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Cash drawer pulse ${i + 1} failed: ${e.message}")
            }
            if (i < attemptsCount - 1) {
                delay(delayBetweenMs)
            }
        }
        Log.d(TAG, "Cash drawer pulses sequence finished (count=$attemptsCount, delayBetween=${delayBetweenMs}ms)")
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
     * Prints TSPL raw data
     */
    suspend fun printTsplRawData(
        connection: IDeviceConnection,
        labelCommands: ByteArray,
        width: Long
    ) = withContext(Dispatchers.IO) {
        validateTsplPrinterReady(connection)
        
        val tsplPrinter = TSPLPrinter(connection)
        tsplPrinter.sendData(labelCommands)
        
        Log.d(TAG, "TSPL raw data printed successfully, ${labelCommands.size} bytes")
    }
    
    /**
     * Prints HTML as TSPL label
     */
    suspend fun printTsplHtml(
        connection: IDeviceConnection,
        html: String,
        width: Long
    ) = withContext(Dispatchers.IO) {
        validateTsplPrinterReady(connection)
        
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
                        Log.d(TAG, "TSPL HTML to Bitmap conversion successful, size: ${bitmapResult.width}x${bitmapResult.height}")
                        continuation.resume(bitmapResult)
                    } else {
                        continuation.resumeWith(kotlin.Result.failure(IllegalStateException("Generated bitmap is null")))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "TSPL HTML to Bitmap conversion failed", e)
                    continuation.resumeWith(kotlin.Result.failure(e))
                }
            }
        }
        
        val tsplPrinter = TSPLPrinter(connection)
        tsplPrinter.sizeMm(width.toInt().toDouble(), (bitmap.height / (width.toInt() / 58)).toDouble()) // Approximate height based on 58mm width
        tsplPrinter.gapMm(2.0, 0.0) // Default gap settings
        tsplPrinter.cls()
        tsplPrinter.bitmap(0, 0, TSPLConst.BMP_MODE_OVERWRITE, width.toInt(), bitmap, AlgorithmType.Dithering)
        tsplPrinter.print(1)
        
        Log.d(TAG, "TSPL HTML printed successfully")
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
     * Gets TSPL printer status
     */
    suspend fun getTsplPrinterStatus(connection: IDeviceConnection): TSPLStatusResult = withContext(Dispatchers.IO) {
        val tsplPrinter = TSPLPrinter(connection)
        
        val statusCode = suspendCoroutine<Int> { continuation ->
            try {
                tsplPrinter.printerStatus(STATUS_CHECK_TIMEOUT_MS.toInt()) { code ->
                    continuation.resume(code)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting TSPL printer status", e)
                continuation.resume(-1) // Error status
            }
        }
        
        val isSuccess = statusCode == 0x00
        val errorMessage = if (isSuccess) null else mapTsplStatusCodeToString(statusCode)
        
        TSPLStatusResult(
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
    
    private fun validatePrinterReady(connection: IDeviceConnection) {
        // Add any general printer validation logic here
        // For now, just ensure connection is not null
        requireNotNull(connection) { "Printer connection is null" }
    }
    
    private fun validateZplPrinterReady(connection: IDeviceConnection) {
        validatePrinterReady(connection)
        // Add ZPL-specific validation if needed
    }
    
    private fun validateTsplPrinterReady(connection: IDeviceConnection) {
        validatePrinterReady(connection)
        // Add TSPL-specific validation if needed
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
    
    private fun mapTsplStatusCodeToString(status: Int): String = when (status) {
        0x00 -> "Normal"
        0x01 -> "Head opened"
        0x02 -> "Paper Jam"
        0x03 -> "Paper Jam and head opened"
        0x04 -> "Out of paper"
        0x05 -> "Out of paper and head opened"
        0x08 -> "Out of ribbon"
        0x09 -> "Out of ribbon and head opened"
        0x0A -> "Out of ribbon and paper jam"
        0x0B -> "Out of ribbon, paper jam and head opened"
        0x0C -> "Out of ribbon and out of paper"
        0x0D -> "Out of ribbon, out of paper and head opened"
        0x10 -> "Pause"
        0x20 -> "Printing"
        0x80 -> "Other error"
        -1 -> "Receive timeout"
        else -> "TSPL status code: 0x${status.toString(16).uppercase()}"
    }
}
