import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:pos_printers/pos_printers.dart';

class FakeNativeClient implements PosPrintersNativeClient {
  NetworkParams? udpConfig;
  int? escWidth;
  TsplLabelMediaDTO? tsplMedia;

  @override
  Future<void> configureNetViaUDP(NetworkParams netSettings) async {
    udpConfig = netSettings;
  }

  @override
  Future<StatusResult> getPrinterStatus(PrinterConnectionParamsDTO printer) {
    throw UnimplementedError();
  }

  @override
  Future<StringResult> getPrinterSN(PrinterConnectionParamsDTO printer) {
    throw UnimplementedError();
  }

  @override
  Future<UsbPermissionResult> hasUsbPermission(UsbParams usbDevice) {
    throw UnimplementedError();
  }

  @override
  Future<void> openCashBox(PrinterConnectionParamsDTO printer) {
    throw UnimplementedError();
  }

  @override
  Future<void> printData(
    PrinterConnectionParamsDTO printer,
    Uint8List data,
    int width,
    bool upsideDown,
  ) {
    throw UnimplementedError();
  }

  @override
  Future<void> printHTML(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
    bool upsideDown,
  ) {
    escWidth = width;
    return Future<void>.value();
  }

  @override
  Future<void> printTsplHtml(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
  ) {
    throw UnimplementedError();
  }

  @override
  Future<void> printTsplHtmlWithMedia(
    PrinterConnectionParamsDTO printer,
    String html,
    TsplLabelMediaDTO media,
  ) {
    tsplMedia = media;
    return Future<void>.value();
  }

  @override
  Future<void> printTsplRawData(
    PrinterConnectionParamsDTO printer,
    Uint8List labelCommands,
    int width,
  ) {
    throw UnimplementedError();
  }

  @override
  Future<void> printZplHtml(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
  ) {
    throw UnimplementedError();
  }

  @override
  Future<void> printZplRawData(
    PrinterConnectionParamsDTO printer,
    Uint8List labelCommands,
    int width,
  ) {
    throw UnimplementedError();
  }

  @override
  Future<UsbPermissionResult> requestUsbPermission(UsbParams usbDevice) {
    throw UnimplementedError();
  }

  @override
  Future<void> setNetSettingsToPrinter(
    PrinterConnectionParamsDTO printer,
    NetworkParams netSettings,
  ) {
    throw UnimplementedError();
  }

  @override
  Future<void> startDiscoverAllUsbPrinters() {
    throw UnimplementedError();
  }

  @override
  Future<void> startDiscoveryTCPNetworkPrinters(int port) {
    throw UnimplementedError();
  }

  @override
  Future<void> startDiscoveryXprinterSDKNetworkPrinters() {
    throw UnimplementedError();
  }

  @override
  Future<TSPLStatusResult> getTSPLPrinterStatus(
    PrinterConnectionParamsDTO printer,
  ) {
    throw UnimplementedError();
  }

  @override
  Future<ZPLStatusResult> getZPLPrinterStatus(
    PrinterConnectionParamsDTO printer,
  ) {
    throw UnimplementedError();
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    PrinterEventRouter.instance.resetForTesting();
  });

  test('legacy UDP configuration copies explicit mac into network settings',
      () async {
    final nativeClient = FakeNativeClient();
    final manager = PosPrintersManager(nativeClient: nativeClient);

    await manager.configureNetViaUDP(
      'AA:BB:CC:DD:EE:FF',
      NetworkParams(
        ipAddress: '192.168.1.50',
        mask: '255.255.255.0',
        gateway: '192.168.1.1',
        macAddress: null,
        dhcp: false,
      ),
    );

    expect(nativeClient.udpConfig?.macAddress, 'AA:BB:CC:DD:EE:FF');
    expect(nativeClient.udpConfig?.ipAddress, '192.168.1.50');
  });

  test('receipt paper helper forwards printable width in dots', () async {
    final nativeClient = FakeNativeClient();
    final manager = PosPrintersManager(nativeClient: nativeClient);

    await manager.printEscHtmlOnPaper(
      _networkPrinter(),
      '<html><body>Receipt</body></html>',
      ReceiptPaper.mm80,
    );

    expect(nativeClient.escWidth, 576);

    manager.dispose();
  });

  test('TSPL media helper forwards explicit physical media', () async {
    final nativeClient = FakeNativeClient();
    final manager = PosPrintersManager(nativeClient: nativeClient);

    await manager.printTsplHtmlOnMedia(
      _networkPrinter(),
      '<html><body>Label</body></html>',
      const TsplLabelMedia(
        width: Millimeters(58),
        height: Millimeters(60),
        gap: Millimeters(2),
        dpi: Dpi(203),
      ),
    );

    expect(nativeClient.tsplMedia?.widthMm, 58);
    expect(nativeClient.tsplMedia?.heightMm, 60);
    expect(nativeClient.tsplMedia?.gapMm, 2);
    expect(nativeClient.tsplMedia?.dpi, 203);
    expect(nativeClient.tsplMedia?.bitmapWidthDots, 464);

    manager.dispose();
  });

  test('multiple managers can observe the same native connection event',
      () async {
    final firstManager = PosPrintersManager(nativeClient: FakeNativeClient());
    final secondManager = PosPrintersManager(nativeClient: FakeNativeClient());
    final firstEvent = firstManager.connectionEvents.first;
    final secondEvent = secondManager.connectionEvents.first;

    PrinterEventRouter.instance.onPrinterAttached(PrinterConnectionParamsDTO(
      id: 'usb:printer',
      connectionType: PosPrinterConnectionType.usb,
      usbParams: UsbParams(
        vendorId: 1,
        productId: 2,
        serialNumber: null,
        manufacturer: null,
        productName: null,
      ),
      networkParams: null,
    ));

    expect((await firstEvent).id, 'usb:printer');
    expect((await secondEvent).id, 'usb:printer');

    firstManager.dispose();
    secondManager.dispose();
  });
}

PrinterConnectionParamsDTO _networkPrinter() {
  return PrinterConnectionParamsDTO(
    id: 'tcp:192.168.1.20',
    connectionType: PosPrinterConnectionType.network,
    usbParams: null,
    networkParams: NetworkParams(
      ipAddress: '192.168.1.20',
      mask: '255.255.255.0',
      gateway: '192.168.1.1',
      macAddress: null,
      dhcp: false,
    ),
  );
}
