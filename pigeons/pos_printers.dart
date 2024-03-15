// ignore_for_file: constant_identifier_names

import 'package:pigeon/pigeon.dart';

// flutter pub run pigeon --input pigeons/pos_printers.dart

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/pos_printers.pigeon.dart',
    dartTestOut: 'test/pos_printers_test.pigeon.dart',
    dartOptions: DartOptions(
      sourceOutPath: 'pigeions/pos_printers.dart',
    ),
    kotlinOut: 'android/src/main/kotlin/com/kicknext/pos_printers/gen/PosPrintersPluginAPI.kt',
    kotlinOptions: KotlinOptions(
      package: 'com.kicknext.pos_printers.gen',
    ),
  ),
)
enum PosPrinterConnectionType {
  usb,
  network,
}

class XPrinterDTO {
  final PosPrinterConnectionType connectionType;
  final String? usbPath;
  final String? macAddress;
  final String? ipAddress;
  final String? mask;
  final String? gateway;
  final bool? dhcp;

  XPrinterDTO({
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

  NetSettingsDTO({required this.ipAddress, required this.mask, required this.gateway, required this.dhcp});
}

class ConnectResult {
  final bool success;
  final String? message;

  ConnectResult({required this.success, this.message});
}

@HostApi()
abstract class POSPrintersApi {
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  bool getPrinters();

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  ConnectResult connectPrinter(XPrinterDTO printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  String getPrinterStatus(XPrinterDTO printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  String getPrinterSN(XPrinterDTO printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  String openCashBox(XPrinterDTO printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  bool printHTML(String html, int width);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  bool setNetSettingsToPrinter(XPrinterDTO printer, NetSettingsDTO netSettings);
}

@FlutterApi()
abstract class POSPrintersReceiverApi {
  void newPrinter(XPrinterDTO message);
  void connectionHandler(ConnectResult message);
}
