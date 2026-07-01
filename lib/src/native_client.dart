import 'dart:typed_data';

import 'pos_printers.pigeon.dart';

abstract class PosPrintersNativeClient {
  Future<UsbPermissionResult> requestUsbPermission(UsbParams usbDevice);
  Future<UsbPermissionResult> hasUsbPermission(UsbParams usbDevice);
  Future<void> startDiscoverAllUsbPrinters();
  Future<void> startDiscoveryXprinterSDKNetworkPrinters();
  Future<void> startDiscoveryTCPNetworkPrinters(int port);
  Future<StatusResult> getPrinterStatus(PrinterConnectionParamsDTO printer);
  Future<StringResult> getPrinterSN(PrinterConnectionParamsDTO printer);
  Future<void> openCashBox(PrinterConnectionParamsDTO printer);
  Future<Uint8List> renderHtmlBitmap(
    String html,
    int width,
    bool upsideDown,
  );
  Future<void> printHTML(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
    bool upsideDown,
  );
  Future<void> printData(
    PrinterConnectionParamsDTO printer,
    Uint8List data,
    int width,
    bool upsideDown,
  );
  Future<void> setNetSettingsToPrinter(
    PrinterConnectionParamsDTO printer,
    NetworkParams netSettings,
  );
  Future<void> configureNetViaUDP(NetworkParams netSettings);
  Future<void> printZplRawData(
    PrinterConnectionParamsDTO printer,
    Uint8List labelCommands,
    int width,
  );
  Future<void> printZplHtml(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
  );
  Future<ZPLStatusResult> getZPLPrinterStatus(
    PrinterConnectionParamsDTO printer,
  );
  Future<void> printTsplRawData(
    PrinterConnectionParamsDTO printer,
    Uint8List labelCommands,
    int width,
  );
  Future<void> printTsplHtml(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
  );
  Future<void> printTsplHtmlWithMedia(
    PrinterConnectionParamsDTO printer,
    String html,
    TsplLabelMediaDTO media,
  );
  Future<TSPLStatusResult> getTSPLPrinterStatus(
    PrinterConnectionParamsDTO printer,
  );
}

class PigeonPosPrintersNativeClient implements PosPrintersNativeClient {
  final POSPrintersApi _api;

  PigeonPosPrintersNativeClient({POSPrintersApi? api})
      : _api = api ?? POSPrintersApi();

  @override
  Future<void> configureNetViaUDP(NetworkParams netSettings) {
    return _api.configureNetViaUDP(netSettings);
  }

  @override
  Future<StatusResult> getPrinterStatus(PrinterConnectionParamsDTO printer) {
    return _api.getPrinterStatus(printer);
  }

  @override
  Future<StringResult> getPrinterSN(PrinterConnectionParamsDTO printer) {
    return _api.getPrinterSN(printer);
  }

  @override
  Future<TSPLStatusResult> getTSPLPrinterStatus(
    PrinterConnectionParamsDTO printer,
  ) {
    return _api.getTSPLPrinterStatus(printer);
  }

  @override
  Future<ZPLStatusResult> getZPLPrinterStatus(
    PrinterConnectionParamsDTO printer,
  ) {
    return _api.getZPLPrinterStatus(printer);
  }

  @override
  Future<UsbPermissionResult> hasUsbPermission(UsbParams usbDevice) {
    return _api.hasUsbPermission(usbDevice);
  }

  @override
  Future<void> openCashBox(PrinterConnectionParamsDTO printer) {
    return _api.openCashBox(printer);
  }

  @override
  Future<Uint8List> renderHtmlBitmap(
    String html,
    int width,
    bool upsideDown,
  ) {
    return _api.renderHtmlBitmap(html, width, upsideDown);
  }

  @override
  Future<void> printData(
    PrinterConnectionParamsDTO printer,
    Uint8List data,
    int width,
    bool upsideDown,
  ) {
    return _api.printData(printer, data, width, upsideDown);
  }

  @override
  Future<void> printHTML(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
    bool upsideDown,
  ) {
    return _api.printHTML(printer, html, width, upsideDown);
  }

  @override
  Future<void> printTsplHtml(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
  ) {
    return _api.printTsplHtml(printer, html, width);
  }

  @override
  Future<void> printTsplHtmlWithMedia(
    PrinterConnectionParamsDTO printer,
    String html,
    TsplLabelMediaDTO media,
  ) {
    return _api.printTsplHtmlWithMedia(printer, html, media);
  }

  @override
  Future<void> printTsplRawData(
    PrinterConnectionParamsDTO printer,
    Uint8List labelCommands,
    int width,
  ) {
    return _api.printTsplRawData(printer, labelCommands, width);
  }

  @override
  Future<void> printZplHtml(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
  ) {
    return _api.printZplHtml(printer, html, width);
  }

  @override
  Future<void> printZplRawData(
    PrinterConnectionParamsDTO printer,
    Uint8List labelCommands,
    int width,
  ) {
    return _api.printZplRawData(printer, labelCommands, width);
  }

  @override
  Future<UsbPermissionResult> requestUsbPermission(UsbParams usbDevice) {
    return _api.requestUsbPermission(usbDevice);
  }

  @override
  Future<void> setNetSettingsToPrinter(
    PrinterConnectionParamsDTO printer,
    NetworkParams netSettings,
  ) {
    return _api.setNetSettingsToPrinter(printer, netSettings);
  }

  @override
  Future<void> startDiscoverAllUsbPrinters() {
    return _api.startDiscoverAllUsbPrinters();
  }

  @override
  Future<void> startDiscoveryTCPNetworkPrinters(int port) {
    return _api.startDiscoveryTCPNetworkPrinters(port);
  }

  @override
  Future<void> startDiscoveryXprinterSDKNetworkPrinters() {
    return _api.startDiscoveryXprinterSDKNetworkPrinters();
  }
}
