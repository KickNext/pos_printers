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

    fun isUsbPrinter(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == 7 }

    fun getUsbSerialNumber(device: UsbDevice, usbManager: UsbManager): String? =
        if (usbManager.hasPermission(device) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { device.serialNumber } catch (_: Exception) { null }
        } else null

    // Parses a dotted-decimal IPv4 address string to a ByteArray
    fun parseData(str: String): ByteArray? {
        val arr = str.split('.')
        if (arr.size != 4) return null
        return try {
            byteArrayOf(
                arr[0].toInt().toByte(),
                arr[1].toInt().toByte(),
                arr[2].toInt().toByte(),
                arr[3].toInt().toByte()
            )
        } catch (e: Exception) {
            null
        }
    }

    // Parses MAC address strings like "00:1A:2B:3C:4D:5E" or "00-1A-2B-3C-4D-5E" to ByteArray
    fun parseMacAddress(mac: String): ByteArray? {
        val parts = mac.split(':', '-')
        if (parts.size != 6) return null
        return try {
            parts.map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: NumberFormatException) {
            null
        }
    }

    // Maps POSConst status codes to human-readable strings
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

    // Retrieves all non-loopback IPv4 network interfaces
    fun getLocalIpAddresses(): List<NetworkInfo> {
        val networks = mutableListOf<NetworkInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback || intf.displayName.contains("vir") || intf.displayName.contains("docker")) continue
                intf.interfaceAddresses.forEach { addr ->
                    val ip = addr.address
                    if (ip is Inet4Address && addr.networkPrefixLength in 1..31) {
                        networks.add(NetworkInfo(ip.hostAddress, addr.networkPrefixLength))
                    }
                }
            }
        } catch (_: Exception) {}
        return networks.distinct()
    }

    // Generates a sequence of IPv4 addresses for a given CIDR block, excluding network and broadcast
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
                    yield(InetAddress.getByAddress(bytes).hostAddress)
                }
            }
        } catch (_: Exception) {
            return null
        }
    }

    // Checks if a TCP port is open on a remote host within a timeout
    suspend fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            true
        } catch (_: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}

// Await extension for CompletableFuture
suspend fun <T> CompletableFuture<T>.await(): T = suspendCoroutine { cont ->
    this.whenComplete { result, ex ->
        if (ex == null) cont.resume(result)
        else cont.resumeWithException(ex)
    }
}