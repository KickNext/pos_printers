import 'package:pos_printers/pos_printers.dart';

class PrinterDiscoveryFilter {
  final List<DiscoveryConnectionType>? connectionTypes;

  PrinterDiscoveryFilter({required this.connectionTypes});
}

enum DiscoveryConnectionType {
  usb,
  sdk,
  tcp;
}

class DiscoveredPrinterDTO {
  String get id => connectionParams.id;
  final PrinterConnectionParamsDTO connectionParams;

  DiscoveredPrinterDTO({
    required this.connectionParams,
  });
}
