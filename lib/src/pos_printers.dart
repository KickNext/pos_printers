import 'dart:developer' as developer;
import 'dart:async';
import 'package:flutter/services.dart'; // Required for PlatformException
import 'package:pos_printers/src/pos_printers.pigeon.dart'; // contains PrinterDiscoveryFilter, ZPLStatusResult

/// Тип события подключения/отключения принтера
enum PrinterConnectionEventType { attached, detached }

/// Событие подключения/отключения принтера
class PrinterConnectionEvent {
  final PrinterConnectionEventType type;
  final DiscoveredPrinterDTO? printer;
  final String? id;
  final String? message;
  PrinterConnectionEvent({
    required this.type,
    this.printer,
    this.id,
    this.message,
  });
}

/// Manages interactions with POS printers (both standard ESC/POS and label printers).
///
/// Provides methods for discovering, connecting, disconnecting, printing,
/// and managing printers. Discovery results are provided via a stream.
class PosPrintersManager implements PrinterDiscoveryEventsApi {
  static const String _logTag = 'PosPrintersManager';

  // Текущий фильтр для поиска принтеров
  PrinterDiscoveryFilter _discoveryFilter = PrinterDiscoveryFilter.all;

  // Implement the FlutterApi
  /// Internal instance of the Pigeon-generated API.
  final POSPrintersApi _api = POSPrintersApi();

  /// Helper method for executing API requests with unified error handling
  ///
  /// [apiOperation] - name of the operation for logging
  /// [apiCall] - function executing the API request
  /// [defaultErrorResult] - optional default result object to return on error
  /// If defaultErrorResult is not specified, errors will be thrown as exceptions
  Future<T> _executeApiCall<T>(
      String apiOperation, Future<T> Function() apiCall,
      {T? defaultErrorResult}) async {
    try {
      return await apiCall();
    } on PlatformException catch (e) {
      developer.log(
          "PlatformException in operation '$apiOperation': ${e.message}",
          name: _logTag);
      if (defaultErrorResult != null) {
        return defaultErrorResult;
      }
      throw Exception('$apiOperation failed: ${e.message}');
    } catch (e) {
      developer.log("Unexpected error in operation '$apiOperation': $e",
          name: _logTag);
      if (defaultErrorResult != null) {
        return defaultErrorResult;
      }
      throw Exception('Unexpected error during $apiOperation: $e');
    }
  }

  /// Stream controller for emitting discovered printers during a scan.
  /// Use broadcast to allow multiple listeners if needed, though typically one is enough.
  StreamController<DiscoveredPrinterDTO>? _printerDiscoveryController;

  /// Stream providing discovered printers. Listen to this after calling [findPrinters].
  /// The stream closes when discovery is complete or an error occurs.
  Stream<DiscoveredPrinterDTO> get discoveryStream =>
      _printerDiscoveryController?.stream ?? const Stream.empty();

  /// Completer to signal the end of the discovery process (success or failure).
  Completer<void>? _discoveryCompleter;

  final _connectionEventsController =
      StreamController<PrinterConnectionEvent>.broadcast();
  Stream<PrinterConnectionEvent> get connectionEvents =>
      _connectionEventsController.stream;

  // Словарь типов подключенных принтеров по идентификатору
  final Map<String, PrinterType> _printerTypes = {};

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

  // Собираем уникальный id из параметров соединения
  String _connectionId(PrinterConnectionParams params) {
    if (params.usbParams != null) {
      final u = params.usbParams!;
      return 'usb:${u.vendorId}:${u.productId}:${u.usbSerialNumber ?? ''}';
    } else if (params.networkParams != null) {
      return 'net:${params.networkParams!.ipAddress}';
    }
    return 'unknown';
  }

  // Определяем, ESC/POS или ZPL (для остальных пока unknown)
  Future<PrinterType> _detectPrinterType(PrinterConnectionParams params) async {
    final status = await getPrinterStatus(params);
    if (status.success) {
      return PrinterType.escpos;
    }
    // если статус не получен — считаем ZPL (label)
    return PrinterType.zpl;
  }

  // --- Native Callbacks Implementation (PrinterDiscoveryEventsApi) ---

  @override
  void onPrinterFound(DiscoveredPrinterDTO printer) {
    // Фильтруем по запрошенному типу (escpos, zpl, all)
    if (_discoveryFilter == PrinterDiscoveryFilter.escpos &&
        printer.printerType != PrinterType.escpos) {
      return;
    }
    if (_discoveryFilter == PrinterDiscoveryFilter.zpl &&
        printer.printerType != PrinterType.zpl) {
      return;
    }
    _printerDiscoveryController?.add(printer);
  }

  @override
  void onDiscoveryComplete(bool success, String? errorMessage) {
    if (!(_printerDiscoveryController?.isClosed ?? true)) {
      if (!success && errorMessage != null) {
        _printerDiscoveryController!
            .addError(Exception('Discovery failed: $errorMessage'));
      }
      // Close the stream when discovery is complete
      _printerDiscoveryController!.close();
    }
    // Complete the future associated with the findPrinters call
    if (!(_discoveryCompleter?.isCompleted ?? true)) {
      if (!success && errorMessage != null) {
        _discoveryCompleter!
            .completeError(Exception('Discovery failed: $errorMessage'));
      } else {
        _discoveryCompleter!.complete();
      }
    }
    // Reset for next scan
    _printerDiscoveryController = null;
    _discoveryCompleter = null;
  }

  @override
  void onPrinterAttached(DiscoveredPrinterDTO printer) {
    developer.log('USB printer attached: ${printer.id}', name: _logTag);
    _connectionEventsController.add(PrinterConnectionEvent(
      type: PrinterConnectionEventType.attached,
      printer: printer,
      id: printer.id,
      message: 'USB attached: ${printer.id}',
    ));
  }

  @override
  void onPrinterDetached(String id) {
    developer.log('USB printer detached: $id', name: _logTag);
    _connectionEventsController.add(PrinterConnectionEvent(
      type: PrinterConnectionEventType.detached,
      id: id,
      message: 'USB detached: $id',
    ));
  }

  // --- Public API Methods ---

  /// Starts scanning for available printers (USB and Network).
  /// Можно указать фильтр: escpos, zpl или all.
  Stream<DiscoveredPrinterDTO> findPrinters({
    PrinterDiscoveryFilter filter = PrinterDiscoveryFilter.all,
  }) {
    _discoveryFilter = filter;
    if (_printerDiscoveryController != null &&
        !_printerDiscoveryController!.isClosed) {
      throw StateError("Discovery is already in progress.");
    }
    _printerDiscoveryController?.close();
    _printerDiscoveryController =
        StreamController<DiscoveredPrinterDTO>.broadcast();
    _discoveryCompleter = Completer<void>();
    try {
      _executeApiCall(
        'Printer discovery initiation',
        () => _api.findPrinters(filter),
      ).catchError((error) {
        _printerDiscoveryController?.addError(error);
        _printerDiscoveryController?.close();
        _discoveryCompleter?.completeError(error);
        _printerDiscoveryController = null;
        _discoveryCompleter = null;
      });
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

  /// Awaits the completion of the current discovery process.
  /// Throws an error if discovery fails.
  Future<void> awaitDiscoveryComplete() async {
    if (_discoveryCompleter == null) {
      throw StateError("Discovery not started.");
    }
    return _discoveryCompleter!.future;
  }

  /// Connects to the specified printer using its connection parameters.
  /// Сохранит тип принтера и эмитит событие с ним.
  Future<void> connectPrinter(PrinterConnectionParams printer) async {
    await _executeApiCall<void>(
      'Connect to printer',
      () => _api.connectPrinter(printer),
    );
    final id = _connectionId(printer);
    // Событие успешного подключения (немедленно)
    _connectionEventsController.add(PrinterConnectionEvent(
      type: PrinterConnectionEventType.attached,
      id: id,
      message: 'Connected to $id',
    ));
    // Определение типа в фоне
    unawaited(() async {
      final type = await _detectPrinterType(printer);
      _printerTypes[id] = type;
      _connectionEventsController.add(PrinterConnectionEvent(
        type: PrinterConnectionEventType.attached,
        id: id,
        message: 'Detected printer type: ${type.toString().split('.').last}',
      ));
    }());
  }

  /// Disconnects from the specified printer.
  Future<void> disconnectPrinter(PrinterConnectionParams printer) async {
    return _executeApiCall<void>(
      'Disconnect printer',
      () => _api.disconnectPrinter(printer),
    );
  }

  /// Gets the current status of the connected printer.
  ///
  /// Returns a [StatusResult] containing the success status, error message (if any),
  /// and the status string itself.
  Future<StatusResult> getPrinterStatus(PrinterConnectionParams printer) async {
    final id = _connectionId(printer);
    final type = _printerTypes[id] ?? PrinterType.unknown;
    if (type == PrinterType.zpl) {
      // Для ZPL сразу вызываем ZPL статус
      try {
        final zpl = await _api.getZPLPrinterStatus(printer);
        return StatusResult(
          success: zpl.success,
          status: 'ZPL code ${zpl.code}',
          errorMessage: zpl.errorMessage,
        );
      } catch (e) {
        return StatusResult(success: false, errorMessage: e.toString());
      }
    }
    // ESC/POS или неизвестный тип по умолчанию
    return _executeApiCall<StatusResult>(
      'Get printer status',
      () => _api.getPrinterStatus(printer),
      defaultErrorResult: StatusResult(
        success: false,
        errorMessage: 'Failed to get printer status',
      ),
    );
  }

  /// Gets the serial number (SN) of the connected printer.
  ///
  /// Returns a [StringResult] containing the success status, error message (if any),
  /// and the serial number string.
  Future<StringResult> getPrinterSN(PrinterConnectionParams printer) async {
    return _executeApiCall<StringResult>(
      'Get printer serial number',
      () => _api.getPrinterSN(printer),
      defaultErrorResult: StringResult(
          success: false, errorMessage: 'Failed to get printer serial number'),
    );
  }

  /// Opens the cash drawer connected to the printer.
  Future<void> openCashBox(PrinterConnectionParams printer) async {
    return _executeApiCall<void>(
      'Open cash drawer',
      () => _api.openCashBox(printer),
    );
  }

  /// Prints HTML content on a standard ESC/POS receipt printer.
  ///
  /// [printer]: Connection parameters of the target printer.
  /// [html]: The HTML string to print.
  /// [width]: The printing width in dots.
  Future<void> printReceiptHTML(
      PrinterConnectionParams printer, String html, int width) async {
    return _executeApiCall<void>(
      'Print HTML receipt',
      () => _api.printHTML(printer, html, width),
    );
  }

  /// Sends raw ESC/POS commands к чековому принтеру.
  Future<void> printReceiptData(
      PrinterConnectionParams printer, Uint8List data, int width) async {
    final id = _connectionId(printer);
    final t = _printerTypes[id] ?? PrinterType.unknown;
    if (t != PrinterType.escpos) {
      throw Exception('Printer $id не поддерживает ESC/POS-печать, тип: $t');
    }
    return _executeApiCall<void>(
      'Print receipt data',
      () => _api.printData(printer, data, width),
    );
  }

  /// Configures network settings for a printer (usually via USB connection initially).
  ///
  /// [printer]: Connection parameters of the target printer (often USB).
  /// [netSettings]: The new network settings to apply.
  Future<void> setNetSettings(
      PrinterConnectionParams printer, NetSettingsDTO netSettings) async {
    return _executeApiCall<void>(
      'Configure network settings',
      () => _api.setNetSettingsToPrinter(printer, netSettings),
    );
  }

  /// Configures network settings via UDP broadcast.
  ///
  /// [macAddress]: The MAC address of the target printer.
  /// [netSettings]: The network settings to apply.
  Future<void> configureNetViaUDP(
      String macAddress, NetSettingsDTO netSettings) async {
    return _executeApiCall<void>(
      'Configure network via UDP',
      () => _api.configureNetViaUDP(macAddress, netSettings),
    );
  }

  // --- Label Printer Specific Methods ---

  /// Sends raw commands (CPCL, TSPL, или ZPL) к принтеру.
  Future<void> printLabelData(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    Uint8List labelCommands,
    int width,
  ) async {
    final id = _connectionId(printer);
    final t = _printerTypes[id] ?? PrinterType.unknown;
    if (t != PrinterType.zpl) {
      throw Exception('Printer $id не поддерживает label-печать, тип: $t');
    }
    return _executeApiCall<void>(
      'Print label data',
      () => _api.printLabelData(printer, language, labelCommands, width),
    );
  }

  /// Prints HTML content rendered as a bitmap on a label printer.
  Future<void> printLabelHTML(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    String html,
    int width,
    int height,
  ) async {
    final id = _connectionId(printer);
    final t = _printerTypes[id] ?? PrinterType.unknown;
    if (t != PrinterType.zpl) {
      throw Exception('Printer $id не поддерживает label-печать, тип: $t');
    }
    return _executeApiCall<void>(
      'Print HTML label',
      () => _api.printLabelHTML(printer, language, html, width, height),
    );
  }

  /// Получить статус ZPL‑принтера (коды 00–80)
  Future<ZPLStatusResult> getZPLPrinterStatus(
      PrinterConnectionParams printer) async {
    return _executeApiCall<ZPLStatusResult>(
      'Get ZPL printer status',
      () => _api.getZPLPrinterStatus(printer),
      defaultErrorResult: ZPLStatusResult(
        success: false,
        code: -1,
        errorMessage: 'Failed to get ZPL status',
      ),
    );
  }
}
