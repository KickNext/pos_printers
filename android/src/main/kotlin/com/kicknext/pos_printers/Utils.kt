package com.kicknext.pos_printers

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import net.posprinter.POSConst

// Represents a network interface's IPv4 address and prefix length
data class NetworkInfo(val ipAddress: String, val prefixLength: Short)

object Utils {

    fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    /**
     * Checks if USB device is a printer based on interface class
     * Interface class 7 = Printer class
     */
    fun isUsbPrinter(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == 7 }

    /**
     * Gets USB device serial number with proper permission and version checks
     */
    fun getUsbSerialNumber(device: UsbDevice, usbManager: UsbManager): String? =
        if (usbManager.hasPermission(device) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { 
                device.serialNumber?.takeIf { it.isNotBlank() }
            } catch (_: Exception) { 
                null 
            }
        } else null

    /**
     * Parses a dotted-decimal IPv4 address string to a ByteArray
     * Validates format and range (0-255 for each octet)
     */
    fun parseData(str: String): ByteArray? {
        val arr = str.split('.')
        if (arr.size != 4) return null
        return try {
            val bytes = arr.map { part ->
                val num = part.toInt()
                if (num !in 0..255) return null
                num.toByte()
            }
            bytes.toByteArray()
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Parses MAC address strings like "00:1A:2B:3C:4D:5E" or "00-1A-2B-3C-4D-5E" to ByteArray
     * Supports both colon and hyphen separators
     */
    fun parseMacAddress(mac: String): ByteArray? {
        val cleanMac = mac.replace(":", "").replace("-", "")
        if (cleanMac.length != 12) return null
        
        return try {
            (0 until cleanMac.length step 2).map { i ->
                cleanMac.substring(i, i + 2).toInt(16).toByte()
            }.toByteArray()
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Maps POSConst status codes to human-readable strings
     * Includes common error codes and timeout scenarios
     */
    fun mapStatusCodeToString(status: Int): String = when (status) {
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

    /**
     * Retrieves all non-loopback IPv4 network interfaces
     * Filters out virtual and Docker interfaces
     */
    fun getLocalIpAddresses(): List<NetworkInfo> {
        val networks = mutableListOf<NetworkInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback || 
                    intf.displayName.contains("vir", ignoreCase = true) || 
                    intf.displayName.contains("docker", ignoreCase = true)) continue
                    
                intf.interfaceAddresses.forEach { addr ->
                    val ip = addr.address
                    if (ip is Inet4Address && addr.networkPrefixLength in 1..31) {
                        // hostAddress может вернуть null в редких случаях, используем orEmpty()
                        val hostAddr = ip.hostAddress.orEmpty()
                        if (hostAddr.isNotEmpty()) {
                            networks.add(NetworkInfo(hostAddr, addr.networkPrefixLength))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("Utils", "Error getting local IP addresses: ${e.message}")
        }
        return networks.distinct()
    }

    /**
     * Generates a sequence of IPv4 addresses for a given CIDR block
     * Excludes network and broadcast addresses
     */
    fun getIpRangeFromCidr(ipAddress: String, prefixLength: Short): Sequence<String>? {
        try {
            val ipBytes = InetAddress.getByName(ipAddress).address
            if (ipBytes.size != 4) return null
            
            val ipInt = ipBytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            val maskInt = (-1 shl (32 - prefixLength))
            val networkInt = ipInt and maskInt
            val broadcastInt = networkInt or maskInt.inv()
            
            val startIp = networkInt + 1
            val endIp = broadcastInt - 1
            
            if (startIp > endIp) return emptySequence()
            
            return sequence {
                for (i in startIp..endIp) {
                    val bytes = byteArrayOf(
                        (i shr 24 and 0xFF).toByte(),
                        (i shr 16 and 0xFF).toByte(),
                        (i shr 8 and 0xFF).toByte(),
                        (i and 0xFF).toByte()
                    )
                    // hostAddress может вернуть null, безопасно обрабатываем
                    val addr = InetAddress.getByAddress(bytes).hostAddress
                    if (addr != null) yield(addr)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("Utils", "Error generating IP range: ${e.message}")
            return null
        }
    }

    /**
     * Checks if a TCP port is open on a remote host within a timeout
     * Uses socket connection attempt
     */
    suspend fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            true
        } catch (e: Exception) {
            false
        } finally {
            try { 
                socket?.close() 
            } catch (e: Exception) {
                android.util.Log.w("Utils", "Error closing socket: ${e.message}")
            }
        }
    }
    
    /**
     * Validates IP address format
     */
    fun isValidIpAddress(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        
        return parts.all { part ->
            try {
                val num = part.toInt()
                num in 0..255
            } catch (e: NumberFormatException) {
                false
            }
        }
    }
    
    /**
     * Validates MAC address format (supports colon and hyphen separators)
     */
    fun isValidMacAddress(mac: String): Boolean {
        val macPattern = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
        return macPattern.matches(mac)
    }
    
    /**
     * Converts byte array to hex string for debugging
     */
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }
    
    /**
     * Safely gets device name for USB device
     */
    fun getUsbDeviceName(device: UsbDevice): String {
        return try {
            device.deviceName ?: "Unknown USB Device"
        } catch (e: Exception) {
            "USB Device (${device.vendorId}:${device.productId})"
        }
    }
}

/**
 * Await extension for CompletableFuture
 */
suspend fun <T> CompletableFuture<T>.await(): T = suspendCoroutine { cont ->
    this.whenComplete { result, ex ->
        if (ex == null) cont.resume(result)
        else cont.resumeWithException(ex)
    }
}