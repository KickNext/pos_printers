import 'package:pos_printers/pos_printers.dart';

/// Model for storing printer information in the UI.
class PrinterItem {
  /// Original discovered printer object
  final DiscoveredPrinterDTO discoveredPrinter;

  /// Connection parameters for the printer
  late final PrinterConnectionParams connectionParams;

  /// Connection status (true = connected, false = disconnected)
  bool isConnected;

  /// Whether the printer is saved (shown in "connected")
  final bool isSaved;

  /// Creates a [PrinterItem] from a discovered printer.
  ///
  /// [discoveredPrinter] - discovered printer from search
  /// [isConnected] - connection status (default: true)
  /// [isSaved] - whether the printer is saved (default: false)
  PrinterItem({
    required this.discoveredPrinter,
    this.isConnected = true,
    this.isSaved = false,
  }) {
    // Initialize connection parameters based on discovered printer
    if (discoveredPrinter.type == PosPrinterConnectionType.usb) {
      connectionParams = PrinterConnectionParams(
        connectionType: PosPrinterConnectionType.usb,
        usbParams: UsbParams(
          vendorId: discoveredPrinter.usbParams!.vendorId,
          productId: discoveredPrinter.usbParams!.productId,
          usbSerialNumber: discoveredPrinter.usbParams!.usbSerialNumber,
          manufacturer: discoveredPrinter.usbParams!.manufacturer,
          productName: discoveredPrinter.usbParams!.productName,
        ),
      );
    } else {
      connectionParams = PrinterConnectionParams(
        connectionType: PosPrinterConnectionType.network,
        networkParams: NetworkParams(
          ipAddress: discoveredPrinter.networkParams!.ipAddress,
          macAddress: discoveredPrinter.networkParams!.macAddress,
        ),
      );
    }
  }
}
