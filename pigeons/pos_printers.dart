// ignore_for_file: constant_identifier_names

import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/pos_printers.pigeon.dart',
  dartTestOut: 'test/pos_printers_test.pigeon.dart',
  kotlinOut: 'android/src/main/kotlin/com/kicknext/pos_printers/gen/PosPrintersPluginAPI.kt',
  kotlinOptions: KotlinOptions(
    package: 'com.kicknext.pos_printers.gen',
  ),
))
enum PosPrinterConnectionType {
  usb,
  network,
}

/// Язык принтера (ESC/POS / CPCL / TSPL / ZPL / Unknown)
enum PrinterLanguage {
  escPos,
  cpcl,
  tspl,
  zpl,
  unknown,
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

  PrinterConnectionParams({
    required this.connectionType,
    this.usbPath,
    this.macAddress,
    this.ipAddress,
    this.mask,
    this.gateway,
    this.dhcp,
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

@HostApi()
abstract class POSPrintersApi {
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  bool getPrinters();

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  ConnectResult connectPrinter(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  String getPrinterStatus(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  String getPrinterSN(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  String openCashBox(PrinterConnectionParams printer);

  /// Печать HTML для обычных чековых ESC/POS принтеров.
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  bool printHTML(PrinterConnectionParams printer, String html, int width);

  /// Печать сырых ESC/POS команд.
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  bool printData(PrinterConnectionParams printer, Uint8List data, int width);

  /// Настройка сетевых параметров
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  bool setNetSettingsToPrinter(PrinterConnectionParams printer, NetSettingsDTO netSettings);

  // ====== Новые методы для ЛЕЙБЛ-ПРИНТЕРОВ ======

  /// Печать "сырых" команд (CPCL/TSPL/ZPL), если нужно.
  /// [language] - указываем, какой именно формат (cpcl, tspl, zpl, ...)
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  bool printLabelData(
    PrinterConnectionParams printer,
    PrinterLanguage language,
    Uint8List labelCommands,
    int width,
  );

  /// Печать HTML на лейбл-принтер (рендерим HTML -> bitmap),
  /// [language] - тип команды (cpcl, tspl, zpl) для отправки.
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  bool printLabelHTML(
    PrinterConnectionParams printer,
    PrinterLanguage language,
    String html,
    int width,
    int height,
  );

  /// Установка базовых параметров (размер этикетки, скорость, плотность)
  /// [language] - cpcl, tspl, zpl
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  bool setupLabelParams(
    PrinterConnectionParams printer,
    PrinterLanguage language,
    int labelWidth,
    int labelHeight,
    int densityOrDarkness,
    int speed,
  );
}

@FlutterApi()
abstract class POSPrintersReceiverApi {
  void newPrinter(PrinterConnectionParams message);
  void connectionHandler(ConnectResult message);
}
