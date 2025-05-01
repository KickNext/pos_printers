import 'package:pos_printers/pos_printers.dart';

class PrinterDiscoveryFilter {
  final List<PrinterLanguage>? languages;
  final List<DiscoveryConnectionType>? connectionTypes;

  PrinterDiscoveryFilter(
      {required this.languages, required this.connectionTypes});
}

enum DiscoveryConnectionType {
  usb,
  sdk,
  tcp;
}

class DiscoveredPrinterDTO {
  String get id => connectionParams.id;
  final PrinterLanguage? printerLanguage;
  final PrinterConnectionParamsDTO connectionParams;

  DiscoveredPrinterDTO({
    required this.printerLanguage,
    required this.connectionParams,
  });
}
