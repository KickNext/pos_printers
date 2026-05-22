/// Dot unit for printer image and coordinate APIs.
class Dots {
  final int value;

  const Dots(this.value);
}

/// Physical millimeter unit for label media.
class Millimeters {
  final num value;

  const Millimeters(this.value);
}

/// Printer resolution in dots per inch.
class Dpi {
  final int value;

  const Dpi(this.value);
}

/// Receipt paper preset with its printable bitmap width in dots.
class ReceiptPaper {
  final Millimeters width;
  final Dots printableWidthDots;

  const ReceiptPaper({
    required this.width,
    required this.printableWidthDots,
  });

  static const mm58 = ReceiptPaper(
    width: Millimeters(58),
    printableWidthDots: Dots(416),
  );

  static const mm80 = ReceiptPaper(
    width: Millimeters(80),
    printableWidthDots: Dots(576),
  );
}

/// Physical TSPL media geometry. TSPL SIZE/GAP commands use millimeters,
/// while bitmap APIs still use dots.
class TsplLabelMedia {
  final Millimeters width;
  final Millimeters height;
  final Millimeters gap;
  final Dpi dpi;

  const TsplLabelMedia({
    required this.width,
    required this.height,
    required this.gap,
    required this.dpi,
  });

  double get dotsPerMillimeter => dpi.value / 25.4;

  int get bitmapWidthDots => (width.value * dotsPerMillimeter).round();

  int get labelHeightDots => (height.value * dotsPerMillimeter).round();

  TsplLabelMedia copyWith({
    Millimeters? width,
    Millimeters? height,
    Millimeters? gap,
    Dpi? dpi,
  }) {
    return TsplLabelMedia(
      width: width ?? this.width,
      height: height ?? this.height,
      gap: gap ?? this.gap,
      dpi: dpi ?? this.dpi,
    );
  }
}
