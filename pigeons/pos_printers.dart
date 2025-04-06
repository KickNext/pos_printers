// ignore_for_file: constant_identifier_names

import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/pos_printers.pigeon.dart',
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

/// DTO c настройками подключения (USB, Network, etc.)
/// Без поля PrinterLanguage.
class PrinterConnectionParams {
  final PosPrinterConnectionType connectionType;
  final String? usbPath;
  final String? macAddress;
  final String? ipAddress;
  final String? mask;
  final String? gateway;
  final bool? dhcp;
  // Новые поля для USB-идентификации
  final int? vendorId;
  final int? productId;
  final String? manufacturer;
  final String? productName;
  final String? usbSerialNumber;

  PrinterConnectionParams({
    required this.connectionType,
    this.usbPath, // Путь все еще нужен для подключения через SDK
    this.macAddress,
    this.ipAddress,
    this.mask,
    this.gateway,
    this.dhcp,
    this.vendorId,
    this.productId,
    this.manufacturer,
    this.productName,
    this.usbSerialNumber,
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

class ConnectResult {
  final bool success;
  final String? message;

  ConnectResult({
    required this.success,
    this.message,
  });
}

/// DTO с расширенной информацией о принтере
class PrinterDetailsDTO {
  final String? serialNumber;
  final String? firmwareVersion; // Пример, если SDK позволяет
  final String? deviceModel; // Пример, если SDK позволяет
  final String? currentStatus; // Статус, полученный от getPrinterStatus

  PrinterDetailsDTO({
    this.serialNumber,
    this.firmwareVersion,
    this.deviceModel,
    this.currentStatus,
  });
}

/// Generic result for operations that succeed or fail with an optional message.
class OperationResult {
  final bool success;
  final String? errorMessage;

  OperationResult({required this.success, this.errorMessage});
}

/// Result for getting printer status.
class StatusResult {
  final bool success;
  final String? errorMessage;
  final String? status; // The actual status string if successful

  StatusResult({required this.success, this.errorMessage, this.status});
}

/// Result for getting printer serial number or other string values.
class StringResult {
   final bool success;
   final String? errorMessage;
   final String? value; // The actual string value (e.g., SN) if successful

   StringResult({required this.success, this.errorMessage, this.value});
}

/// Result for the initial printer discovery call.
/// Note: Individual printers are still sent via `newPrinter` callback.
/// This result indicates if the scan *started* successfully.
class ScanInitiationResult {
  final bool success;
  final String? errorMessage;
  // Optional: Could include an initial list if the native side can provide one quickly
  // final List<PrinterConnectionParams?>? initialPrinters;

  ScanInitiationResult({required this.success, this.errorMessage});
}


@HostApi()
abstract class POSPrintersApi {
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  ScanInitiationResult getPrinters();

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  ConnectResult connectPrinter(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  OperationResult disconnectPrinter(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  StatusResult getPrinterStatus(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  StringResult getPrinterSN(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  OperationResult openCashBox(PrinterConnectionParams printer);

  /// Печать HTML для обычных чековых ESC/POS принтеров.
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  OperationResult printHTML(PrinterConnectionParams printer, String html, int width);

  /// Печать сырых ESC/POS команд.
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  OperationResult printData(PrinterConnectionParams printer, Uint8List data, int width);

  /// Настройка сетевых параметров
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  OperationResult setNetSettingsToPrinter(
      PrinterConnectionParams printer, NetSettingsDTO netSettings);

  // ====== Новые методы для ЛЕЙБЛ-ПРИНТЕРОВ ======

  /// Печать "сырых" команд (CPCL/TSPL/ZPL), если нужно.
  /// [language] - указываем, какой именно формат (cpcl, tspl, zpl, ...)
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  OperationResult printLabelData(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    Uint8List labelCommands,
    int width,
  );

  /// Печать HTML на лейбл-принтер (рендерим HTML -> bitmap),
  /// [language] - тип команды (cpcl, tspl, zpl) для отправки.
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  OperationResult printLabelHTML(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    String html,
    int width,
    int height,
  );

  /// Установка базовых параметров (размер этикетки, скорость, плотность)
  /// [language] - cpcl, tspl, zpl
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  OperationResult setupLabelParams(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    int labelWidth,
    int labelHeight,
    int densityOrDarkness,
    int speed,
  );

  /// Получение расширенной информации о принтере
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  PrinterDetailsDTO getPrinterDetails(PrinterConnectionParams printer);
}

@FlutterApi()
abstract class POSPrintersReceiverApi {
  void newPrinter(PrinterConnectionParams message);
  void connectionHandler(ConnectResult message);
  /// Called by native code when the printer scan process is complete.
  void scanCompleted(bool success, String? errorMessage);
}
