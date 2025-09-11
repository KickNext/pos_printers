package com.kicknext.pos_printers.validation

import com.kicknext.pos_printers.gen.*

/**
 * Validates input parameters for printer operations
 */
object ParameterValidator {
    
    /**
     * Validates printer connection parameters
     */
    fun validatePrinterConnection(printer: PrinterConnectionParamsDTO) {
        require(printer.id.isNotBlank()) { "Printer ID cannot be empty" }
        
        when (printer.connectionType) {
            PosPrinterConnectionType.USB -> {
                val usbParams = printer.usbParams
                    ?: throw IllegalArgumentException("USB parameters are required for USB connection")
                validateUsbParams(usbParams)
            }
            PosPrinterConnectionType.NETWORK -> {
                val networkParams = printer.networkParams
                    ?: throw IllegalArgumentException("Network parameters are required for network connection")
                validateNetworkParams(networkParams)
            }
        }
    }
    
    /**
     * Validates USB connection parameters
     */
    private fun validateUsbParams(params: UsbParams) {
        require(params.vendorId > 0) { "Vendor ID must be positive" }
        require(params.productId > 0) { "Product ID must be positive" }
        // Serial number can be null for some devices
    }
    
    /**
     * Validates network connection parameters
     */
    private fun validateNetworkParams(params: NetworkParams) {
        require(params.ipAddress.isNotBlank()) { "IP address cannot be empty" }
        require(isValidIpAddress(params.ipAddress)) { "Invalid IP address format: ${params.ipAddress}" }
    }
    
    /**
     * Validates network settings
     */
    fun validateNetworkSettings(settings: NetworkParams) {
        require(settings.ipAddress.isNotBlank()) { "IP address cannot be empty" }
        require(isValidIpAddress(settings.ipAddress)) { "Invalid IP address format: ${settings.ipAddress}" }
        
        settings.mask?.let { mask ->
            require(mask.isNotBlank()) { "Subnet mask cannot be empty when provided" }
            require(isValidIpAddress(mask)) { "Invalid subnet mask format: $mask" }
        }
        
        settings.gateway?.let { gateway ->
            require(gateway.isNotBlank()) { "Gateway cannot be empty when provided" }
            require(isValidIpAddress(gateway)) { "Invalid gateway format: $gateway" }
        }
        
        settings.macAddress?.let { mac ->
            require(mac.isNotBlank()) { "MAC address cannot be empty when provided" }
            require(isValidMacAddress(mac)) { "Invalid MAC address format: $mac" }
        }
    }
    
    /**
     * Validates print data
     */
    fun validatePrintData(data: ByteArray, width: Long) {
        require(data.isNotEmpty()) { "Print data cannot be empty" }
        require(width > 0) { "Print width must be positive" }
        require(width <= 832) { "Print width too large (max 832 dots for most thermal printers)" }
    }
    
    /**
     * Validates HTML content
     */
    fun validateHtmlContent(html: String, width: Long) {
        require(html.isNotBlank()) { "HTML content cannot be empty" }
        require(width > 0) { "Print width must be positive" }
        require(width <= 832) { "Print width too large (max 832 dots for most thermal printers)" }
        
        // Basic HTML validation
        val trimmedHtml = html.trim()
        require(trimmedHtml.contains("<") && trimmedHtml.contains(">")) { 
            "Invalid HTML content: must contain HTML tags" 
        }
    }
    
    /**
     * Validates MAC address configuration parameters
     */
    fun validateMacConfiguration(macAddress: String, ipAddress: String, mask: String, gateway: String) {
        require(isValidMacAddress(macAddress)) { "Invalid MAC address format: $macAddress" }
        require(isValidIpAddress(ipAddress)) { "Invalid IP address format: $ipAddress" }
        require(isValidIpAddress(mask)) { "Invalid subnet mask format: $mask" }
        require(isValidIpAddress(gateway)) { "Invalid gateway format: $gateway" }
    }
    
    /**
     * Validates TCP port for discovery
     */
    fun validateTcpPort(port: Long) {
        require(port in 1..65535) { "Port must be between 1 and 65535, got: $port" }
    }
    
    /**
     * Checks if string is a valid IPv4 address
     */
    private fun isValidIpAddress(ip: String): Boolean {
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
     * Checks if string is a valid MAC address
     */
    private fun isValidMacAddress(mac: String): Boolean {
        val macPattern = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
        return macPattern.matches(mac)
    }
    
    /**
     * Validates timeout values
     */
    fun validateTimeout(timeoutMs: Long) {
        require(timeoutMs > 0) { "Timeout must be positive" }
        require(timeoutMs <= 30000) { "Timeout too large (max 30 seconds)" }
    }
}
