# pos_printers

**Flutter plugin for Android POS printers**

Supports receipt printers (ESC/POS) and label printers (ZPL) over USB and network.

---

## Contents
1. [Overview](#overview)
2. [Key Features](#key-features)
3. [Installation](#installation)
4. [Usage](#usage)
5. [API Reference](#api-reference)
6. [Tips](#tips)
7. [Authors](#authors)

---

## Overview

`pos_printers` wraps the native Xprinter SDK on Android and provides a single Dart API for:

- Discovering USB and network printers
- Connecting to multiple printers simultaneously
- Printing receipts (HTML or raw ESC/POS bytes)
- Printing labels using ZPL (HTML or raw ZPL commands)
- Querying printer status and serial number
- Opening cash drawer and configuring network settings

The core class is `PosPrintersManager`, which exposes streams and methods for all operations.

---

## Key Features

- **Printer Discovery**
  - USB: scans devices with printer USB class.
  - Network: UDP broadcast (Xprinter SDK) and optional TCP port scan.
- **Connection Management**
  - Connect/disconnect multiple printers.
  - `connectionEvents` stream for attach, detach, and type detection.
- **Receipt Printing (ESC/POS)**
  - `printHTML` renders HTML to bitmap and prints.
  - `printData` sends raw ESC/POS commands.
- **Label Printing (ZPL only)**
  - `printLabelHTML` renders HTML to bitmap and sends ZPL.
  - `printLabelData` sends raw ZPL bytes.
- **Utilities**
  - `getPrinterStatus` handles ESC/POS or ZPL based on detected type.
  - `getZPLPrinterStatus` for label printers.
  - `getPrinterSN` retrieves serial number.
  - `openCashBox` opens cash drawer.
  - `setNetSettingsToPrinter` and `configureNetViaUDP` for IP configuration.

---

## Installation

Add the plugin from Git in your `pubspec.yaml`:

```yaml
dependencies:
  pos_printers:
    git:
      url: https://github.com/kicknext/pos_printers.git
      ref: main
```

No pub.dev publication yet.

---

## Usage

### PosPrintersManager

```dart
import 'package:pos_printers/pos_printers.dart';

final manager = PosPrintersManager();

// Discover printers
manager.findPrinters(filter: PrinterDiscoveryFilter.all)
  .listen((dto) {
    print('Found: ${dto.id}, type=${dto.printerType}');
  });
await manager.awaitDiscoveryComplete();

// Connect
final params = PrinterConnectionParams(
  connectionType: dto.type,
  usbParams: dto.usbParams,
  networkParams: dto.networkParams,
);
await manager.connectPrinter(params);

// Print receipt (ESC/POS)
await manager.printHTML(params, '<h1>Total: 10.00</h1>', 576);

// Print label (ZPL)
await manager.printLabelHTML(
  params,
  LabelPrinterLanguage.zpl,
  '<div>Label content</div>',
  464,
  320,
);

// Get status
final status = await manager.getPrinterStatus(params);
print('Status: ${status.status}');

// Get ZPL status (label)
final zplStatus = await manager.getZPLPrinterStatus(params);
print('ZPL code: ${zplStatus.code}');

// Serial number
final sn = await manager.getPrinterSN(params);
print('SN: ${sn.value}');

// Disconnect when done
await manager.disconnectPrinter(params);
manager.dispose();
```

---

## API Reference

Use methods on `PosPrintersManager`:
```dart
// Discover printers
Stream<DiscoveredPrinterDTO> findPrinters({PrinterDiscoveryFilter filter})
// Await discovery complete
Future<void> awaitDiscoveryComplete()
// Connect/disconnect
Future<void> connectPrinter(PrinterConnectionParams params)
Future<void> disconnectPrinter(PrinterConnectionParams params)
// Receipt printing (ESC/POS)
Future<void> printHTML(PrinterConnectionParams params, String html, int width)
Future<void> printData(PrinterConnectionParams params, Uint8List data, int width)
// Label printing (ZPL)
Future<void> printLabelHTML(PrinterConnectionParams params, LabelPrinterLanguage lang, String html, int width, int height)
Future<void> printLabelData(PrinterConnectionParams params, LabelPrinterLanguage lang, Uint8List data, int width)
// Status and details
Future<StatusResult> getPrinterStatus(PrinterConnectionParams params)
Future<ZPLStatusResult> getZPLPrinterStatus(PrinterConnectionParams params)
Future<StringResult> getPrinterSN(PrinterConnectionParams params)
// Cash drawer and network
Future<void> openCashBox(PrinterConnectionParams params)
Future<void> setNetSettingsToPrinter(PrinterConnectionParams params, NetSettingsDTO settings)
Future<void> configureNetViaUDP(String macAddress, NetSettingsDTO settings)
// Event stream
Stream<PrinterConnectionEvent> get connectionEvents
```

---

## Tips

- Always check `printerType` before printing (ESC/POS vs ZPL).
- Use direct IP connection for non‑Xprinter network devices.
- Listen to `connectionEvents` to update UI on USB attach/detach.
- Call `dispose()` on the manager in your widget's `dispose()`.

---

## Authors

- kicknext — main development and integration
- GitHub Copilot — assistance with implementation and documentation
