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
  Stream<DiscoveredPrinterDTO> findPrinters() {
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
  bool samePrinter(DiscoveredPrinterDTO a, DiscoveredPrinterDTO b) {
    return a.id == b.id;
  }

  /// Get printer status
  Future<StatusResult> getPrinterStatus(PrinterItem item) {
    return _posPrintersManager.getPrinterStatus(item.connectionParams);
  }

  /// Example: print HTML for receipt (ESC/POS)
  Future<void> printEscHtml(PrinterItem item) async {
    if (item.discoveredPrinter.printerLanguage == PrinterLanguage.zpl) {
      debugPrint('Skipping ESC/POS HTML print for ZPL label printer.');
      throw Exception('Invalid printer type: ZPL');
    }
    await _posPrintersManager.printEscHTML(
      item.connectionParams,
      "<h1>ESC/POS Html</h1><p>Some text</p>",
      576, // 80mm width in dots (for 203 dpi)
    );
  }

  /// Print raw ESC/POS commands
  Future<void> printEscPosData(PrinterItem item) async {
    if (item.discoveredPrinter.printerLanguage == PrinterLanguage.zpl) {
      debugPrint('Skipping ESC/POS raw print for ZPL label printer.');
      throw Exception('Invalid printer type: ZPL');
    }
    List<int> bytes = [];
    bytes.addAll([0x1B, 0x40]); // Init
    bytes.addAll([0x1B, 0x61, 0x01]); // Center
    bytes.addAll("Hello ESC/POS\n".codeUnits);
    bytes.add(0x0A); // LF
    bytes.addAll([0x1D, 0x56, 0x41, 0x10]); // Partial cut
    await _posPrintersManager.printEscRawData(
        item.connectionParams, Uint8List.fromList(bytes), 576);
  }

  /// Print raw ZPL label commands
  Future<void> printZplRawData(PrinterItem item) async {
    if (item.discoveredPrinter.printerLanguage != PrinterLanguage.zpl) {
      throw Exception('Only ZPL label printers are supported');
    }
    // Simple ZPL sample
    const commands = '''^XA
  ^PW456
  ^LL304            
  ^CF0,30              
  ^FO20,20^FDLuna Bloom Candle^FS
  ^CF0,40             
  ^FO20,60^FD\$24.99^FS
  ^FO340,20
  ^BQN,2,4           
  ^FDLA,https://example.com/luna-bloom-candle^FS
  ^XZ\r\n''';
    await _posPrintersManager.printZplRawData(
      item.connectionParams,
      Uint8List.fromList(commands.codeUnits),
      673,
    );
  }

  /// Print HTML for ZPL label printer
  Future<void> printLabelHtml(PrinterItem item) async {
    if (item.discoveredPrinter.printerLanguage != PrinterLanguage.zpl) {
      throw Exception('Only ZPL label printers are supported');
    }
    final html = generatePriceTagHtml(
      itemName: 'Awesome Gadget',
      price: '99.99',
      barcodeData: '123456789012',
      unit: 'pcs',
    );
    const widthDots = 673; // 58 mm at 8 dots/mm
    const heightDots = 449; // 40 mm at 8 dots/mm
    await _posPrintersManager.printZplHtml(
      item.connectionParams,
      html,
      widthDots,
      heightDots,
    );
  }

  /// Get ZPL printer status
  Future<ZPLStatusResult> getZPLPrinterStatus(PrinterItem item) async {
    return _posPrintersManager.getZPLPrinterStatus(item.connectionParams);
  }

  /// Sets network settings via active connection
  Future<void> setNetSettingsViaConnection(
      PrinterItem item, NetworkParams settings) async {
    if (item.discoveredPrinter.connectionParams.connectionType !=
        PosPrinterConnectionType.network) {
      throw Exception('This function is only for network printers');
    }
    await _posPrintersManager.setNetSettings(item.connectionParams, settings);
  }

  /// Configures network settings via UDP broadcast
  Future<void> configureNetViaUDP(
      PrinterItem item, NetworkParams settings) async {
    final mac =
        item.discoveredPrinter.connectionParams.networkParams?.macAddress;
    if (mac == null || mac.isEmpty) {
      throw Exception(
          'MAC address not found for this printer. Cannot configure via UDP.');
    }
    await _posPrintersManager.configureNetViaUDP(mac, settings);
  }
}
