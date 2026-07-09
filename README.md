# POS Printers

Flutter plugin for Android receipt and label printers. It supports ESC/POS, ZPL, and TSPL printing over USB and network connections, with USB permission handling, discovery events, status checks, and network configuration helpers.

## Install

```yaml
dependencies:
  pos_printers:
    git:
      url: https://github.com/KickNext/pos_printers.git
```

## Quick Start

```dart
import 'package:pos_printers/pos_printers.dart';

final manager = PosPrintersManager();

final stream = manager.findPrinters(
  filter: PrinterDiscoveryFilter(
    connectionTypes: const [
      DiscoveryConnectionType.usb,
      DiscoveryConnectionType.sdk,
      DiscoveryConnectionType.tcp,
    ],
  ),
);

await for (final printer in stream) {
  await manager.withUsbPermission(printer, () {
    return manager.printEscHtmlOnPaper(
      printer,
      '<html><body><b>Total: 12.34</b></body></html>',
      ReceiptPaper.mm80,
    );
  });
}
```

## Public API Shape

`PosPrintersManager` is the public entry point. Pigeon-generated DTOs remain exported for compatibility with existing apps, but new code should keep business choices in explicit domain types where available:

- `ReceiptPaper.mm58` and `ReceiptPaper.mm80` express receipt printable width in dots.
- `TsplLabelMedia` expresses physical TSPL label size in millimeters plus DPI.
- `PrinterDiscoveryFilter` filters by connection type: USB, SDK network discovery, and bounded TCP scan.
- `PrinterEventRouter` fans native events out to every active `PosPrintersManager` instance.
- `PosPrintersNativeClient` can be injected in tests instead of mocking Flutter channels.

## Discovery

```dart
final printers = manager.findPrinters(
  filter: PrinterDiscoveryFilter(
    connectionTypes: const [DiscoveryConnectionType.usb],
  ),
);

await for (final printer in printers) {
  print('Found ${printer.id}');
}

await manager.awaitDiscoveryComplete();
```

TCP discovery scans candidate hosts for port `9100` by default and is capped on Android to avoid accidentally scanning very large networks.

## Receipt Printing

```dart
await manager.withUsbPermission(printer, () {
  return manager.printEscHtmlOnPaper(
    printer,
    '<html><body>Receipt</body></html>',
    ReceiptPaper.mm58,
  );
});

await manager.printEscRawDataOnPaper(
  printer,
  escPosBytes,
  ReceiptPaper.mm80,
);
```

The legacy `printEscHTML` and `printEscRawData` methods are still available when the caller already has a bitmap width in dots.

## Label Printing

ZPL methods accept raw ZPL commands or HTML rendered as a bitmap:

```dart
await manager.printZplRawData(
  printer,
  Uint8List.fromList(utf8.encode('^XA^FO50,50^FDHello^FS^XZ')),
  576,
);
```

For TSPL HTML printing, prefer explicit media geometry:

```dart
const media = TsplLabelMedia(
  width: Millimeters(58),
  height: Millimeters(60),
  gap: Millimeters(2),
  dpi: Dpi(203),
);

await manager.printTsplHtmlOnMedia(
  printer,
  '<html><body><h3>Product Label</h3></body></html>',
  media,
);
```

For raw TSPL, the command stream remains authoritative. Include the exact `SIZE`, `GAP` or `BLINE`, and `PRINT` commands in your payload.

## Status

```dart
final status = await manager.getPrinterStatus(printer);
if (!status.success) {
  print(status.errorMessage);
}

final tspl = await manager.getTSPLPrinterStatus(printer);
```

ESC/POS and TSPL status values are normalized on Android so paper-out, cover-open, timeouts, and similar states are not reported as successful print-ready states.

## Network Configuration

USB/network configuration:

```dart
await manager.setNetSettings(
  usbPrinter,
  NetworkParams(
    ipAddress: '192.168.1.50',
    mask: '255.255.255.0',
    gateway: '192.168.1.1',
    macAddress: null,
    dhcp: false,
  ),
);
```

UDP broadcast configuration requires a target MAC address. It may be passed either as the first argument or inside `NetworkParams.macAddress`.

```dart
await manager.configureNetViaUDP(
  'AA:BB:CC:DD:EE:FF',
  NetworkParams(
    ipAddress: '192.168.1.51',
    mask: '255.255.255.0',
    gateway: '192.168.1.1',
    macAddress: null,
    dhcp: false,
  ),
);
```

## Architecture

The package is organized as a domain-first Dart facade over an internal Pigeon transport:

- Dart public API: `lib/src/pos_printers.dart`, `domain.dart`, `native_client.dart`, `event_router.dart`.
- Pigeon wire contract: `pigeons/pos_printers.dart` and generated files.
- Android runtime: plugin adapter, discovery, connection manager, printer operations, network manager, and small domain mappers.

See [doc/ARCHITECTURE.md](doc/ARCHITECTURE.md) for the review notes and layer boundaries.

## Development

Regenerate Pigeon after editing `pigeons/pos_printers.dart`:

```shell
dart run pigeon --input pigeons/pos_printers.dart
```

Useful verification commands:

```shell
flutter analyze
flutter test
cd example/android && ./gradlew :pos_printers:testDebugUnitTest
cd ../.. && cd example && flutter build apk --debug
dart pub publish --dry-run
```

## Platform Support

Android only. iOS is not implemented.

## License

MIT. See [LICENSE](LICENSE).
