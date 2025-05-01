import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:pos_printers/pos_printers.dart';
import '../models/printer_item.dart';
import '../utils/html_templates.dart';

/// Service for working with printers: search, connect, and operations
class PrinterService {
  /// Manager for interacting with printers
  final PosPrintersManager _posPrintersManager = PosPrintersManager();

  /// Stream of USB printer connection/disconnection events
  Stream<PrinterConnectionEvent> get connectionEvents =>
      _posPrintersManager.connectionEvents;

  /// Start searching for printers
  Stream<PrinterConnectionParamsDTO> findPrinters() {
    final stream = _posPrintersManager.findPrinters(filter: null);
    return stream;
  }

  /// Wait for discovery process to complete
  Future<void> awaitDiscoveryComplete() {
    return _posPrintersManager.awaitDiscoveryComplete();
  }

  /// Dispose resources
  void dispose() {
    _posPrintersManager.dispose();
  }

  /// Compare printers by ID (usbPath or ip:port)
  bool samePrinter(PrinterConnectionParamsDTO a, PrinterConnectionParamsDTO b) {
    return a.id == b.id;
  }

  /// Get printer status
  Future<StatusResult> getPrinterStatus(PrinterItem item) {
    return _posPrintersManager.getPrinterStatus(item.connectionParams);
  }

  /// Example: print HTML for receipt (ESC/POS)
  Future<void> printEscHtml(PrinterItem item) async {
    // if (item.connectionParams.printerLanguage == PrinterLanguage.zpl) {
    //   debugPrint('Skipping ESC/POS HTML print for ZPL label printer.');
    //   throw Exception('Invalid printer type: ZPL');
    // }
    // await _posPrintersManager.printEscHTML(
    //   item.connectionParams,
    //   "<h1>ESC/POS Html</h1><p>Some text</p>",
    //   576, // 80mm width in dots (for 203 dpi)
    // );
  }

  /// Print raw ESC/POS commands
  Future<void> printEscPosData(PrinterItem item) async {
    // if (item.discoveredPrinter.printerLanguage == PrinterLanguage.zpl) {
    //   debugPrint('Skipping ESC/POS raw print for ZPL label printer.');
    //   throw Exception('Invalid printer type: ZPL');
    // }
    // List<int> bytes = [];
    // bytes.addAll([0x1B, 0x40]); // Init
    // bytes.addAll([0x1B, 0x61, 0x01]); // Center
    // bytes.addAll("Hello ESC/POS\n".codeUnits);
    // bytes.add(0x0A); // LF
    // bytes.addAll([0x1D, 0x56, 0x41, 0x10]); // Partial cut
    // await _posPrintersManager.printEscRawData(
    //     item.connectionParams, Uint8List.fromList(bytes), 576);
  }

  /// Print raw ZPL label commands
  Future<void> printZplRawData(PrinterItem item) async {
//     if (item.discoveredPrinter.printerLanguage != PrinterLanguage.zpl) {
//       throw Exception('Only ZPL label printers are supported');
//     }
//     // Simple ZPL sample
//     const commands = '''^XA
// ^PW457
// ^CF0,32
// ^FO20,20,0
// ^FB250,3,0,L,0^FDItem name  long lo long long^FS
// ^CF0,30,30
// ^FO20,130^FD\$250010.34^FS
// ^FO20,140^GB200,3,3^FS
// ^CF0,50,50
// ^FO20,160,0^FD\$250006.34 /kg^FS
// ^FO437,20,1
// ^CF0,14
// ^FB150,1,0,R,0^FD0000000000000000^FS
// ^FO437,35,1
// ^BQN,2,5,L
// ^FDLA,000000000000000000^FS
// ^CF0,20
// ^FO20,220^FDStore name^FS
// ^FO437,220,1
// ^FB150,1,0,R,0^FD01/01/2025^FS
// ^XZ\r\n''';
//     await _posPrintersManager.printZplRawData(
//       item.connectionParams,
//       Uint8List.fromList(commands.codeUnits),
//       457,
//     );
  }

  /// Print HTML for ZPL label printer
  Future<void> printLabelHtml(PrinterItem item) async {
    // if (item.discoveredPrinter.printerLanguage != PrinterLanguage.zpl) {
    //   throw Exception('Only ZPL label printers are supported');
    // }
    // final html = generatePriceTagHtml(
    //   itemName: 'Awesome Gadget',
    //   price: '99.99',
    //   barcodeData: '123456789012',
    //   unit: 'pcs',
    // );
    // const widthDots = 457; // 58 mm at 8 dots/mm
    // const heightDots = 254; // 40 mm at 8 dots/mm
    // await _posPrintersManager.printZplHtml(
    //   item.connectionParams,
    //   html,
    //   widthDots,
    //   heightDots,
    // );
  }

  /// Get ZPL printer status
  Future<ZPLStatusResult> getZPLPrinterStatus(PrinterItem item) async {
    return _posPrintersManager.getZPLPrinterStatus(item.connectionParams);
  }

  /// Sets network settings via active connection
  Future<void> setNetSettingsViaConnection(
      PrinterItem item, NetworkParams settings) async {
    // if (item.discoveredPrinter.connectionParams.connectionType !=
    //     PosPrinterConnectionType.network) {
    //   throw Exception('This function is only for network printers');
    // }
    // await _posPrintersManager.setNetSettings(item.connectionParams, settings);
  }

  /// Configures network settings via UDP broadcast
  Future<void> configureNetViaUDP(
      PrinterItem item, NetworkParams settings) async {
    // final mac =
    //     item.discoveredPrinter.connectionParams.networkParams?.macAddress;
    // if (mac == null || mac.isEmpty) {
    //   throw Exception(
    //       'MAC address not found for this printer. Cannot configure via UDP.');
    // }
    // await _posPrintersManager.configureNetViaUDP(mac, settings);
  }

  Future<void> checkPrinterLanguage(PrinterItem item) async {
    final response = await _posPrintersManager.checkPrinterLanguage(
      item.connectionParams,
    );
    item.printerLanguage = response.printerLanguage;
  }
}
