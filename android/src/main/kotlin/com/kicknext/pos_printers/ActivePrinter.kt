package com.kicknext.pos_printers

import android.util.Log
import com.kicknext.pos_printers.gen.POSPrintersReceiverApi
import com.kicknext.pos_printers.gen.PosPrinterConnectionType
import com.kicknext.pos_printers.gen.XPrinterDTO
import net.posprinter.IConnectListener
import net.posprinter.IDeviceConnection
import net.posprinter.POSConnect
import net.posprinter.POSPrinter

class ActivePrinter(val printerData: XPrinterDTO, private val receiver: POSPrintersReceiverApi) {
    private var deviceConnection: IDeviceConnection? = null
    private val printer: POSPrinter = POSPrinter(deviceConnection)


    private fun sendStatus(message: String, callback: ((Result<Unit>) -> Unit)? = null) {
        val defaultCallback: (Result<Unit>) -> Unit = { result ->
            result.onSuccess {
                Log.d("POSPrinters", "Сообщение успешно отправлено")
            }.onFailure { error ->
                Log.e("POSPrinters", "Ошибка при отправке сообщения: $error")
            }
        }
        val effectiveCallback = callback ?: defaultCallback
//        receiver.newInfo(message, effectiveCallback)
    }

    private val connectListener = IConnectListener { code, connInfo, msg ->
        when (code) {

            POSConnect.CONNECT_SUCCESS -> {
                println(connInfo)
                println(msg)
                sendStatus("Connect success")
            }

            POSConnect.CONNECT_FAIL -> {
                println(connInfo)
                println(msg)
                sendStatus("Connect fail")
            }

            POSConnect.CONNECT_INTERRUPT -> {
                println(connInfo)
                println(msg)
                sendStatus("Connection has disconnected")
            }

            POSConnect.SEND_FAIL -> {
                println(connInfo)
                println(msg)
                sendStatus("Send failed")
            }

            POSConnect.USB_DETACHED -> {
                println(connInfo)
                println(msg)
                sendStatus("Usb detached")
            }

            POSConnect.USB_ATTACHED -> {
                println(connInfo)
                println(msg)
                sendStatus("Usb_attached")
            }
        }
    }
}