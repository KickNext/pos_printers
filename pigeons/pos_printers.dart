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

class PrinterDiscoveryFilter {
  final List<PrinterLanguage>? languages;
  final List<DiscoveryConnectionType>? connectionTypes;

  PrinterDiscoveryFilter(
      {required this.languages, required this.connectionTypes});
}

enum PrinterLanguage {
  esc,
  zpl;
}

enum DiscoveryConnectionType {
  usb,
  sdk,
  tcp;
}

class PrinterConnectionParams {
  final PosPrinterConnectionType connectionType;
  final UsbParams? usbParams;
  final NetworkParams? networkParams;

  PrinterConnectionParams({
    required this.connectionType,
    required this.usbParams,
    required this.networkParams,
  });
}

class UsbParams {
  final int vendorId;
  final int productId;
  final String? usbSerialNumber;
  final String? manufacturer; // Может быть получен при обнаружении (USB)
  final String? productName; // Может быть получен при обнаружении (USB)

  UsbParams({
    required this.vendorId,
    required this.productId,
    required this.usbSerialNumber,
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

class DiscoveredPrinterDTO {
  final String id;
  final PrinterLanguage? printerLanguage;
  final PrinterConnectionParams connectionParams;

  DiscoveredPrinterDTO({
    required this.id,
    required this.printerLanguage,
    required this.connectionParams,
  });
}

@HostApi()
abstract class POSPrintersApi {
  void findPrinters(PrinterDiscoveryFilter? filter);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  StatusResult getPrinterStatus(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  StringResult getPrinterSN(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void openCashBox(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void printHTML(PrinterConnectionParams printer, String html, int width);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread) // Reverted
  void printData(PrinterConnectionParams printer, Uint8List data, int width);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void setNetSettingsToPrinter(
      PrinterConnectionParams printer, NetworkParams netSettings);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void configureNetViaUDP(NetworkParams netSettings);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread) // Reverted
  void printZplRawData(
    PrinterConnectionParams printer,
    Uint8List labelCommands,
    int width,
  );

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread) // Reverted
  void printZplHtml(
    PrinterConnectionParams printer,
    String html,
    int width,
    int height,
  );

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  ZPLStatusResult getZPLPrinterStatus(PrinterConnectionParams printer);
}

/// API для получения событий обнаружения принтеров из нативного кода во Flutter.
@FlutterApi()
abstract class PrinterDiscoveryEventsApi {
  /// Вызывается при обнаружении нового (уникального) принтера.
  /// `printer` содержит информацию о найденном принтере. Используйте стабильные
  /// идентификаторы из него (`vendorId`/`productId`/`usbSerialNumber` или `ipAddress`)
  /// для создания `PrinterConnectionParams` при вызове `connectPrinter`.
  void onPrinterFound(DiscoveredPrinterDTO printer);

  /// Вызывается по завершении всего процесса поиска.
  /// `success` = true, если поиск завершился без критических ошибок (даже если ничего не найдено).
  /// `errorMessage` содержит сообщение об ошибке, если `success` = false.
  void onDiscoveryComplete(bool success, String? errorMessage);
  void onPrinterAttached(DiscoveredPrinterDTO printer);
  void onPrinterDetached(String id);
}
