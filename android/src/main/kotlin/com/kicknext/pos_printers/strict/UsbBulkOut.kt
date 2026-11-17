package com.kicknext.pos_printers.strict

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import net.posprinter.IDeviceConnection
import java.lang.reflect.Field

/**
 * The bulk OUT endpoint of an open SDK USB connection, written directly.
 *
 * Xprinter SDK 3.5.8 `sendSync` on USB returns the length of the array it was given, not what
 * `bulkTransfer` moved, and treats any non-negative transfer as success: a short transfer passes as a
 * full write. Strict mode writes USB through the same UsbDeviceConnection and endpoint the SDK opened,
 * so it sees the real count. The SDK class is obfuscated: its fields are found by type, and the
 * endpoint by direction and kind, never by name.
 */
internal class UsbBulkOut(private val usb: UsbDeviceConnection, private val endpoint: UsbEndpoint) {

    /** Moves [length] bytes of [buffer] from [offset]; returns the bytes moved or a negative code. */
    fun transfer(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        // The 4-argument call with a copied slice: the offset overload is not trusted on old Android.
        val slice = if (offset == 0 && length == buffer.size) buffer else buffer.copyOfRange(offset, offset + length)
        return usb.bulkTransfer(endpoint, slice, length, timeoutMs)
    }

    companion object {
        private const val TAG = "UsbBulkOut"

        /** Before Android 9 one bulk transfer moves at most this many bytes. */
        const val MAX_TRANSFER_BYTES = 16384

        /** The bulk OUT of [connection], or null when it is not an open SDK USB connection. */
        fun of(connection: IDeviceConnection): UsbBulkOut? {
            val fields = usbFields(connection.javaClass) ?: return null
            return try {
                val usb = fields.connection.get(connection) as? UsbDeviceConnection ?: return null
                val endpoint = fields.endpoints
                    .mapNotNull { it.get(connection) as? UsbEndpoint }
                    .firstOrNull {
                        it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                    } ?: return null
                UsbBulkOut(usb, endpoint)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot reach the USB connection of the SDK: ${e.message}")
                null
            }
        }

        internal class Fields(val connection: Field, val endpoints: List<Field>)

        /**
         * The field that holds the UsbDeviceConnection and the fields that hold endpoints, looked up in
         * [type] and its superclasses. Null when there is no single UsbDeviceConnection field or no
         * endpoint field (a network connection, or an SDK that changed).
         */
        internal fun usbFields(type: Class<*>): Fields? {
            val all = generateSequence(type) { it.superclass }.flatMap { it.declaredFields.asSequence() }.toList()
            val connections = all.filter { it.type == UsbDeviceConnection::class.java }
            val endpoints = all.filter { it.type == UsbEndpoint::class.java }
            if (connections.size != 1 || endpoints.isEmpty()) return null
            return try {
                connections.single().isAccessible = true
                endpoints.forEach { it.isAccessible = true }
                Fields(connections.single(), endpoints)
            } catch (e: Exception) {
                null
            }
        }
    }
}
