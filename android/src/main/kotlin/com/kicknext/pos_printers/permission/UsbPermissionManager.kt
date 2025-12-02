package com.kicknext.pos_printers.permission

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.kicknext.pos_printers.gen.UsbParams
import com.kicknext.pos_printers.gen.UsbPermissionResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Менеджер USB-разрешений для Android.
 * 
 * В Android для работы с USB-устройствами необходимо:
 * 1. Найти устройство через UsbManager
 * 2. Запросить разрешение пользователя через requestPermission()
 * 3. Дождаться ответа через BroadcastReceiver
 * 4. Только после этого можно открывать соединение с устройством
 * 
 * Этот класс инкапсулирует всю логику запроса и проверки USB-разрешений,
 * обеспечивая потокобезопасность и корректную обработку коллбэков.
 * 
 * @param context Контекст приложения для регистрации BroadcastReceiver
 * @param usbManager Системный менеджер USB
 */
class UsbPermissionManager(
    private val context: Context,
    private val usbManager: UsbManager
) {
    companion object {
        private const val TAG = "UsbPermissionManager"
        
        /**
         * Уникальный action для Intent, который будет получен при ответе пользователя
         * на запрос разрешения USB. Должен быть уникальным для пакета приложения.
         */
        private const val ACTION_USB_PERMISSION = "com.kicknext.pos_printers.USB_PERMISSION"
        
        /**
         * Таймаут ожидания ответа пользователя (в миллисекундах).
         * После этого времени запрос считается неудачным.
         */
        private const val PERMISSION_TIMEOUT_MS = 60_000L
    }
    
    /**
     * Хранилище активных коллбэков для запросов разрешений.
     * Ключ - уникальный идентификатор устройства (VID:PID:Serial)
     */
    private val pendingCallbacks = ConcurrentHashMap<String, (UsbPermissionResult) -> Unit>()
    
    /**
     * Флаг инициализации ресивера
     */
    @Volatile
    private var isReceiverRegistered = false
    
    /**
     * BroadcastReceiver для обработки ответов на запросы USB-разрешений.
     * 
     * Android вызывает этот ресивер когда пользователь разрешает или запрещает
     * доступ к USB-устройству.
     */
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            
            // Используем совместимый способ получения Parcelable для Android 13+
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            
            if (device == null) {
                Log.w(TAG, "USB permission response received but device is null")
                return
            }
            
            val deviceKey = createDeviceKey(device.vendorId, device.productId, getSerialNumber(device))
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            
            Log.d(TAG, "USB permission response: device=$deviceKey, granted=$granted")
            
            // Извлекаем и вызываем коллбэк
            val callback = pendingCallbacks.remove(deviceKey)
            if (callback != null) {
                val result = UsbPermissionResult(
                    granted = granted,
                    errorMessage = if (granted) null else "User denied USB permission",
                    deviceInfo = "${device.manufacturerName ?: "Unknown"} ${device.productName ?: device.deviceName}"
                )
                callback(result)
            } else {
                Log.w(TAG, "No pending callback for device $deviceKey")
            }
        }
    }
    
    /**
     * Регистрирует BroadcastReceiver для получения ответов на запросы разрешений.
     * Должен вызываться при инициализации плагина.
     */
    @Synchronized
    fun register() {
        if (isReceiverRegistered) return
        
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        
        // Android 13+ (API 33) требует указания RECEIVER_NOT_EXPORTED для безопасности
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }
        
        isReceiverRegistered = true
        Log.d(TAG, "USB permission receiver registered")
    }
    
    /**
     * Отменяет регистрацию BroadcastReceiver.
     * Должен вызываться при отключении плагина.
     */
    @Synchronized
    fun unregister() {
        if (!isReceiverRegistered) return
        
        try {
            context.unregisterReceiver(usbPermissionReceiver)
            isReceiverRegistered = false
            
            // Отменяем все ожидающие запросы
            pendingCallbacks.forEach { (key, callback) ->
                callback(UsbPermissionResult(
                    granted = false,
                    errorMessage = "Permission manager was unregistered",
                    deviceInfo = key
                ))
            }
            pendingCallbacks.clear()
            
            Log.d(TAG, "USB permission receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering USB permission receiver: ${e.message}")
        }
    }
    
    /**
     * Проверяет, есть ли разрешение на использование USB-устройства.
     * Не показывает диалог пользователю.
     * 
     * @param usbParams Параметры USB-устройства (vendorId, productId, serialNumber)
     * @return UsbPermissionResult с информацией о текущем состоянии разрешения
     */
    fun hasPermission(usbParams: UsbParams): UsbPermissionResult {
        val device = findDevice(usbParams)
            ?: return UsbPermissionResult(
                granted = false,
                errorMessage = "USB device not found (VID=${usbParams.vendorId}, PID=${usbParams.productId})",
                deviceInfo = null
            )
        
        val hasPermission = usbManager.hasPermission(device)
        return UsbPermissionResult(
            granted = hasPermission,
            errorMessage = if (hasPermission) null else "USB permission not granted. Call requestUsbPermission() first.",
            deviceInfo = "${device.manufacturerName ?: "Unknown"} ${device.productName ?: device.deviceName}"
        )
    }
    
    /**
     * Запрашивает разрешение на использование USB-устройства у пользователя.
     * 
     * Если разрешение уже есть - сразу возвращает успех.
     * Если нет - показывает системный диалог запроса разрешения.
     * 
     * ВАЖНО: Этот метод должен вызываться из Main thread, так как
     * PendingIntent требует главного потока для корректной работы.
     * 
     * @param usbParams Параметры USB-устройства
     * @param callback Коллбэк, который будет вызван с результатом запроса
     */
    fun requestPermission(usbParams: UsbParams, callback: (UsbPermissionResult) -> Unit) {
        // Проверяем, что ресивер зарегистрирован
        if (!isReceiverRegistered) {
            register()
        }
        
        // Ищем устройство
        val device = findDevice(usbParams)
        if (device == null) {
            callback(UsbPermissionResult(
                granted = false,
                errorMessage = "USB device not found (VID=${usbParams.vendorId}, PID=${usbParams.productId})",
                deviceInfo = null
            ))
            return
        }
        
        // Проверяем, есть ли уже разрешение
        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "USB permission already granted for device ${device.deviceName}")
            callback(UsbPermissionResult(
                granted = true,
                errorMessage = null,
                deviceInfo = "${device.manufacturerName ?: "Unknown"} ${device.productName ?: device.deviceName}"
            ))
            return
        }
        
        // Создаём ключ для устройства
        val deviceKey = createDeviceKey(device.vendorId, device.productId, getSerialNumber(device))
        
        // Проверяем, нет ли уже активного запроса для этого устройства
        if (pendingCallbacks.containsKey(deviceKey)) {
            Log.w(TAG, "Permission request already pending for device $deviceKey")
            callback(UsbPermissionResult(
                granted = false,
                errorMessage = "Permission request already in progress",
                deviceInfo = device.deviceName
            ))
            return
        }
        
        // Сохраняем коллбэк
        pendingCallbacks[deviceKey] = callback
        
        // Создаём PendingIntent для получения ответа
        val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            device.deviceId, // Используем deviceId как requestCode для уникальности
            permissionIntent,
            pendingIntentFlags
        )
        
        Log.d(TAG, "Requesting USB permission for device $deviceKey (${device.deviceName})")
        
        // Запрашиваем разрешение
        usbManager.requestPermission(device, pendingIntent)
        
        // Устанавливаем таймаут
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val timedOutCallback = pendingCallbacks.remove(deviceKey)
            if (timedOutCallback != null) {
                Log.w(TAG, "USB permission request timed out for device $deviceKey")
                timedOutCallback(UsbPermissionResult(
                    granted = false,
                    errorMessage = "Permission request timed out. Please try again.",
                    deviceInfo = device.deviceName
                ))
            }
        }, PERMISSION_TIMEOUT_MS)
    }
    
    /**
     * Ищет USB-устройство по заданным параметрам.
     * 
     * @param params Параметры для поиска (VID, PID, опционально серийный номер)
     * @return UsbDevice если найден, null если нет
     */
    private fun findDevice(params: UsbParams): UsbDevice? {
        return usbManager.deviceList.values.find { device ->
            val vidMatch = device.vendorId == params.vendorId.toInt()
            val pidMatch = device.productId == params.productId.toInt()
            
            // Серийный номер проверяем только если он указан в параметрах
            val serialMatch = params.serialNumber?.let { expectedSerial ->
                if (usbManager.hasPermission(device)) {
                    try {
                        getSerialNumber(device) == expectedSerial
                    } catch (e: SecurityException) {
                        // Не можем прочитать серийный номер без разрешения, пропускаем проверку
                        true
                    }
                } else {
                    // Без разрешения не можем проверить серийный номер
                    true
                }
            } ?: true
            
            vidMatch && pidMatch && serialMatch
        }
    }
    
    /**
     * Безопасно получает серийный номер устройства.
     * 
     * @param device USB-устройство
     * @return Серийный номер или null если недоступен
     */
    private fun getSerialNumber(device: UsbDevice): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && usbManager.hasPermission(device)) {
            try {
                device.serialNumber?.takeIf { it.isNotBlank() }
            } catch (e: SecurityException) {
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Создаёт уникальный ключ для устройства.
     * 
     * @param vendorId Vendor ID
     * @param productId Product ID
     * @param serialNumber Серийный номер (может быть null)
     * @return Строковый ключ в формате "VID:PID:SERIAL"
     */
    private fun createDeviceKey(vendorId: Int, productId: Int, serialNumber: String?): String {
        return "$vendorId:$productId:${serialNumber ?: "null"}"
    }
}
