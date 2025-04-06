import 'dart:async';
// Required for Uint8List

import 'package:flutter/services.dart'; // Required for PlatformException
import 'package:pos_printers/pos_printers.pigeon.dart';

/// Manages interactions with POS printers (both standard ESC/POS and label printers).
///
/// Provides methods for discovering, connecting, disconnecting, printing,
/// and managing printers. Also exposes streams for printer discovery and
/// connection events.
class PosPrintersManager implements POSPrintersReceiverApi {
  /// Internal instance of the Pigeon-generated API.
  final POSPrintersApi _api = POSPrintersApi();

  /// Stream controller for emitting discovered printers during a scan.
  StreamController<PrinterConnectionParams>? _printerDiscoveryController;

  /// Stream controller for broadcasting connection-related events
  /// (connect success/failure, disconnects, USB attach/detach).
  final StreamController<ConnectResult> _connectionEventsController =
      StreamController<ConnectResult>.broadcast(); // Use broadcast for multiple listeners

  /// Stream providing connection-related events.
  /// Listen to this stream to get updates on connection status changes.
  Stream<ConnectResult> get connectionEvents => _connectionEventsController.stream;

  /// Initializes the manager and sets up the receiver for native callbacks.
  PosPrintersManager() {
    POSPrintersReceiverApi.setUp(this);
  }

  /// Disposes the stream controllers. Call this when the manager is no longer needed.
  void dispose() {
    _printerDiscoveryController?.close();
    _connectionEventsController.close();
    POSPrintersReceiverApi.setUp(null); // Detach the receiver
  }

  // --- Native Callbacks ---

  @override
  void newPrinter(PrinterConnectionParams printer) {
    _printerDiscoveryController?.add(printer);
  }

  @override
  void connectionHandler(ConnectResult message) {
    _connectionEventsController.add(message);
  }

  @override
  void scanCompleted(bool success, String? errorMessage) {
    if (!success && errorMessage != null) {
      _printerDiscoveryController?.addError(Exception('Scan failed: $errorMessage'));
    }
    // Close the stream regardless of success/failure, as the scan attempt is over
    if (!(_printerDiscoveryController?.isClosed ?? true)) {
      _printerDiscoveryController!.close();
    }
    _printerDiscoveryController = null; // Ready for next scan
  }

  // --- Public API Methods ---

  /// Starts scanning for available printers (USB and Network).
  ///
  /// Returns a stream that emits [PrinterConnectionParams] for each discovered printer.
  /// The stream closes automatically when the scan is complete.
  ///
  /// Note: Network discovery might primarily find Xprinter devices due to SDK limitations.
  /// For other network printers, direct connection via IP is recommended.
  Stream<PrinterConnectionParams> findPrinters() {
    // Close previous controller if any scan was interrupted
    _printerDiscoveryController?.close();
    _printerDiscoveryController = StreamController<PrinterConnectionParams>();

    // Start the native scan. The `then` block ensures the controller closes
    // even if the native call finishes quickly or throws an error handled by Pigeon.
    // Initiate the scan. Errors during initiation are caught.
    // The stream is closed via the scanCompleted callback.
    _api.getPrinters().then((result) {
      if (!result.success) {
        // If initiation itself failed, report error and close stream immediately
        _printerDiscoveryController?.addError(
          Exception('Failed to start printer scan: ${result.errorMessage ?? "Unknown error"}')
        );
        if (!(_printerDiscoveryController?.isClosed ?? true)) {
          _printerDiscoveryController!.close();
        }
         _printerDiscoveryController = null;
      }
      // If initiation succeeded, we just wait for newPrinter/scanCompleted callbacks.
    }).catchError((e) {
      // Catch PlatformExceptions or other errors during the initial call
      _printerDiscoveryController?.addError(e);
       if (!(_printerDiscoveryController?.isClosed ?? true)) {
         _printerDiscoveryController!.close();
       }
       _printerDiscoveryController = null;
    });

    return _printerDiscoveryController!.stream;
  }

  /// Connects to the specified printer.
  ///
  /// Returns a [ConnectResult] indicating success or failure.
  /// Listen to the [connectionEvents] stream for detailed connection status updates.
  Future<ConnectResult> connectPrinter(PrinterConnectionParams printer) async {
    try {
      return await _api.connectPrinter(printer);
    } on PlatformException catch (e) {
      print("PlatformException during connect: ${e.message}");
      // Return ConnectResult with failure status
      return ConnectResult(success: false, message: 'Connection failed: ${e.message}');
    } catch (e) {
       print("Unexpected error during connect: $e");
       return ConnectResult(success: false, message: 'Unexpected connection error: $e');
    }
  }

  /// Disconnects from the specified printer.
  ///
  /// Returns an [OperationResult] indicating success or failure.
  Future<OperationResult> disconnectPrinter(PrinterConnectionParams printer) async {
    try {
      // Assuming _api.disconnectPrinter now returns OperationResult
      return await _api.disconnectPrinter(printer);
    } on PlatformException catch (e) {
      // Log or handle the platform exception if needed
      print("PlatformException during disconnect: ${e.message}");
      // Return a structured failure result
      return OperationResult(success: false, errorMessage: 'Disconnect failed: ${e.message}');
    } catch (e) {
       print("Unexpected error during disconnect: $e");
       return OperationResult(success: false, errorMessage: 'Unexpected error: $e');
    }
  }

  /// Gets the current status of the connected printer.
  ///
  /// Returns a [StatusResult] containing the success status, error message (if any),
  /// and the status string itself.
  Future<StatusResult> getPrinterStatus(PrinterConnectionParams printer) async {
    try {
      return await _api.getPrinterStatus(printer);
    } on PlatformException catch (e) {
      print("PlatformException getting status: ${e.message}");
      return StatusResult(success: false, errorMessage: 'Failed to get status: ${e.message}');
    } catch (e) {
      print("Unexpected error getting status: $e");
      return StatusResult(success: false, errorMessage: 'Unexpected error: $e');
    }
  }

  /// Gets the serial number (SN) of the connected printer.
  ///
  /// Returns a [StringResult] containing the success status, error message (if any),
  /// and the serial number string.
  Future<StringResult> getPrinterSN(PrinterConnectionParams printer) async {
    try {
      return await _api.getPrinterSN(printer);
    } on PlatformException catch (e) {
      print("PlatformException getting SN: ${e.message}");
      return StringResult(success: false, errorMessage: 'Failed to get SN: ${e.message}');
    } catch (e) {
      print("Unexpected error getting SN: $e");
      return StringResult(success: false, errorMessage: 'Unexpected error: $e');
    }
  }

  /// Opens the cash drawer connected to the printer.
  ///
  /// Returns an [OperationResult] indicating success or failure.
  Future<OperationResult> openCashBox(PrinterConnectionParams printer) async {
    try {
      return await _api.openCashBox(printer);
    } on PlatformException catch (e) {
      print("PlatformException opening cash box: ${e.message}");
      return OperationResult(success: false, errorMessage: 'Failed to open cash box: ${e.message}');
    } catch (e) {
      print("Unexpected error opening cash box: $e");
      return OperationResult(success: false, errorMessage: 'Unexpected error: $e');
    }
  }

  /// Prints HTML content on a standard ESC/POS receipt printer.
  ///
  /// [printer]: Connection parameters of the target printer.
  /// [html]: The HTML string to print.
  /// [width]: The printing width in dots.
  /// Returns an [OperationResult] indicating success or failure.
  Future<OperationResult> printReceiptHTML(PrinterConnectionParams printer, String html, int width) async {
    try {
      return await _api.printHTML(printer, html, width.toInt());
    } on PlatformException catch (e) {
      print("PlatformException printing HTML receipt: ${e.message}");
      return OperationResult(success: false, errorMessage: 'Failed to print HTML receipt: ${e.message}');
    } catch (e) {
      print("Unexpected error printing HTML receipt: $e");
      return OperationResult(success: false, errorMessage: 'Unexpected error: $e');
    }
  }

  /// Sends raw ESC/POS commands to a standard receipt printer.
  ///
  /// [printer]: Connection parameters of the target printer.
  /// [data]: The raw byte data (ESC/POS commands).
  /// [width]: The printing width in dots (may be relevant for some printers/commands).
  /// Returns an [OperationResult] indicating success or failure.
  Future<OperationResult> printReceiptData(PrinterConnectionParams printer, Uint8List data, int width) async {
    try {
      return await _api.printData(printer, data, width.toInt());
    } on PlatformException catch (e) {
      print("PlatformException printing raw receipt data: ${e.message}");
      return OperationResult(success: false, errorMessage: 'Failed to print raw receipt data: ${e.message}');
    } catch (e) {
      print("Unexpected error printing raw receipt data: $e");
      return OperationResult(success: false, errorMessage: 'Unexpected error: $e');
    }
  }

  /// Configures network settings for a printer (usually via USB connection initially).
  ///
  /// [printer]: Connection parameters of the target printer (often USB).
  /// [netSettings]: The new network settings to apply.
  /// Returns an [OperationResult] indicating success or failure.
  Future<OperationResult> setNetSettings(PrinterConnectionParams printer, NetSettingsDTO netSettings) async {
    try {
      return await _api.setNetSettingsToPrinter(printer, netSettings);
    } on PlatformException catch (e) {
      print("PlatformException setting network settings: ${e.message}");
      return OperationResult(success: false, errorMessage: 'Failed to set network settings: ${e.message}');
    } catch (e) {
      print("Unexpected error setting network settings: $e");
      return OperationResult(success: false, errorMessage: 'Unexpected error: $e');
    }
  }

  // --- Label Printer Specific Methods ---

  /// Sends raw commands (CPCL, TSPL, or ZPL) to a label printer.
  ///
  /// [printer]: Connection parameters of the target printer.
  /// [language]: The command language ([LabelPrinterLanguage]).
  /// [labelCommands]: The raw byte data for the label.
  /// [width]: The printing width in dots (may be relevant for some commands).
  /// Returns an [OperationResult] indicating success or failure.
  Future<OperationResult> printLabelData(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    Uint8List labelCommands,
    int width,
  ) async {
    try {
      return await _api.printLabelData(printer, language, labelCommands, width.toInt());
    } on PlatformException catch (e) {
      print("PlatformException printing raw label data: ${e.message}");
      return OperationResult(success: false, errorMessage: 'Failed to print raw label data: ${e.message}');
    } catch (e) {
      print("Unexpected error printing raw label data: $e");
      return OperationResult(success: false, errorMessage: 'Unexpected error: $e');
    }
  }

  /// Prints HTML content rendered as a bitmap on a label printer.
  ///
  /// [printer]: Connection parameters of the target printer.
  /// [language]: The command language ([LabelPrinterLanguage]) to use for printing the bitmap.
  /// [html]: The HTML string to render.
  /// [width]: The label width in dots.
  /// [height]: The label height in dots.
  /// Returns an [OperationResult] indicating success or failure.
  Future<OperationResult> printLabelHTML(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    String html,
    int width,
    int height,
  ) async {
    try {
      return await _api.printLabelHTML(printer, language, html, width.toInt(), height.toInt());
    } on PlatformException catch (e) {
      print("PlatformException printing HTML label: ${e.message}");
      return OperationResult(success: false, errorMessage: 'Failed to print HTML label: ${e.message}');
    } catch (e) {
      print("Unexpected error printing HTML label: $e");
      return OperationResult(success: false, errorMessage: 'Unexpected error: $e');
    }
  }

  /// Sets up basic parameters for a label printer.
  ///
  /// [printer]: Connection parameters of the target printer.
  /// [language]: The command language ([LabelPrinterLanguage]) to use.
  /// [labelWidth]: Label width (units depend on language/printer, often dots or mm).
  /// [labelHeight]: Label height (units depend on language/printer, often dots or mm).
  /// [densityOrDarkness]: Printing density or darkness (range depends on language/printer).
  /// [speed]: Printing speed (range depends on language/printer).
  /// Returns an [OperationResult] indicating success or failure.
  Future<OperationResult> setupLabelParams(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    int labelWidth,
    int labelHeight,
    int densityOrDarkness,
    int speed,
  ) async {
    try {
      return await _api.setupLabelParams(
          printer, language, labelWidth.toInt(), labelHeight.toInt(), densityOrDarkness.toInt(), speed.toInt());
    } on PlatformException catch (e) {
      print("PlatformException setting up label parameters: ${e.message}");
      return OperationResult(success: false, errorMessage: 'Failed to setup label parameters: ${e.message}');
    } catch (e) {
      print("Unexpected error setting up label parameters: $e");
      return OperationResult(success: false, errorMessage: 'Unexpected error: $e');
    }
  }

  /// Gets detailed information about the connected printer.
  ///
  /// Returns a [PrinterDetailsDTO] containing information like serial number,
  /// status, and potentially model/firmware (if available via SDK).
  /// Returns a [PrinterDetailsDTO] on success, or null if details cannot be retrieved.
  Future<PrinterDetailsDTO?> getPrinterDetails(PrinterConnectionParams printer) async {
    try {
      return await _api.getPrinterDetails(printer);
    } on PlatformException catch (e) {
      print("PlatformException getting printer details: ${e.message}");
      // Return null on failure. Consider creating a PrinterDetailsResult
      // in Pigeon for more detailed error reporting if needed.
      return null;
    } catch (e) {
       print("Unexpected error getting printer details: $e");
       // Return null on failure.
       return null;
    }
  }
}
