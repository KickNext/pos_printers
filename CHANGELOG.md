## 0.8.0

### Changes

- Xprinter Android SDK updated from 3.3.83 to 3.5.8 (`android/libs`: manifest, `classes.jar`, native libraries for all four ABIs).
- `NetworkParams.modelName` (optional, last field): model name reported by the printer in the UDP search; null when the printer does not report one.
- `findNetworkPrinterByIp(ipAddress, {timeout})`: UDP search by IPv4, returns the printer's MAC, mask, gateway, DHCP flag and model name, or null if nobody answered within the timeout (3 s by default). Useful for printers found by TCP scan, which have no MAC.
- SDK (UDP) discovery is now bounded to 3 s (`searchNetDeviceWithTimeout`); the finish event is sent after the search window closes, so all answers reach the stream first. IPv6 answers are skipped.
- SDK 3.5.8 removed `IPOSListener`, `IDeviceConnection.connect/connectSync(String, IPOSListener)`, `IDeviceConnection.isConnect(byte[], cb)` and `POSConnect.connectMac`. The plugin does not use any of them, so printing and connection behavior is unchanged.

## 0.6.0

### 🚀 New Features

- **USB Permission Management (Critical Fix)**
  - Added `requestUsbPermission(UsbParams)` - Requests USB permission from user via system dialog
  - Added `hasUsbPermission(UsbParams)` - Checks if USB permission is already granted
  - Added `withUsbPermission<T>(printer, operation)` - Convenience wrapper that auto-requests permission before operations
  - Added `UsbPermissionResult` class with `granted`, `errorMessage`, and `deviceInfo` fields
  - Added `UsbPermissionDeniedException` for handling permission denial
  - **This fixes "USB permission denied for device /dev/bus/XXX/XXX" errors**

### 🔧 Improvements

- **Code Refactoring & Optimization**
  - Created `UsbPermissionManager` for centralized USB permission handling with proper Android lifecycle
  - Fixed deprecated `getParcelableExtra` calls for Android 13+ compatibility
  - Improved null-safety in `Utils.kt` for IP address handling
  - Added comprehensive KDoc/JSDoc comments for all new APIs
  - Updated example app with USB permission request button and `withUsbPermission` usage

### 📖 Documentation

- Added detailed documentation for USB permission workflow in Dart API
- Updated example app to demonstrate USB permission handling

### ⚠️ Migration Guide

Before printing to USB printers, you must now request permission:

```dart
// Option 1: Manual permission request
final result = await manager.requestUsbPermission(printer.usbParams!);
if (result.granted) {
  await manager.printEscHTML(printer, html, 384);
}

// Option 2: Automatic with withUsbPermission (recommended)
await manager.withUsbPermission(printer, () async {
  await manager.printEscHTML(printer, html, 384);
});
```

---

## 0.5.0

- **BREAKING CHANGES:**
  - Removed printer language detection functionality
    - Removed `checkPrinterLanguage()` method from API
    - Removed `CheckPrinterLanguageResponse` class
    - Removed `PrinterLanguage` enum
  - Updated `PrinterDiscoveryFilter` to remove language filtering
    - Removed `languages` parameter - now only filters by `connectionTypes`
    - `DiscoveredPrinterDTO` no longer includes `printerLanguage` field
  - Simplified example app to single-file stress test application
  - Updated documentation to reflect API changes
  - Added TSPL (TSC Printer Language) support
    - `printTsplRawData` - Send raw TSPL commands to printer
    - `printTsplHtml` - Convert HTML to bitmap and print on TSPL label printer
    - `getTSPLPrinterStatus` - Get TSPL printer status with detailed error codes
  - Added TSPL status code mapping (0x00-0x80)
- Documentation updates
  - Added TSPL usage guide with examples
  - Updated README with TSPL examples
  - Added TSPL example file in example app

## 0.1.0

- Initial release with ESC/POS and ZPL support
- USB and network connectivity
- HTML to bitmap conversion
- Printer discovery and status checking
