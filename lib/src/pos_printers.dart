import 'dart:developer' as developer;
import 'dart:async';
import 'package:flutter/services.dart'; // Required for PlatformException
import 'package:pos_printers/src/pos_printers.pigeon.dart';

/// Manages interactions with POS printers (both standard ESC/POS and label printers).
///
/// Provides methods for discovering, connecting, disconnecting, printing,
/// and managing printers. Discovery results are provided via a stream.
class PosPrintersManager implements PrinterDiscoveryEventsApi {
  static const String _logTag = 'PosPrintersManager';

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
    PrinterDiscoveryEventsApi.setUp(null); // Detach the receiver
  }

  // --- Native Callbacks Implementation (PrinterDiscoveryEventsApi) ---

  @override
  void onPrinterFound(DiscoveredPrinterDTO printer) {
    if (!(_printerDiscoveryController?.isClosed ?? true)) {
      _printerDiscoveryController!.add(printer);
    } else {
      developer.log(
          "Warning: onPrinterFound called but discovery stream is closed or null.",
          name: _logTag);
    }
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

  // --- Public API Methods ---

  /// Starts scanning for available printers (USB and Network via SDK + TCP Scan).
  ///
  /// Returns a [Stream<DiscoveredPrinter>] that emits printers as they are found.
  /// The stream will close when the discovery process is complete.
  /// Listen to the stream's `onDone` or `onError` callbacks, or await the Future
  /// returned by `stream.toList()` or similar methods to know when discovery finishes.
  ///
  /// You can also await the Future returned by [awaitDiscoveryComplete] to know when
  /// the discovery process has fully completed (successfully or with an error).
  Stream<DiscoveredPrinterDTO> findPrinters() {
    // Prevent concurrent scans
    if (_printerDiscoveryController != null &&
        !_printerDiscoveryController!.isClosed) {
      throw StateError("Discovery is already in progress.");
    }

    // Close previous controller just in case (should be null if completed properly)
    _printerDiscoveryController?.close();
    _printerDiscoveryController =
        StreamController<DiscoveredPrinterDTO>.broadcast();
    _discoveryCompleter = Completer<void>();

    try {
      // Initiate the native scan (method returns void)
      _executeApiCall(
        'Printer discovery initiation',
        () => _api.findPrinters(),
      ).catchError((error) {
        // Handle discovery initiation error
        _printerDiscoveryController?.addError(error);
        _printerDiscoveryController?.close();
        _discoveryCompleter?.completeError(error);
        _printerDiscoveryController = null;
        _discoveryCompleter = null;
      });

      // Return the stream immediately. Results will come via callbacks.
      return _printerDiscoveryController!.stream;
    } catch (e) {
      // This section will handle errors that could occur before _executeApiCall
      developer.log("Unexpected error during discovery initiation: $e",
          name: _logTag);
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
  ///
  /// Use the `id` and `type` from a [DiscoveredPrinter] to create
  /// the appropriate [PrinterConnectionParams].
  ///
  /// Returns a [ConnectResult] indicating success or failure.
  Future<void> connectPrinter(PrinterConnectionParams printer) async {
    return _executeApiCall<void>(
      'Connect to printer',
      () => _api.connectPrinter(printer),
    );
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
    return _executeApiCall<StatusResult>(
      'Get printer status',
      () => _api.getPrinterStatus(printer),
      defaultErrorResult: StatusResult(
          success: false, errorMessage: 'Failed to get printer status'),
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
  /// [upsideDown]: Whether to print the content upside down.
  Future<void> printReceiptHTML(
      PrinterConnectionParams printer, String html, int width) async {
    return _executeApiCall<void>(
      'Print HTML receipt',
      () => _api.printHTML(printer, html, width),
    );
  }

  /// Sends raw ESC/POS commands to a standard receipt printer.
  ///
  /// [printer]: Connection parameters of the target printer.
  /// [data]: The raw byte data (ESC/POS commands).
  /// [width]: The printing width in dots (may be relevant for some printers/commands).
  /// [upsideDown]: Whether to print the content upside down.
  Future<void> printReceiptData(
      PrinterConnectionParams printer, Uint8List data, int width) async {
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

  /// Sends raw commands (CPCL, TSPL, or ZPL) to a label printer.
  ///
  /// [printer]: Connection parameters of the target printer.
  /// [language]: The command language ([LabelPrinterLanguage]).
  /// [labelCommands]: The raw byte data for the label.
  /// [width]: The printing width in dots (may be relevant for some commands).
  Future<void> printLabelData(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    Uint8List labelCommands,
    int width,
  ) async {
    return _executeApiCall<void>(
      'Print label data',
      () => _api.printLabelData(printer, language, labelCommands, width),
    );
  }

  /// Prints HTML content rendered as a bitmap on a label printer.
  ///
  /// [printer]: Connection parameters of the target printer.
  /// [language]: The command language ([LabelPrinterLanguage]) to use for printing the bitmap.
  /// [html]: The HTML string to render.
  /// [width]: The label width in dots.
  /// [height]: The label height in dots.
  Future<void> printLabelHTML(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    String html,
    int width,
    int height,
  ) async {
    return _executeApiCall<void>(
      'Print HTML label',
      () => _api.printLabelHTML(printer, language, html, width, height),
    );
  }
}
