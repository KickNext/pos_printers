import 'package:flutter_test/flutter_test.dart';
import 'package:pos_printers/pos_printers.dart';

void main() {
  test('receipt paper presets expose printable width in dots', () {
    expect(ReceiptPaper.mm58.printableWidthDots.value, 416);
    expect(ReceiptPaper.mm80.printableWidthDots.value, 576);
  });

  test('TSPL label media keeps physical label geometry explicit', () {
    const media = TsplLabelMedia(
      width: Millimeters(58),
      height: Millimeters(60),
      gap: Millimeters(2),
      dpi: Dpi(203),
    );

    expect(media.width.value, 58);
    expect(media.height.value, 60);
    expect(media.gap.value, 2);
    expect(media.dotsPerMillimeter, closeTo(7.992, 0.001));
    expect(media.bitmapWidthDots, 464);
    expect(media.labelHeightDots, 480);
  });
}
