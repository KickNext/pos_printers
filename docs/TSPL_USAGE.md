# TSPL Printer Usage Guide

This guide explains how to use TSPL (TSC Printer Language) printers with the pos_printers plugin.

## Overview

TSPL is a command language used by TSC label printers. This plugin supports:

- Raw TSPL command printing
- HTML to bitmap conversion for label printing
- Printer status monitoring
- Automatic printer language detection

## Supported TSPL Commands

The plugin supports all standard TSPL commands including:

### Label Setup Commands

- `SIZE` - Set label size
- `GAP` - Set gap between labels
- `BLINE` - Set black line position
- `OFFSET` - Set label offset
- `DIRECTION` - Set print direction
- `REFERENCE` - Set reference point
- `SPEED` - Set print speed
- `DENSITY` - Set print density

### Print Control Commands

- `CLS` - Clear buffer
- `PRINT` - Print label(s)
- `FEED` - Feed label
- `BACKFEED` - Reverse feed
- `FORMFEED` - Feed to next label
- `HOME` - Move to home position

### Text Commands

- `TEXT` - Print text
- `CODEPAGE` - Set character encoding

### Barcode Commands

- `BARCODE` - Print 1D barcode
- `QRCODE` - Print QR code

### Graphics Commands

- `BITMAP` - Print bitmap image
- `BOX` - Draw rectangle
- `BAR` - Draw filled bar
- `ERASE` - Erase region
- `REVERSE` - Reverse region

## Quick Start Examples

### 1. Print Simple Text

```dart
import 'dart:convert';
import 'dart:typed_data';
import 'package:pos_printers/pos_printers.dart';

final manager = PosPrintersManager();

// Discover TSPL printer
final printers = await manager.findPrinters(
  filter: PrinterDiscoveryFilter(
    languages: [PrinterLanguage.tspl],
  ),
);

// Print simple text
final tsplCommands = '''
SIZE 60 mm, 40 mm
GAP 2 mm, 0 mm
DIRECTION 0
CLS
TEXT 50,50,"3",0,1,1,"Hello World"
PRINT 1
''';

await manager.printTsplRawData(
  printer,
  Uint8List.fromList(utf8.encode(tsplCommands)),
  416, // 58mm width
);
```

### 2. Print Barcode Label

```dart
final tsplCommands = '''
SIZE 60 mm, 40 mm
GAP 2 mm, 0 mm
DIRECTION 0
CLS
TEXT 50,20,"3",0,1,1,"Product Label"
BARCODE 50,60,"128",80,1,0,2,2,"1234567890"
TEXT 50,160,"2",0,1,1,"SKU: 1234567890"
PRINT 1
''';

await manager.printTsplRawData(
  printer,
  Uint8List.fromList(utf8.encode(tsplCommands)),
  416,
);
```

### 3. Print QR Code

```dart
final tsplCommands = '''
SIZE 60 mm, 40 mm
GAP 2 mm, 0 mm
DIRECTION 0
CLS
TEXT 50,20,"3",0,1,1,"Scan QR Code"
QRCODE 100,60,H,4,A,0,"https://example.com"
PRINT 1
''';

await manager.printTsplRawData(
  printer,
  Uint8List.fromList(utf8.encode(tsplCommands)),
  416,
);
```

### 4. Print HTML as Label

```dart
final html = '''
<html>
  <head>
    <style>
      body {
        font-family: Arial;
        padding: 10px;
      }
      h1 {
        font-size: 20px;
        text-align: center;
      }
      .price {
        font-size: 24px;
        font-weight: bold;
        text-align: center;
      }
    </style>
  </head>
  <body>
    <h1>Product Label</h1>
    <p>SKU: ABC-12345</p>
    <div class="price">\$19.99</div>
  </body>
</html>
''';

await manager.printTsplHtml(printer, html, 416);
```

### 5. Check Printer Status

```dart
final status = await manager.getTSPLPrinterStatus(printer);

if (status.success) {
  print('Printer is ready');
} else {
  print('Printer error: ${status.errorMessage}');
}
```

## TSPL Status Codes

The `getTSPLPrinterStatus` method returns status codes indicating printer state:

| Code | Description                                 |
| ---- | ------------------------------------------- |
| 0x00 | Normal                                      |
| 0x01 | Head opened                                 |
| 0x02 | Paper Jam                                   |
| 0x03 | Paper Jam and head opened                   |
| 0x04 | Out of paper                                |
| 0x05 | Out of paper and head opened                |
| 0x08 | Out of ribbon                               |
| 0x09 | Out of ribbon and head opened               |
| 0x0A | Out of ribbon and paper jam                 |
| 0x0B | Out of ribbon, paper jam and head opened    |
| 0x0C | Out of ribbon and out of paper              |
| 0x0D | Out of ribbon, out of paper and head opened |
| 0x10 | Pause                                       |
| 0x20 | Printing                                    |
| 0x80 | Other error                                 |
| -1   | Receive timeout                             |

## Advanced Examples

### Complex Label with Multiple Elements

```dart
final tsplCommands = '''
SIZE 60 mm, 80 mm
GAP 2 mm, 0 mm
DIRECTION 0
SPEED 4
DENSITY 8
CLS

REM === Header ===
BOX 10,10,410,80,2
TEXT 30,25,"4",0,1,1,"PRODUCT LABEL"

REM === Product Info ===
TEXT 20,100,"3",0,1,1,"Name: Premium Coffee"
TEXT 20,135,"2",0,1,1,"SKU: COF-001"
TEXT 20,165,"2",0,1,1,"Weight: 250g"

REM === Barcode ===
BARCODE 50,200,"128",60,1,0,2,2,"COF001250"

REM === Price ===
BOX 10,290,410,370,2
TEXT 100,310,"5",0,2,2,"\$12.99"

REM === Footer ===
TEXT 80,390,"1",0,1,1,"Best before: 2025-12-31"

PRINT 1
''';

await manager.printTsplRawData(
  printer,
  Uint8List.fromList(utf8.encode(tsplCommands)),
  416,
);
```

### Print Multiple Copies

```dart
final tsplCommands = '''
SIZE 60 mm, 40 mm
GAP 2 mm, 0 mm
DIRECTION 0
CLS
TEXT 100,80,"4",0,1,1,"Copy Label"
PRINT 5
''';

await manager.printTsplRawData(
  printer,
  Uint8List.fromList(utf8.encode(tsplCommands)),
  416,
);
```

## Common Label Sizes

Standard label sizes in dots (for 8 dots/mm resolution):

| Size (mm) | Width (dots) | Common Use            |
| --------- | ------------ | --------------------- |
| 40 x 30   | 320 x 240    | Small product labels  |
| 50 x 30   | 400 x 240    | Shipping labels       |
| 60 x 40   | 480 x 320    | Product labels        |
| 100 x 50  | 800 x 400    | Large shipping labels |

## Tips and Best Practices

1. **Always start with CLS**: Clear the buffer before printing to avoid overlapping content
2. **Use appropriate paper sizes**: Match SIZE command to your actual label size
3. **Set proper gap**: GAP command should match the gap between your labels
4. **Test status before printing**: Check printer status to avoid errors
5. **Use REM for comments**: Document your TSPL code for maintainability
6. **Optimize print speed**: Balance SPEED and DENSITY for best quality
7. **Multiple copies**: Use PRINT command with count parameter instead of sending the same job multiple times

## Troubleshooting

### Label not printing

- Check printer status with `getTSPLPrinterStatus`
- Verify label size matches SIZE command
- Ensure gap settings are correct

### Poor print quality

- Adjust DENSITY setting (0-15)
- Reduce SPEED if printing too fast
- Check ribbon and paper installation

### Barcode not scanning

- Increase barcode height
- Ensure adequate quiet zones
- Use appropriate barcode type for your scanner

## See Also

- [Android TSPL Program Manual](../info/Android_TSPL_Program_Manual.md) - Complete TSPL command reference
- [Main README](../README.md) - General plugin documentation
- [Example App](../example/) - Full working examples
