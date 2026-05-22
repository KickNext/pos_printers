# POS Printers Example

Example Flutter app for exercising the Android plugin against real USB and network printers.

## What It Covers

- USB, SDK network, and TCP discovery.
- USB permission request/check flow.
- ESC/POS receipt HTML and raw bytes.
- ZPL HTML and raw labels.
- TSPL HTML with explicit media geometry.
- TSPL raw commands with bitmap width derived from media.
- Status checks and serial number lookup.
- UDP network configuration with an explicit target MAC address.
- Multi-connection status stress test.

## Run

```shell
cd example
flutter run
```

## TSPL Media

The print tab exposes width, height, gap, and DPI fields. These map to:

```dart
final media = TsplLabelMedia(
  width: Millimeters(58),
  height: Millimeters(60),
  gap: Millimeters(2),
  dpi: Dpi(203),
);

await manager.printTsplHtmlOnMedia(printer, html, media);
```

Raw TSPL still needs `SIZE` and `GAP` inside the command payload. The app passes `media.bitmapWidthDots` as the SDK bitmap width.

## Receipt Paper

ESC/POS printing uses `ReceiptPaper.mm58` or `ReceiptPaper.mm80` through the typed helper methods:

```dart
await manager.printEscHtmlOnPaper(printer, html, ReceiptPaper.mm80);
await manager.printEscRawDataOnPaper(printer, bytes, ReceiptPaper.mm58);
```

## Network Configuration

UDP network configuration requires a MAC address. Enter the target printer MAC in the network tab before sending the command. The manager rejects UDP configuration without a target MAC because the native packet cannot be built safely.

## Verification

This app is also used as the Android build host for plugin unit tests:

```shell
cd example/android
./gradlew :pos_printers:testDebugUnitTest
```
