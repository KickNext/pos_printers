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

/// Язык лейбл-принтера (CPCL / TSPL / ZPL)
enum LabelPrinterLanguage {
  cpcl,
  tspl,
  zpl,
}

/// Фильтр для поиска принтеров по протоколу
enum PrinterDiscoveryFilter { escpos, zpl, all }

/// Тип принтера для определения протокола печати
enum PrinterType { escpos, zpl, unknown }

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

class NetSettingsDTO {
  final String ipAddress;
  final String mask;
  final String gateway;
  final bool dhcp;

  NetSettingsDTO({
    required this.ipAddress,
    required this.mask,
    required this.gateway,
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

/// DTO с расширенной информацией о принтере
// class PrinterDetailsDTO {
//   final String? serialNumber;
//   final String? firmwareVersion;
//   final String? deviceModel;
//   final String? currentStatus;

//   PrinterDetailsDTO({
//     this.serialNumber,
//     this.firmwareVersion,
//     this.deviceModel,
//     this.currentStatus,
//   });
// }

/// Result for getting printer status.
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
  final PosPrinterConnectionType type;
  final PrinterType printerType;
  final UsbParams? usbParams;
  final NetworkParams? networkParams;

  DiscoveredPrinterDTO({
    required this.id,
    required this.type,
    required this.printerType,
    this.usbParams,
    this.networkParams,
  });
}

@HostApi()
abstract class POSPrintersApi {
  /// Инициирует асинхронный поиск принтеров (USB, SDK Net, TCP Net).
  /// [filter] — фильтр типов принтеров (escpos, zpl, all)
  void findPrinters(PrinterDiscoveryFilter filter);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void connectPrinter(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void disconnectPrinter(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  StatusResult getPrinterStatus(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  StringResult getPrinterSN(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void openCashBox(PrinterConnectionParams printer);

  /// Печать HTML для обычных чековых ESC/POS принтеров.
  @async
  @TaskQueue(
      type: TaskQueueType
          .serialBackgroundThread) // Reverted: Concurrent not supported by Pigeon TaskQueueType
  void printHTML(PrinterConnectionParams printer, String html, int width);

  /// Печать сырых ESC/POS команд.
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread) // Reverted
  void printData(PrinterConnectionParams printer, Uint8List data, int width);

  /// Настройка сетевых параметров через существующее соединение
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void setNetSettingsToPrinter(
      PrinterConnectionParams printer, NetSettingsDTO netSettings);

  /// Настройка сетевых параметров через UDP broadcast (требуется MAC-адрес)
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void configureNetViaUDP(String macAddress, NetSettingsDTO netSettings);

  /// Печать "сырых" команд (CPCL/TSPL/ZPL), если нужно.
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread) // Reverted
  void printLabelData(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    Uint8List labelCommands,
    int width,
  );

  /// Печать HTML на лейбл-принтер (рендерим HTML -> bitmap),
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread) // Reverted
  void printLabelHTML(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    String html,
    int width,
    int height,
  );

  /// Получить статус ZPL‑принтера (коды 00–80)
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
