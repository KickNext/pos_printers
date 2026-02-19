import 'dart:developer' as developer;
import 'dart:async';
import 'package:flutter/services.dart'; // Required for PlatformException
import 'package:pos_printers/pos_printers.dart';

/// Типы событий подключения/отключения принтера
enum PrinterConnectionEventType { attached, detached }

/// Событие подключения или отключения принтера (обычно USB)
class PrinterConnectionEvent {
  final PrinterConnectionEventType type;
  final PrinterConnectionParamsDTO? printer;
  final String? id;
  final String? message;
  PrinterConnectionEvent({
    required this.type,
    this.printer,
    this.id,
    this.message,
  });
}

/// Исключение, выбрасываемое при отказе пользователя в USB-разрешении.
///
/// Содержит информацию об устройстве и сообщение об ошибке.
class UsbPermissionDeniedException implements Exception {
  /// Сообщение об ошибке
  final String message;

  /// Информация об устройстве (название, производитель)
  final String? deviceInfo;

  UsbPermissionDeniedException({
    required this.message,
    this.deviceInfo,
  });

  @override
  String toString() {
    if (deviceInfo != null) {
      return 'UsbPermissionDeniedException: $message (Device: $deviceInfo)';
    }
    return 'UsbPermissionDeniedException: $message';
  }
}

/// Главный класс для управления POS-принтерами.
///
/// Предоставляет методы для:
/// - Обнаружения принтеров (USB, TCP, SDK)
/// - Печати (ESC/POS, ZPL, TSPL)
/// - Управления USB-разрешениями
/// - Настройки сети принтера
///
/// Пример использования:
/// ```dart
/// final manager = PosPrintersManager();
///
/// // Поиск принтеров
/// await for (final printer in manager.findPrinters(filter: null)) {
///   print('Найден: ${printer.id}');
/// }
///
/// // Запрос USB-разрешения и печать
/// await manager.withUsbPermission(printer, () async {
///   await manager.printEscHTML(printer, '<h1>Test</h1>', 384);
/// });
/// ```
class PosPrintersManager implements PrinterDiscoveryEventsApi {
  static const String _logTag = 'PosPrintersManager';

  final POSPrintersApi _api = POSPrintersApi();

  /// Stream controller for emitting discovered printers during a scan.
  /// Use broadcast to allow multiple listeners if needed, though typically one is enough.
  StreamController<PrinterConnectionParamsDTO>? _printerDiscoveryController;

  /// Stream providing discovered printers. Listen to this after calling [findPrinters].
  /// The stream closes when discovery is complete or an error occurs.
  Stream<PrinterConnectionParamsDTO> get discoveryStream =>
      _printerDiscoveryController?.stream ?? const Stream.empty();

  /// Completer to signal the end of the discovery process (success or failure).
  Completer<void>? _discoveryCompleter;

  final _connectionEventsController =
      StreamController<PrinterConnectionEvent>.broadcast();
  Stream<PrinterConnectionEvent> get connectionEvents =>
      _connectionEventsController.stream;

  /// Initializes the manager and sets up the receiver for native callbacks.
  PosPrintersManager() {
    // Set up the handler for native calls to the FlutterApi
    PrinterDiscoveryEventsApi.setUp(this);
  }

  /// Disposes resources. Call this when the manager is no longer needed.
  void dispose() {
    _printerDiscoveryController?.close();
    _discoveryCompleter?.completeError(StateError(
        "Manager disposed during discovery")); // Signal error if ongoing
    _connectionEventsController.close();
    PrinterDiscoveryEventsApi.setUp(null); // Detach the receiver
  }

  @override
  void onPrinterFound(PrinterConnectionParamsDTO printer) {
    _printerDiscoveryController?.add(printer);
  }

  @override
  void onDiscoveryComplete(bool success) {
    _finishDiscovery();
  }

  @override
  void onPrinterAttached(PrinterConnectionParamsDTO printer) {
    developer.log('USB printer attached: ${printer.id}', name: _logTag);
    _connectionEventsController.add(PrinterConnectionEvent(
      type: PrinterConnectionEventType.attached,
      printer: printer,
      id: printer.id,
      message: 'USB attached: ${printer.id}',
    ));
  }

  @override
  void onPrinterDetached(PrinterConnectionParamsDTO printer) {
    _connectionEventsController.add(PrinterConnectionEvent(
      type: PrinterConnectionEventType.detached,
      printer: printer,
      id: printer.id,
      message: 'USB detached:  ${printer.id}',
    ));
  }

  Stream<PrinterConnectionParamsDTO> findPrinters({
    required PrinterDiscoveryFilter? filter,
  }) {
    if (_printerDiscoveryController != null &&
        !_printerDiscoveryController!.isClosed) {
      throw StateError("Discovery is already in progress.");
    }
    _printerDiscoveryController?.close();
    _printerDiscoveryController =
        StreamController<PrinterConnectionParamsDTO>.broadcast();
    _discoveryCompleter = Completer<void>();
    try {
      unawaited(_startDiscoverPrinters(filter: filter));
      return _printerDiscoveryController!.stream;
    } catch (e) {
      _printerDiscoveryController?.addError(e);
      _printerDiscoveryController?.close();
      _discoveryCompleter?.completeError(e);
      _printerDiscoveryController = null;
      _discoveryCompleter = null;
      return Stream.error(Exception('Unexpected error starting discovery: $e'));
    }
  }

  Future<void> _startDiscoverPrinters({
    required PrinterDiscoveryFilter? filter,
  }) async {
    try {
      final types = filter?.connectionTypes;
      final discoverAll = types == null || types.isEmpty;

      if (discoverAll || types.contains(DiscoveryConnectionType.usb)) {
        await _api.startDiscoverAllUsbPrinters();
      }
      if (discoverAll || types.contains(DiscoveryConnectionType.sdk)) {
        await _api.startDiscoveryXprinterSDKNetworkPrinters();
      }
      if (discoverAll || types.contains(DiscoveryConnectionType.tcp)) {
        await _api.startDiscoveryTCPNetworkPrinters(9100);
      }

      _finishDiscovery();
    } catch (error, stackTrace) {
      _finishDiscovery(error: error, stackTrace: stackTrace);
    }
  }

  /// Awaits the completion of the current discovery process.
  /// Throws an error if discovery fails.
  Future<void> awaitDiscoveryComplete() async {
    if (_discoveryCompleter == null) {
      throw StateError("Discovery not started.");
    }
    return _discoveryCompleter!.future;
  }

  /// Gets the current status of the connected printer.
  ///
  /// Returns a [StatusResult] containing the success status, error message (if any),
  /// and the status string itself.
  Future<StatusResult> getPrinterStatus(
      PrinterConnectionParamsDTO printer) async {
    return _api.getPrinterStatus(printer);
  }

  /// Gets the serial number (SN) of the connected printer.
  ///
  /// Returns a [StringResult] containing the success status, error message (if any),
  /// and the serial number string.
  Future<StringResult> getPrinterSN(PrinterConnectionParamsDTO printer) async {
    return _api.getPrinterSN(printer);
  }

  /// Opens the cash drawer connected to the printer.
  Future<void> openCashBox(PrinterConnectionParamsDTO printer) async {
    return _api.openCashBox(printer);
  }

  /// Prints HTML content on a standard ESC/POS receipt printer.
  ///
  /// [printer]: Connection parameters of the target printer.
  /// [html]: The HTML string to print.
  /// [width]: The printing width in dots.
  /// [upsideDown]: Печать в перевернутом режиме (180°), если поддерживается SDK.
  Future<void> printEscHTML(
    PrinterConnectionParamsDTO printer,
    String html,
    int width, {
    bool upsideDown = false,
  }) async {
    return _api.printHTML(printer, html, width, upsideDown);
  }

  /// Sends raw ESC/POS commands к чековому принтеру.
  Future<void> printEscRawData(
    PrinterConnectionParamsDTO printer,
    Uint8List data,
    int width, {
    bool upsideDown = false,
  }) async {
    return _api.printData(printer, data, width, upsideDown);
  }

  /// Configures network settings for a printer (usually via USB connection initially).
  ///
  /// [printer]: Connection parameters of the target printer (often USB).
  /// [netSettings]: The new network settings to apply.
  Future<void> setNetSettings(
      PrinterConnectionParamsDTO printer, NetworkParams netSettings) async {
    return _api.setNetSettingsToPrinter(printer, netSettings);
  }

  /// Configures network settings via UDP broadcast.
  ///
  /// [macAddress]: The MAC address of the target printer.
  /// [netSettings]: The network settings to apply.
  Future<void> configureNetViaUDP(
      String macAddress, NetworkParams netSettings) async {
    return _api.configureNetViaUDP(netSettings);
  }

  // --- Label Printer Specific Methods ---

  /// Sends raw commands (CPCL, TSPL, или ZPL) к принтеру.
  Future<void> printZplRawData(
    PrinterConnectionParamsDTO printer,
    Uint8List labelCommands,
    int width,
  ) async {
    return _api.printZplRawData(printer, labelCommands, width);
  }

  /// Prints HTML content rendered as a bitmap on a label printer.
  Future<void> printZplHtml(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
  ) async {
    return _api.printZplHtml(printer, html, width);
  }

  /// Получить статус ZPL‑принтера (коды 00–80)
  Future<ZPLStatusResult> getZPLPrinterStatus(
      PrinterConnectionParamsDTO printer) async {
    return _api.getZPLPrinterStatus(printer);
  }

  /// Отправка сырых TSPL-команд принтеру.
  Future<void> printTsplRawData(
    PrinterConnectionParamsDTO printer,
    Uint8List labelCommands,
    int width,
  ) async {
    return _api.printTsplRawData(printer, labelCommands, width);
  }

  /// Печать HTML как TSPL-этикетки.
  Future<void> printTsplHtml(
    PrinterConnectionParamsDTO printer,
    String html,
    int width,
  ) async {
    return _api.printTsplHtml(printer, html, width);
  }

  /// Получить статус TSPL-принтера
  Future<TSPLStatusResult> getTSPLPrinterStatus(
      PrinterConnectionParamsDTO printer) async {
    return _api.getTSPLPrinterStatus(printer);
  }

  // ==================== USB Permission Methods ====================

  /// Запрашивает разрешение на использование USB-устройства у пользователя.
  ///
  /// В Android для работы с USB-устройствами необходимо получить разрешение
  /// от пользователя. Этот метод показывает системный диалог с запросом.
  ///
  /// **ВАЖНО**: Этот метод должен быть вызван перед любыми операциями
  /// с USB-принтером (печать, получение статуса и т.д.), иначе будет
  /// ошибка "USB permission denied".
  ///
  /// Пример использования:
  /// ```dart
  /// final result = await manager.requestUsbPermission(printer.usbParams!);
  /// if (result.granted) {
  ///   // Можно работать с принтером
  ///   await manager.printEscHTML(printer, html, 384);
  /// } else {
  ///   print('Пользователь отказал в доступе: ${result.errorMessage}');
  /// }
  /// ```
  ///
  /// [usbParams] - параметры USB-устройства (vendorId, productId, serialNumber)
  ///
  /// Возвращает [UsbPermissionResult] с информацией о результате:
  /// - [granted] - true если разрешение получено
  /// - [errorMessage] - сообщение об ошибке (если не получено)
  /// - [deviceInfo] - информация об устройстве
  Future<UsbPermissionResult> requestUsbPermission(UsbParams usbParams) async {
    developer.log(
        'Requesting USB permission for VID=${usbParams.vendorId}, PID=${usbParams.productId}',
        name: _logTag);
    return _api.requestUsbPermission(usbParams);
  }

  /// Проверяет, есть ли уже разрешение на использование USB-устройства.
  ///
  /// Этот метод **не показывает** диалог пользователю, только проверяет
  /// текущее состояние разрешения.
  ///
  /// Полезен для проверки перед операциями, чтобы понять нужно ли
  /// запрашивать разрешение.
  ///
  /// [usbParams] - параметры USB-устройства
  ///
  /// Возвращает [UsbPermissionResult] с текущим состоянием разрешения.
  Future<UsbPermissionResult> hasUsbPermission(UsbParams usbParams) async {
    return _api.hasUsbPermission(usbParams);
  }

  /// Удобный метод для работы с USB-принтером с автоматическим запросом разрешения.
  ///
  /// Проверяет разрешение, запрашивает его при необходимости, и выполняет
  /// переданную операцию только при успешном получении разрешения.
  ///
  /// [printer] - параметры подключения принтера
  /// [operation] - операция для выполнения после получения разрешения
  ///
  /// Выбрасывает исключение если:
  /// - Принтер не USB
  /// - Не удалось получить разрешение
  Future<T> withUsbPermission<T>(
    PrinterConnectionParamsDTO printer,
    Future<T> Function() operation,
  ) async {
    // Проверяем что это USB-принтер
    if (printer.connectionType != PosPrinterConnectionType.usb) {
      // Для сетевых принтеров разрешение не требуется
      return operation();
    }

    final usbParams = printer.usbParams;
    if (usbParams == null) {
      throw ArgumentError('USB params are required for USB printer');
    }

    // Проверяем есть ли уже разрешение
    final hasPermission = await hasUsbPermission(usbParams);
    if (hasPermission.granted) {
      return operation();
    }

    // Запрашиваем разрешение
    developer.log('USB permission not granted, requesting...', name: _logTag);
    final result = await requestUsbPermission(usbParams);

    if (!result.granted) {
      throw UsbPermissionDeniedException(
        message: result.errorMessage ?? 'USB permission denied',
        deviceInfo: result.deviceInfo,
      );
    }

    // Выполняем операцию
    return operation();
  }

  @override
  void onDiscoveryError(String errorMessage) {
    _finishDiscovery(error: errorMessage);
  }

  void _finishDiscovery({Object? error, StackTrace? stackTrace}) {
    final controller = _printerDiscoveryController;
    final completer = _discoveryCompleter;

    if (error != null && controller != null && !controller.isClosed) {
      controller.addError(error, stackTrace);
    }
    if (controller != null && !controller.isClosed) {
      controller.close();
    }

    if (completer != null && !completer.isCompleted) {
      if (error == null) {
        completer.complete();
      } else {
        completer.completeError(error, stackTrace);
      }
    }

    _printerDiscoveryController = null;
    _discoveryCompleter = null;
  }
}
