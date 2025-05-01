import 'package:pos_printers/pos_printers.dart';

/// Model for storing printer information in the UI.
class PrinterItem {

  /// Connection parameters for the printer
  final PrinterConnectionParamsDTO connectionParams;

  /// Connection status (true = connected, false = disconnected)
  bool isConnected;

  /// Whether the printer is saved (shown in "connected")
  final bool isSaved;

  bool isBusy = false;

  PrinterLanguage? printerLanguage;

  /// Creates a [PrinterItem] from a discovered printer.
  /// [isConnected] - connection status (default: true)
  /// [isSaved] - whether the printer is saved (default: false)
  PrinterItem({
    required this.connectionParams,
    this.isConnected = true,
    this.isSaved = false,
  });


}
