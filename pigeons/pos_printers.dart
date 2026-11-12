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
  final String? modelName;

  NetworkParams({
    required this.ipAddress,
    required this.mask,
    required this.gateway,
    required this.macAddress,
    required this.dhcp,
    required this.modelName,
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

/// Результат статуса TSPL‑принтера
class TSPLStatusResult {
  final bool success;
  final int code;
  final String? errorMessage;

  TSPLStatusResult(
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

/// Результат проверки/запроса USB разрешений.
/// Android требует явного запроса разрешения на использование USB устройств.
class UsbPermissionResult {
  /// Указывает, было ли разрешение получено успешно.
  final bool granted;

  /// Сообщение об ошибке, если разрешение не было получено.
  final String? errorMessage;

  /// Информация о устройстве, для которого запрашивалось разрешение.
  final String? deviceInfo;

  UsbPermissionResult({
    required this.granted,
    this.errorMessage,
    this.deviceInfo,
  });
}

// ==================== Strict printing mode (opt-in) ====================
//
// Everything below is additive. The methods above keep their behaviour. The
// `*Strict` methods return an honest outcome instead of throwing/guessing.

/// Mapped outcome of a printer status read or of a strict print job.
///
/// For a job, [ready] means: the bytes were written to the printer and the
/// status read afterwards shows no fault. It does NOT mean "paper came out":
/// ESC/POS printers give no completion signal.
enum PrinterOutcome {
  /// Printer answers and reports no fault (paper near end is still ready).
  ready,

  /// Paper roll is empty.
  noPaper,

  /// Cover is open.
  coverOpen,

  /// Cannot connect, connection dropped, or the printer is paused/offline.
  offline,

  /// Connecting, rendering, the status read or the whole job ran out of time.
  timeout,

  /// Printer fault (cutter, overheat, generic error) or a write error.
  error,

  /// The printer did not give a usable status.
  unknown,
}

/// Where a strict job stopped.
enum PrintStage {
  /// The job ran to the end (see the outcome for the final printer state).
  completed,
  validation,
  render,
  connect,
  preStatus,
  write,
  postStatus,
}

/// Limits of a strict job. Defaults are chosen by the Dart wrapper.
class StrictPrintOptionsDTO {
  /// Connection attempts. Only connecting is retried, never a write.
  final int connectAttempts;

  /// Total time for all connection attempts, in milliseconds.
  final int connectBudgetMs;

  /// Hard limit for the whole job (render, connect, status, write).
  final int jobTimeoutMs;

  /// Limit for one status read.
  final int statusTimeoutMs;

  /// Read the status before sending and refuse to send on a known fault.
  final bool checkStatusBefore;

  /// Read the status after sending and report it.
  final bool checkStatusAfter;

  /// Also refuse to send when the status is unknown or timed out.
  final bool refuseOnUnknownStatus;

  /// Pause between the last write and the status read, in milliseconds.
  final int postStatusDelayMs;

  StrictPrintOptionsDTO({
    required this.connectAttempts,
    required this.connectBudgetMs,
    required this.jobTimeoutMs,
    required this.statusTimeoutMs,
    required this.checkStatusBefore,
    required this.checkStatusAfter,
    required this.refuseOnUnknownStatus,
    required this.postStatusDelayMs,
  });
}

/// Honest ESC/POS status: the SDK code and the mapped outcome.
class PrinterStatusDTO {
  final PrinterOutcome outcome;

  /// Numeric status of the Xprinter SDK 3.5.8 (`POSConst.STS_*`, negative
  /// values are SDK errors); null when no status was obtained.
  final int? statusCode;

  /// Human readable text for logs.
  final String message;
  final int connectAttempts;
  final int elapsedMs;

  PrinterStatusDTO({
    required this.outcome,
    required this.statusCode,
    required this.message,
    required this.connectAttempts,
    required this.elapsedMs,
  });
}

/// Result of a strict print job or cash drawer pulse.
class PrintJobResultDTO {
  final PrinterOutcome outcome;

  /// True when at least one byte was written to the printer. A job with
  /// `sent == true` must not be resent automatically (double print).
  final bool sent;
  final PrintStage stage;

  /// Status codes read before/after sending (null when not read).
  final int? statusBefore;
  final int? statusAfter;
  final int bytesSent;
  final int connectAttempts;
  final int elapsedMs;
  final String? message;

  PrintJobResultDTO({
    required this.outcome,
    required this.sent,
    required this.stage,
    required this.statusBefore,
    required this.statusAfter,
    required this.bytesSent,
    required this.connectAttempts,
    required this.elapsedMs,
    required this.message,
  });
}

@HostApi()
abstract class POSPrintersApi {
  /// Запрашивает разрешение на использование USB устройства у пользователя.
  /// Это необходимо вызывать перед любыми операциями с USB принтером в Android.
  /// Возвращает [UsbPermissionResult] с информацией о результате запроса.
  @async
  UsbPermissionResult requestUsbPermission(UsbParams usbDevice);

  /// Проверяет, есть ли уже разрешение на использование USB устройства.
  /// Не показывает диалог пользователю, только проверяет текущее состояние.
  @async
  UsbPermissionResult hasUsbPermission(UsbParams usbDevice);

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
  StatusResult getPrinterStatus(PrinterConnectionParamsDTO printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  StringResult getPrinterSN(PrinterConnectionParamsDTO printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void openCashBox(PrinterConnectionParamsDTO printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  Uint8List renderHtmlBitmap(String html, int width, bool upsideDown);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void printHTML(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
    bool upsideDown,
  );

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void printData(
    PrinterConnectionParamsDTO printer,
    Uint8List data,
    int width,
    bool upsideDown,
  );

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void setNetSettingsToPrinter(
      PrinterConnectionParamsDTO printer, NetworkParams netSettings);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void configureNetViaUDP(NetworkParams netSettings);

  /// UDP-поиск по SDK: возвращает сетевые параметры принтера с IPv4
  /// [ipAddress] или null, если за [timeoutMs] никто не ответил.
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  NetworkParams? findNetworkPrinterByIp(String ipAddress, int timeoutMs);

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

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void printTsplRawData(
    PrinterConnectionParamsDTO printer,
    Uint8List labelCommands,
    int width,
  );

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void printTsplHtml(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
  );

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  TSPLStatusResult getTSPLPrinterStatus(PrinterConnectionParamsDTO printer);

  // ---- Strict mode (opt-in). These run on their own coroutines, not on the
  // serial task queues, so a dead printer never blocks other printers. Jobs for
  // the same printer are serialised and the whole job is time-boxed.

  /// ESC/POS status with the real SDK code and a mapped outcome.
  @async
  PrinterStatusDTO getPrinterStatusStrict(
    PrinterConnectionParamsDTO printer,
    StrictPrintOptionsDTO options,
  );

  /// Strict raw ESC/POS print: status check, bounded connect retries, no retry
  /// after the first written byte, status read after sending.
  @async
  PrintJobResultDTO printDataStrict(
    PrinterConnectionParamsDTO printer,
    Uint8List data,
    int width,
    bool upsideDown,
    StrictPrintOptionsDTO options,
  );

  /// Strict HTML receipt print (same guarantees as [printDataStrict]).
  @async
  PrintJobResultDTO printHTMLStrict(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
    bool upsideDown,
    StrictPrintOptionsDTO options,
  );

  /// Strict cash drawer pulse: reports connection and write errors.
  @async
  PrintJobResultDTO openCashBoxStrict(
    PrinterConnectionParamsDTO printer,
    StrictPrintOptionsDTO options,
  );

  /// TCP scan limited to at most a /24 around each local address.
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void startDiscoveryTCPNetworkPrintersStrict(int port);
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
