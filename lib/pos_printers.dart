import 'dart:async';
import 'package:pos_printers/pos_printers.pigeon.dart';

class PosPrintersManager implements POSPrintersReceiverApi {
  PosPrintersManager() {
    POSPrintersReceiverApi.setUp(this);
  }

  StreamController<PrinterConnectionParams>? _printerStreamController;
  StreamController<ConnectResult> printerStatusStreamController = StreamController<ConnectResult>();

  Stream<PrinterConnectionParams> findPrinters() async* {
    _printerStreamController = StreamController<PrinterConnectionParams>();
    POSPrintersApi().getPrinters().then((_) {
      _printerStreamController!.close();
      _printerStreamController = null;
    });
    yield* _printerStreamController!.stream;
  }

  @override
  void newPrinter(PrinterConnectionParams printer) {
    _printerStreamController?.add(printer);
  }

  @override
  void connectionHandler(ConnectResult message) {
    printerStatusStreamController.add(message);
  }
}
