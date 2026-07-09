# TSPL Usage

TSPL is a physical label language. The printer uses `SIZE`, `GAP`, and `BLINE` in millimeters to feed media correctly. Bitmap APIs still use dots. Keep those two units separate.

## Discover A Printer

Discovery filters by connection type, not by printer language:

```dart
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
  print(printer.id);
}
```

## Print Raw TSPL

Raw TSPL gives full control. The payload must include the correct media setup for the roll in the printer.

```dart
final commands = '''
SIZE 58 mm, 60 mm
GAP 2 mm, 0 mm
DIRECTION 0
CLS
TEXT 40,40,"3",0,1,1,"TSPL RAW DEMO"
QRCODE 40,90,H,4,A,0,"https://example.com"
PRINT 1
''';

await manager.printTsplRawData(
  printer,
  Uint8List.fromList(utf8.encode(commands)),
  const TsplLabelMedia(
    width: Millimeters(58),
    height: Millimeters(60),
    gap: Millimeters(2),
    dpi: Dpi(203),
  ).bitmapWidthDots,
);
```

For raw TSPL, the `width` argument is only the bitmap/SDK width in dots. The printer media behavior comes from the TSPL commands you send.

## Print HTML As TSPL

Prefer `printTsplHtmlOnMedia` so the Android side receives both bitmap width and physical label geometry:

```dart
const media = TsplLabelMedia(
  width: Millimeters(58),
  height: Millimeters(60),
  gap: Millimeters(2),
  dpi: Dpi(203),
);

await manager.printTsplHtmlOnMedia(
  printer,
  '''
  <html>
    <body style="font-family: Arial; padding: 8px;">
      <h3>Product Label</h3>
      <p>SKU: ABC-123</p>
      <p>Price: 19.99</p>
    </body>
  </html>
  ''',
  media,
);
```

`printTsplHtml` remains available for legacy callers. It derives physical size from bitmap dimensions and assumes a 2 mm gap at 203 DPI.

## Common Media Values

| Physical size | Gap | DPI | Bitmap width |
| --- | --- | --- | --- |
| 40 x 30 mm | 2 mm | 203 | 320 dots |
| 58 x 40 mm | 2 mm | 203 | 464 dots |
| 58 x 60 mm | 2 mm | 203 | 464 dots |
| 60 x 80 mm | 2 mm | 203 | 480 dots |
| 100 x 150 mm | 3 mm | 203 | 799 dots |

The dot values are rounded from `mm * dpi / 25.4`.

## Status Codes

`getTSPLPrinterStatus` returns the native status code and a normalized success flag:

| Code | Meaning |
| --- | --- |
| `0x00` | Normal |
| `0x01` | Head opened |
| `0x02` | Paper jam |
| `0x04` | Out of paper |
| `0x08` | Out of ribbon |
| `0x10` | Pause |
| `0x20` | Printing |
| `0x40` | Label not found |
| `0x80` | Other error |
| `-1` | Receive timeout |

Only normal state is treated as successful. Paper, head, ribbon, timeout, and other fault states return `success == false`.

## Troubleshooting

- If the printer feeds only part of a label, check `SIZE` against the physical label height.
- If the printer cannot find the next label, check `GAP` or use `BLINE` for black-mark media.
- If HTML output is clipped, check `TsplLabelMedia.width` and DPI so the bitmap width matches the printable area.
- If raw commands print but HTML fails, try a simple HTML body first and confirm the printer can accept bitmap TSPL commands.

## Related Files

- [Architecture](ARCHITECTURE.md)
- [Label size guide](../TSPL_LABEL_SIZE_GUIDE.md)
- [Android TSPL command reference](../info/Android_TSPL_Program_Manual.md)
