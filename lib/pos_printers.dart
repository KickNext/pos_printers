import 'dart:async';
import 'package:pos_printers/pos_printers.pigeon.dart';

import 'models/printer.dart';

class PosPrintersManager implements POSPrintersReceiverApi {
  PosPrintersManager() {
    POSPrintersReceiverApi.setup(this);
  }

  POSPrinter? printer;
  StreamController<XPrinterDTO>? _printerStreamController;

  Stream<XPrinterDTO> findPrinters() async* {
    _printerStreamController = StreamController<XPrinterDTO>();
    POSPrintersApi().getPrinters().then((_) {
      _printerStreamController!.close();
      _printerStreamController = null;
    });
    yield* _printerStreamController!.stream;
  }

  Future<void> connectPrinter(XPrinterDTO printerDTO) async {
    final result = await POSPrintersApi().connectPrinter(printerDTO);
    if (result.success) {
      printer = POSPrinter.fromDTO(printerDTO).copyWith(isConnecting: true);
    }
  }

  Future<bool> printHTML(String html, int width) async {
    final result = await POSPrintersApi().printHTML(html, width);
    return result;
  }

  @override
  void newPrinter(XPrinterDTO printer) {
    _printerStreamController?.add(printer);
  }

  @override
  void connectionHandler(ConnectResult message) {}
}
