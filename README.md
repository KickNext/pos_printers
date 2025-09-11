# POS Printers Plugin

A comprehensive Flutter plugin for POS (Point of Sale) and label printers, supporting both ESC/POS receipt printers and ZPL label printers with USB and network connectivity.

## Features

- **Multiple Printer Types**: Support for ESC/POS receipt printers and ZPL label printers
- **Multiple Connection Types**: USB, network (TCP), and SDK-based discovery
- **HTML Printing**: Convert HTML to printable bitmaps for both receipt and label printers  
- **Raw Data Printing**: Send raw ESC/POS, ZPL, CPCL, and TSPL commands
- **Printer Discovery**: Automatic discovery of USB and network printers
- **Connection Management**: Robust connection handling with retry logic and proper cleanup
- **Paper Sizes**: Support for 58mm (416 dots) and 80mm (576 dots) paper widths
- **Network Configuration**: Configure printer network settings via USB or UDP
- **Real-time Events**: Monitor printer attach/detach events and discovery status
- **Stress Testing**: Built-in support for concurrent printing operations

## Installation

Add this to your package's `pubspec.yaml` file:

```yaml
dependencies:
  pos_printers:
    git:
      url: https://github.com/your-repo/pos_printers.git
```

## Quick Start

```dart
import 'package:pos_printers/pos_printers.dart';

// Create manager instance
final manager = PosPrintersManager();

// Discover printers
final printerStream = manager.findPrinters(filter: null);
printerStream.listen((printer) {
  print('Found printer: ${printer.id}');
});

// Print HTML receipt on 80mm paper
await manager.printEscHTML(
  printer,
  '<html><body><h1>Receipt</h1><p>Total: $10.50</p></body></html>',
  PaperSize.mm80.value
);
```

## API Reference

### Core Classes

#### PosPrintersManager

Main class for managing printer operations.

```dart
final manager = PosPrintersManager();
```

**Methods:**

- `Stream<PrinterConnectionParamsDTO> findPrinters({PrinterDiscoveryFilter? filter})`
- `Future<void> awaitDiscoveryComplete()`
- `Future<StatusResult> getPrinterStatus(PrinterConnectionParamsDTO printer)`
- `Future<StringResult> getPrinterSN(PrinterConnectionParamsDTO printer)`
- `Future<void> openCashBox(PrinterConnectionParamsDTO printer)`
- `Future<void> printEscHTML(PrinterConnectionParamsDTO printer, String html, int width)`
- `Future<void> printEscRawData(PrinterConnectionParamsDTO printer, Uint8List data, int width)`
- `Future<void> printZplHtml(PrinterConnectionParamsDTO printer, String html, int width)`
- `Future<void> printZplRawData(PrinterConnectionParamsDTO printer, Uint8List data, int width)`
- `Future<ZPLStatusResult> getZPLPrinterStatus(PrinterConnectionParamsDTO printer)`
- `Future<CheckPrinterLanguageResponse> checkPrinterLanguage(PrinterConnectionParamsDTO printer)`
- `Future<void> setNetSettings(PrinterConnectionParamsDTO printer, NetworkParams netSettings)`
- `Future<void> configureNetViaUDP(String macAddress, NetworkParams netSettings)`
- `void dispose()`

**Streams:**

- `Stream<PrinterConnectionParamsDTO> discoveryStream` - Discovered printers during scanning
- `Stream<PrinterConnectionEvent> connectionEvents` - Printer attach/detach events

#### PrinterConnectionParamsDTO

Represents printer connection parameters.

```dart
final printer = PrinterConnectionParamsDTO(
  id: 'printer_001',
  connectionType: PosPrinterConnectionType.network,
  usbParams: null,
  networkParams: NetworkParams(
    ipAddress: '192.168.1.100',
    mask: '255.255.255.0',
    gateway: '192.168.1.1',
    macAddress: 'AA:BB:CC:DD:EE:FF',
    dhcp: false,
  ),
);
```

**Properties:**
- `String id` - Unique printer identifier
- `PosPrinterConnectionType connectionType` - Connection type (usb, network)
- `UsbParams? usbParams` - USB connection parameters (if USB)
- `NetworkParams? networkParams` - Network connection parameters (if network)

#### NetworkParams

Network connection parameters.

```dart
final networkParams = NetworkParams(
  ipAddress: '192.168.1.100',
  mask: '255.255.255.0', 
  gateway: '192.168.1.1',
  macAddress: 'AA:BB:CC:DD:EE:FF',
  dhcp: false,
);
```

#### UsbParams

USB connection parameters.

```dart
final usbParams = UsbParams(
  vendorId: 0x0416,
  productId: 0x5011,
  serialNumber: 'ABC123',
  manufacturer: 'Xprinter',
  productName: 'XP-80C',
);
```

### Printer Discovery

#### Basic Discovery

```dart
// Discover all available printers
final printerStream = manager.findPrinters(filter: null);

printerStream.listen(
  (printer) => print('Found: ${printer.id}'),
  onDone: () => print('Discovery complete'),
  onError: (error) => print('Discovery error: $error'),
);

// Wait for discovery to complete
await manager.awaitDiscoveryComplete();
```

#### Filtered Discovery

```dart
// Discover only USB ESC/POS printers
final filter = PrinterDiscoveryFilter(
  connectionTypes: [DiscoveryConnectionType.usb],
  languages: [PrinterLanguage.esc],
);

final printerStream = manager.findPrinters(filter: filter);
```

#### Discovery Connection Types

- `DiscoveryConnectionType.usb` - USB connected printers
- `DiscoveryConnectionType.sdk` - Network printers via Xprinter SDK
- `DiscoveryConnectionType.tcp` - Network printers via TCP (port 9100)

#### Real-time Events

```dart
// Monitor printer connections
manager.connectionEvents.listen((event) {
  switch (event.type) {
    case PrinterConnectionEventType.attached:
      print('Printer attached: ${event.printer?.id}');
      break;
    case PrinterConnectionEventType.detached:
      print('Printer detached: ${event.printer?.id}');
      break;
  }
});
```

### Printing Operations

#### HTML Printing

Print HTML content converted to bitmap.

**ESC/POS Receipt Printers:**
```dart
final html = '''
<html>
<body style="font-family: monospace; text-align: center;">
  <h2>STORE RECEIPT</h2>
  <hr>
  <table width="100%">
    <tr><td>Coffee</td><td align="right">$3.50</td></tr>
    <tr><td>Sandwich</td><td align="right">$7.00</td></tr>
  </table>
  <hr>
  <p><b>Total: $10.50</b></p>
  <p>Thank you!</p>
</body>
</html>
''';

await manager.printEscHTML(printer, html, PaperSize.mm80.value);
```

**ZPL Label Printers:**
```dart
final html = '''
<html>
<body style="font-family: Arial; padding: 10px;">
  <div style="border: 2px solid black; padding: 20px;">
    <h1>SHIPPING LABEL</h1>
    <p>To: John Doe</p>
    <p>123 Main Street</p>
    <p>City, State 12345</p>
    <div style="margin-top: 20px; font-size: 24px;">
      <b>Tracking: ABC123456789</b>
    </div>
  </div>
</body>
</html>
''';

await manager.printZplHtml(printer, html, PaperSize.mm80.value);
```

#### Raw Data Printing

Send raw printer commands directly.

**ESC/POS Commands:**
```dart
final escCommands = [
  0x1B, 0x40, // Initialize
  0x1B, 0x61, 0x01, // Center align
  ...utf8.encode('Hello World'),
  0x0A, // Line feed
  0x1D, 0x56, 0x42, 0x00, // Cut paper
];

await manager.printEscRawData(
  printer, 
  Uint8List.fromList(escCommands), 
  PaperSize.mm80.value
);
```

**ZPL Commands:**
```dart
final zplCommands = '''
^XA
^CFD,30
^FO50,50^FDHello World^FS
^XZ
''';

await manager.printZplRawData(
  printer,
  Uint8List.fromList(utf8.encode(zplCommands)),
  PaperSize.mm80.value
);
```

### Paper Sizes

Predefined paper sizes with dot widths:

```dart
enum PaperSize {
  mm58(416),  // 58mm paper = 416 dots
  mm80(576);  // 80mm paper = 576 dots
}

// Usage
await manager.printEscHTML(printer, html, PaperSize.mm58.value);
await manager.printEscHTML(printer, html, PaperSize.mm80.value);
```

### Printer Status and Information

#### Get Printer Status

```dart
final status = await manager.getPrinterStatus(printer);
if (status.success) {
  print('Printer status: ${status.status}');
} else {
  print('Error: ${status.errorMessage}');
}
```

#### Get Serial Number

```dart
final snResult = await manager.getPrinterSN(printer);
if (snResult.success) {
  print('Serial Number: ${snResult.value}');
} else {
  print('Error: ${snResult.errorMessage}');
}
```

#### Check Printer Language

```dart
final response = await manager.checkPrinterLanguage(printer);
print('Language: ${response.printerLanguage}'); // PrinterLanguage.esc or PrinterLanguage.zpl
```

#### ZPL Printer Status

```dart
final zplStatus = await manager.getZPLPrinterStatus(printer);
if (zplStatus.success) {
  print('ZPL Status Code: ${zplStatus.code}');
  // Status codes: 00-80 (see ZPL documentation)
} else {
  print('Error: ${zplStatus.errorMessage}');
}
```

### Network Configuration

#### Configure via USB Connection

```dart
final newSettings = NetworkParams(
  ipAddress: '192.168.1.200',
  mask: '255.255.255.0',
  gateway: '192.168.1.1', 
  macAddress: null, // Will be detected
  dhcp: false,
);

await manager.setNetSettings(usbPrinter, newSettings);
```

#### Configure via UDP Broadcast

```dart
final settings = NetworkParams(
  ipAddress: '192.168.1.201',
  mask: '255.255.255.0',
  gateway: '192.168.1.1',
  macAddress: 'AA:BB:CC:DD:EE:FF',
  dhcp: false,
);

await manager.configureNetViaUDP('AA:BB:CC:DD:EE:FF', settings);
```

### Cash Drawer Control

```dart
// Open cash drawer connected to printer
await manager.openCashBox(printer);
```

### Error Handling

All async methods can throw exceptions. Wrap in try-catch blocks:

```dart
try {
  await manager.printEscHTML(printer, html, PaperSize.mm80.value);
  print('Print successful');
} catch (e) {
  print('Print failed: $e');
}
```

### Advanced Usage

#### Stress Testing / Concurrent Printing

```dart
Future<void> stressTest() async {
  final printers = <PrinterConnectionParamsDTO>[printer1, printer2];
  final futures = <Future<void>>[];
  
  // Send 10 print jobs simultaneously
  for (int i = 0; i < 10; i++) {
    final html = '<html><body><h1>Receipt #$i</h1></body></html>';
    futures.add(manager.printEscHTML(
      printers[i % printers.length],
      html,
      PaperSize.mm80.value,
    ));
  }
  
  // Wait for all to complete
  await Future.wait(futures);
  print('All prints completed');
}
```

#### Custom Connection Handling

```dart
class PrinterService {
  final PosPrintersManager _manager;
  final Map<String, PrinterConnectionParamsDTO> _connectedPrinters = {};
  
  PrinterService() : _manager = PosPrintersManager() {
    _setupEventListeners();
  }
  
  void _setupEventListeners() {
    _manager.connectionEvents.listen((event) {
      switch (event.type) {
        case PrinterConnectionEventType.attached:
          _connectedPrinters[event.printer!.id] = event.printer!;
          break;
        case PrinterConnectionEventType.detached:
          _connectedPrinters.remove(event.printer!.id);
          break;
      }
    });
  }
  
  Future<void> printToAll(String html) async {
    final futures = _connectedPrinters.values.map((printer) =>
        _manager.printEscHTML(printer, html, PaperSize.mm80.value));
    await Future.wait(futures);
  }
}
```

### Complete Example

See the `example/` directory for a full working application that demonstrates:

- Printer discovery with real-time updates
- HTML and raw data printing
- Stress testing with concurrent operations
- Error handling and retry logic
- Network printer configuration
- Multi-language support (ESC/POS and ZPL)

### Enums Reference

#### PosPrinterConnectionType
- `usb` - USB connection
- `network` - Network TCP/IP connection

#### PrinterLanguage  
- `esc` - ESC/POS commands (receipt printers)
- `zpl` - ZPL commands (label printers)

#### DiscoveryConnectionType
- `usb` - Discover USB printers
- `sdk` - Discover via Xprinter SDK
- `tcp` - Discover via TCP scan (port 9100)

#### PrinterConnectionEventType
- `attached` - Printer was connected
- `detached` - Printer was disconnected

## Architecture

The plugin uses a modular architecture with:

- **Pigeon-generated bindings** for type-safe Flutter-Android communication
- **Connection pooling** with automatic retry and cleanup
- **Thread-safe operations** using Kotlin coroutines
- **Event-driven discovery** with real-time printer detection
- **Robust error handling** with detailed error messages

## Platform Support

- **Android**: Full support for USB and network printers
- **iOS**: Not supported (Android-only plugin)

## Building

To regenerate the Pigeon bindings after modifying `pigeons/pos_printers.dart`:

```shell
dart run pigeon --input pigeons/pos_printers.dart
```

## License

This project is licensed under the MIT License - see the LICENSE file for details.