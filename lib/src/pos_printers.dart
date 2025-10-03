import 'dart:developer' as developer;
import 'dart:async';
import 'package:flutter/services.dart'; // Required for PlatformException
import 'package:pos_printers/pos_printers.dart';

enum PrinterConnectionEventType { attached, detached }

class PrinterConnectionEvent {
  final PrinterConnectionEventType type;
  final PrinterConnectionParamsDTO? printer;
  final String? id;
  final String? message;
  PrinterConnectionEvent({
    required this.type,
    this.printer,
    this.id,
    this.message,
  });
}

class PosPrintersManager implements PrinterDiscoveryEventsApi {
  static const String _logTag = 'PosPrintersManager';

  final POSPrintersApi _api = POSPrintersApi();

  /// Stream controller for emitting discovered printers during a scan.
  /// Use broadcast to allow multiple listeners if needed, though typically one is enough.
  StreamController<PrinterConnectionParamsDTO>? _printerDiscoveryController;

  /// Stream providing discovered printers. Listen to this after calling [findPrinters].
  /// The stream closes when discovery is complete or an error occurs.
  Stream<PrinterConnectionParamsDTO> get discoveryStream =>
      _printerDiscoveryController?.stream ?? const Stream.empty();

  /// Completer to signal the end of the discovery process (success or failure).
  Completer<void>? _discoveryCompleter;

  final _connectionEventsController =
      StreamController<PrinterConnectionEvent>.broadcast();
  Stream<PrinterConnectionEvent> get connectionEvents =>
      _connectionEventsController.stream;

  /// Initializes the manager and sets up the receiver for native callbacks.
  PosPrintersManager() {
    // Set up the handler for native calls to the FlutterApi
    PrinterDiscoveryEventsApi.setUp(this);
  }

  /// Disposes resources. Call this when the manager is no longer needed.
  void dispose() {
    _printerDiscoveryController?.close();
    _discoveryCompleter?.completeError(StateError(
        "Manager disposed during discovery")); // Signal error if ongoing
    _connectionEventsController.close();
    PrinterDiscoveryEventsApi.setUp(null); // Detach the receiver
  }

  @override
  void onPrinterFound(PrinterConnectionParamsDTO printer) {
    _printerDiscoveryController?.add(printer);
  }

  @override
  void onDiscoveryComplete(bool success) {
    if (!(_printerDiscoveryController?.isClosed ?? true)) {
      _printerDiscoveryController!.close();
    }
    // Complete the future associated with the findPrinters call
    if (!(_discoveryCompleter?.isCompleted ?? true)) {
      _discoveryCompleter!.complete();
    }
    // Reset for next scan
    _printerDiscoveryController = null;
    _discoveryCompleter = null;
  }

  @override
  void onPrinterAttached(PrinterConnectionParamsDTO printer) {
    developer.log('USB printer attached: ${printer.id}', name: _logTag);
    _connectionEventsController.add(PrinterConnectionEvent(
      type: PrinterConnectionEventType.attached,
      printer: printer,
      id: printer.id,
      message: 'USB attached: ${printer.id}',
    ));
  }

  @override
  void onPrinterDetached(PrinterConnectionParamsDTO printer) {
    _connectionEventsController.add(PrinterConnectionEvent(
      type: PrinterConnectionEventType.detached,
      printer: printer,
      id: printer.id,
      message: 'USB detached:  ${printer.id}',
    ));
  }

  Stream<PrinterConnectionParamsDTO> findPrinters({
    required PrinterDiscoveryFilter? filter,
  }) {
    if (_printerDiscoveryController != null &&
        !_printerDiscoveryController!.isClosed) {
      throw StateError("Discovery is already in progress.");
    }
    _printerDiscoveryController?.close();
    _printerDiscoveryController =
        StreamController<PrinterConnectionParamsDTO>.broadcast();
    _discoveryCompleter = Completer<void>();
    try {
      unawaited(_startDiscoverPrinters(filter: filter));
      return _printerDiscoveryController!.stream;
    } catch (e) {
      _printerDiscoveryController?.addError(e);
      _printerDiscoveryController?.close();
      _discoveryCompleter?.completeError(e);
      _printerDiscoveryController = null;
      _discoveryCompleter = null;
      return Stream.error(Exception('Unexpected error starting discovery: $e'));
    }
  }

  Future<void> _startDiscoverPrinters({
    required PrinterDiscoveryFilter? filter,
  }) async {
    final types = filter?.connectionTypes;
    final discoverAll = types == null || types.isEmpty;

    if (discoverAll || types.contains(DiscoveryConnectionType.usb)) {
      await _api.startDiscoverAllUsbPrinters();
    }
    if (discoverAll || types.contains(DiscoveryConnectionType.sdk)) {
      await _api.startDiscoveryXprinterSDKNetworkPrinters();
    }
    if (discoverAll || types.contains(DiscoveryConnectionType.tcp)) {
      await _api.startDiscoveryTCPNetworkPrinters(9100);
    }

    await _printerDiscoveryController?.close();
  }

  /// Awaits the completion of the current discovery process.
  /// Throws an error if discovery fails.
  Future<void> awaitDiscoveryComplete() async {
    if (_discoveryCompleter == null) {
      throw StateError("Discovery not started.");
    }
    return _discoveryCompleter!.future;
  }

  /// Gets the current status of the connected printer.
  ///
  /// Returns a [StatusResult] containing the success status, error message (if any),
  /// and the status string itself.
  Future<StatusResult> getPrinterStatus(
      PrinterConnectionParamsDTO printer) async {
    return _api.getPrinterStatus(printer);
  }

  /// Gets the serial number (SN) of the connected printer.
  ///
  /// Returns a [StringResult] containing the success status, error message (if any),
  /// and the serial number string.
  Future<StringResult> getPrinterSN(PrinterConnectionParamsDTO printer) async {
    return _api.getPrinterSN(printer);
  }

  /// Opens the cash drawer connected to the printer.
  Future<void> openCashBox(PrinterConnectionParamsDTO printer) async {
    return _api.openCashBox(printer);
  }

  /// Prints HTML content on a standard ESC/POS receipt printer.
  ///
  /// [printer]: Connection parameters of the target printer.
  /// [html]: The HTML string to print.
  /// [width]: The printing width in dots.
  Future<void> printEscHTML(
      PrinterConnectionParamsDTO printer, String html, int width) async {
    return _api.printHTML(printer, html, width);
  }

  /// Sends raw ESC/POS commands к чековому принтеру.
  Future<void> printEscRawData(
      PrinterConnectionParamsDTO printer, Uint8List data, int width) async {
    return _api.printData(printer, data, width);
  }

  /// Configures network settings for a printer (usually via USB connection initially).
  ///
  /// [printer]: Connection parameters of the target printer (often USB).
  /// [netSettings]: The new network settings to apply.
  Future<void> setNetSettings(
      PrinterConnectionParamsDTO printer, NetworkParams netSettings) async {
    return _api.setNetSettingsToPrinter(printer, netSettings);
  }

  /// Configures network settings via UDP broadcast.
  ///
  /// [macAddress]: The MAC address of the target printer.
  /// [netSettings]: The network settings to apply.
  Future<void> configureNetViaUDP(
      String macAddress, NetworkParams netSettings) async {
    return _api.configureNetViaUDP(netSettings);
  }

  // --- Label Printer Specific Methods ---

  /// Sends raw commands (CPCL, TSPL, или ZPL) к принтеру.
  Future<void> printZplRawData(
    PrinterConnectionParamsDTO printer,
    Uint8List labelCommands,
    int width,
  ) async {
    return _api.printZplRawData(printer, labelCommands, width);
  }

  /// Prints HTML content rendered as a bitmap on a label printer.
  Future<void> printZplHtml(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
  ) async {
    return _api.printZplHtml(printer, html, width);
  }

  /// Получить статус ZPL‑принтера (коды 00–80)
  Future<ZPLStatusResult> getZPLPrinterStatus(
      PrinterConnectionParamsDTO printer) async {
    return _api.getZPLPrinterStatus(printer);
  }

  /// Отправка сырых TSPL-команд принтеру.
  Future<void> printTsplRawData(
    PrinterConnectionParamsDTO printer,
    Uint8List labelCommands,
    int width,
  ) async {
    return _api.printTsplRawData(printer, labelCommands, width);
  }

  /// Печать HTML как TSPL-этикетки.
  Future<void> printTsplHtml(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
  ) async {
    return _api.printTsplHtml(printer, html, width);
  }

  /// Получить статус TSPL-принтера
  Future<TSPLStatusResult> getTSPLPrinterStatus(
      PrinterConnectionParamsDTO printer) async {
    return _api.getTSPLPrinterStatus(printer);
  }

  @override
  void onDiscoveryError(String errorMessage) {
    if (_printerDiscoveryController != null) {
      _printerDiscoveryController!.addError(errorMessage);
      _printerDiscoveryController!.close();
    }
    // Complete the future associated with the findPrinters call
    if (_discoveryCompleter != null && !_discoveryCompleter!.isCompleted) {
      _discoveryCompleter!.completeError(errorMessage);
    }
    // Reset for next scan
    _printerDiscoveryController = null;
    _discoveryCompleter = null;
  }
}
