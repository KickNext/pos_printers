import 'pos_printers.pigeon.dart';

abstract class PrinterEventListener {
  void onPrinterFound(PrinterConnectionParamsDTO printer);
  void onDiscoveryComplete(bool success);
  void onDiscoveryError(String errorMessage);
  void onPrinterAttached(PrinterConnectionParamsDTO printer);
  void onPrinterDetached(PrinterConnectionParamsDTO printer);
}

class PrinterEventRouter implements PrinterDiscoveryEventsApi {
  PrinterEventRouter._();

  static final PrinterEventRouter instance = PrinterEventRouter._();

  final Set<PrinterEventListener> _listeners = <PrinterEventListener>{};
  bool _isSetUp = false;

  void ensureSetUp() {
    if (_isSetUp) {
      return;
    }
    PrinterDiscoveryEventsApi.setUp(this);
    _isSetUp = true;
  }

  void addListener(PrinterEventListener listener) {
    _listeners.add(listener);
  }

  void removeListener(PrinterEventListener listener) {
    _listeners.remove(listener);
  }

  void resetForTesting() {
    _listeners.clear();
    if (_isSetUp) {
      PrinterDiscoveryEventsApi.setUp(null);
      _isSetUp = false;
    }
  }

  @override
  void onPrinterFound(PrinterConnectionParamsDTO printer) {
    for (final listener in List<PrinterEventListener>.of(_listeners)) {
      listener.onPrinterFound(printer);
    }
  }

  @override
  void onDiscoveryComplete(bool success) {
    for (final listener in List<PrinterEventListener>.of(_listeners)) {
      listener.onDiscoveryComplete(success);
    }
  }

  @override
  void onDiscoveryError(String errorMessage) {
    for (final listener in List<PrinterEventListener>.of(_listeners)) {
      listener.onDiscoveryError(errorMessage);
    }
  }

  @override
  void onPrinterAttached(PrinterConnectionParamsDTO printer) {
    for (final listener in List<PrinterEventListener>.of(_listeners)) {
      listener.onPrinterAttached(printer);
    }
  }

  @override
  void onPrinterDetached(PrinterConnectionParamsDTO printer) {
    for (final listener in List<PrinterEventListener>.of(_listeners)) {
      listener.onPrinterDetached(printer);
    }
  }
}
