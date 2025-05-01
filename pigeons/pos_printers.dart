// ignore_for_file: constant_identifier_names

import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/src/pos_printers.pigeon.dart',
  dartTestOut: 'test/pos_printers_test.pigeon.dart',
  kotlinOut:
      'android/src/main/kotlin/com/kicknext/pos_printers/gen/PosPrintersPluginAPI.kt',
  kotlinOptions: KotlinOptions(
    package: 'com.kicknext.pos_printers.gen',
  ),
))
enum PosPrinterConnectionType {
  usb,
  network,
}

enum PrinterLanguage {
  esc,
  zpl;
}

class PrinterConnectionParamsDTO {
  final String id;
  final PosPrinterConnectionType connectionType;
  final UsbParams? usbParams;
  final NetworkParams? networkParams;

  PrinterConnectionParamsDTO({
    required this.id,
    required this.connectionType,
    required this.usbParams,
    required this.networkParams,
  });
}

class UsbParams {
  final int vendorId;
  final int productId;
  final String? serialNumber;
  final String? manufacturer; // Может быть получен при обнаружении (USB)
  final String? productName; // Может быть получен при обнаружении (USB)

  UsbParams({
    required this.vendorId,
    required this.productId,
    required this.serialNumber,
    required this.manufacturer,
    required this.productName,
  });
}

class NetworkParams {
  final String ipAddress;
  final String? mask;
  final String? gateway;
  final String? macAddress;
  final bool? dhcp;

  NetworkParams({
    required this.ipAddress,
    required this.mask,
    required this.gateway,
    required this.macAddress,
    required this.dhcp,
  });
}

/// Результат статуса ZPL‑принтера
class ZPLStatusResult {
  final bool success;
  final int code;
  final String? errorMessage;

  ZPLStatusResult(
      {required this.success, required this.code, this.errorMessage});
}

class StatusResult {
  final bool success;
  final String? errorMessage;
  final String? status;

  StatusResult({required this.success, this.errorMessage, this.status});
}

class StringResult {
  final bool success;
  final String? errorMessage;
  final String? value;

  StringResult({required this.success, this.errorMessage, this.value});
}

class CheckPrinterLanguageResponse {
  final PrinterLanguage printerLanguage;
  final PrinterConnectionParamsDTO connectionParams;

  CheckPrinterLanguageResponse({
    required this.printerLanguage,
    required this.connectionParams,
  });
}

@HostApi()
abstract class POSPrintersApi {

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void startDiscoverAllUsbPrinters();

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void startDiscoveryXprinterSDKNetworkPrinters();

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void startDiscoveryTCPNetworkPrinters(int port);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  CheckPrinterLanguageResponse checkPrinterLanguage(
      PrinterConnectionParamsDTO printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  StatusResult getPrinterStatus(PrinterConnectionParamsDTO printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  StringResult getPrinterSN(PrinterConnectionParamsDTO printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void openCashBox(PrinterConnectionParamsDTO printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void printHTML(PrinterConnectionParamsDTO printer, String html, int width);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void printData(PrinterConnectionParamsDTO printer, Uint8List data, int width);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void setNetSettingsToPrinter(
      PrinterConnectionParamsDTO printer, NetworkParams netSettings);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void configureNetViaUDP(NetworkParams netSettings);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread) // Reverted
  void printZplRawData(
    PrinterConnectionParamsDTO printer,
    Uint8List labelCommands,
    int width,
  );

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread) // Reverted
  void printZplHtml(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
  );

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  ZPLStatusResult getZPLPrinterStatus(PrinterConnectionParamsDTO printer);
}

/// API для получения событий обнаружения принтеров из нативного кода во Flutter.
@FlutterApi()
abstract class PrinterDiscoveryEventsApi {
  void onPrinterFound(PrinterConnectionParamsDTO printer);
  void onDiscoveryComplete(bool success);
  void onDiscoveryError(String errorMessage);
  void onPrinterAttached(PrinterConnectionParamsDTO printer);
  void onPrinterDetached(PrinterConnectionParamsDTO printer);
}
