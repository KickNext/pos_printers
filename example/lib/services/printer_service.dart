import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:pos_printers/pos_printers.dart';
import '../models/printer_item.dart';
import '../utils/html_templates.dart';

/// Сервис для работы с принтерами, содержит методы для поиска, подключения,
/// и выполнения операций с принтерами
class PrinterService {
  /// Менеджер для взаимодействия с принтерами
  final PosPrintersManager _posPrintersManager = PosPrintersManager();

  /// Запуск поиска принтеров
  Stream<DiscoveredPrinter> findPrinters() {
    final stream = _posPrintersManager.findPrinters();
    return stream;
  }

  /// Ожидание завершения процесса обнаружения
  Future<void> awaitDiscoveryComplete() {
    return _posPrintersManager.awaitDiscoveryComplete();
  }

  /// Очистка ресурсов
  void dispose() {
    _posPrintersManager.dispose();
  }

  /// Сравниваем принтеры по ID (usbPath или ip:port)
  bool samePrinter(DiscoveredPrinter a, DiscoveredPrinter b) {
    return a.id == b.id;
  }

  /// Подключаемся к принтеру
  Future<ConnectResult> connectToPrinter(PrinterItem item) {
    return _posPrintersManager.connectPrinter(item.connectionParams);
  }

  /// Отключаемся от принтера
  Future<void> disconnectPrinter(PrinterItem item) async {
    await _posPrintersManager.disconnectPrinter(item.connectionParams);
  }

  /// Запрос статуса
  Future<StatusResult> getPrinterStatus(PrinterItem item) {
    return _posPrintersManager.getPrinterStatus(item.connectionParams);
  }

  /// Пример печати HTML для чекового (ESC/POS)
  Future<void> printEscHtml(PrinterItem item) async {
    if (item.isLabelPrinter) {
      debugPrint('Skipping ESC/POS HTML print for label printer.');
      throw Exception('Недопустимый тип принтера');
    }
    await _posPrintersManager.printReceiptHTML(
      item.connectionParams,
      "<h1>ESC/POS Html</h1><p>Some text</p>",
      576, // 80mm width in dots (for 203 dpi)
      upsideDown: item.isUpsideDown,
    );
  }

  /// Печать ESC/POS сырых команд
  Future<void> printEscPosData(PrinterItem item) async {
    if (item.isLabelPrinter) {
      debugPrint('Skipping ESC/POS raw print for label printer.');
      throw Exception('Недопустимый тип принтера');
    }
    List<int> bytes = [];
    bytes.addAll([0x1B, 0x40]); // Init
    bytes.addAll([0x1B, 0x61, 0x01]); // Center
    bytes.addAll("Hello ESC/POS\n".codeUnits);
    bytes.add(0x0A); // LF
    bytes.addAll([0x1D, 0x56, 0x41, 0x10]); // Partial cut
    await _posPrintersManager.printReceiptData(
        item.connectionParams, Uint8List.fromList(bytes), 576,
        upsideDown: item.isUpsideDown);
  }

  /// Печать лейбла сырыми командами (CPCL/TSPL/ZPL)
  Future<void> printLabelRaw(PrinterItem item) async {
    if (!item.isLabelPrinter || item.language == null) {
      throw Exception('Требуется указать язык для принтера этикеток');
    }
    String commands;
    switch (item.language!) {
      case LabelPrinterLanguage.cpcl:
        commands =
            "! 0 200 200 320 1\r\nTEXT 4 0 50 50 Hello CPCL\r\nPRINT\r\n";
        break;
      case LabelPrinterLanguage.tspl:
        commands =
            "SIZE 58 mm, 40 mm\r\nGAP 2 mm, 0 mm\r\nCLS\r\nTEXT 50,50,\"ROMAN.TTF\",0,12,12,\"Hello TSPL\"\r\nPRINT 1,1\r\n";
        break;
      case LabelPrinterLanguage.zpl:
        commands =
            "^XA\r\n^PW464\r\n^LL320\r\n^FO50,50^A0N,30,30^FDHello ZPL^FS\r\n^XZ\r\n";
        break;
    }
    final data = Uint8List.fromList(commands.codeUnits);
    await _posPrintersManager.printLabelData(
      item.connectionParams,
      item.language!,
      data,
      576,
    );
  }

  /// Печать HTML на лейбл-принтер
  Future<void> printLabelHtml(PrinterItem item) async {
    if (!item.isLabelPrinter || item.language == null) {
      throw Exception('Требуется указать язык для принтера этикеток');
    }
    final html = generatePriceTagHtml(
      itemName: 'Awesome Gadget',
      price: '99.99',
      barcodeData: '123456789012',
      unit: 'pcs',
    );
    const int widthDots = 464; // 58 * 8
    const int heightDots = 320; // 40 * 8
    await _posPrintersManager.printLabelHTML(
      item.connectionParams,
      item.language!,
      html,
      widthDots,
      heightDots,
    );
  }

  /// Пример смены настроек лейбл (размер, скорость, плотность)
  Future<void> setupLabelParams(PrinterItem item) async {
    if (!item.isLabelPrinter || item.language == null) {
      throw Exception('Требуется указать язык для принтера этикеток');
    }
    const int labelWidthMm = 58;
    const int labelHeightMm = 40;
    const int density = 15;
    const int speed = 4;
    await _posPrintersManager.setupLabelParams(
      item.connectionParams,
      item.language!,
      labelWidthMm,
      labelHeightMm,
      density,
      speed,
    );
  }

  /// Sets network settings via active connection
  Future<void> setNetSettingsViaConnection(
      PrinterItem item, NetSettingsDTO settings) async {
    if (item.discoveredPrinter.type != PosPrinterConnectionType.network) {
      throw Exception('Эта функция только для сетевых принтеров');
    }
    await _posPrintersManager.setNetSettings(item.connectionParams, settings);
  }

  /// Configures network settings via UDP broadcast
  Future<void> configureNetViaUDP(
      PrinterItem item, NetSettingsDTO settings) async {
    final mac = item.discoveredPrinter.macAddress;
    if (mac == null || mac.isEmpty) {
      throw Exception(
          'MAC-адрес не найден для этого принтера. Невозможно настроить по UDP.');
    }
    await _posPrintersManager.configureNetViaUDP(mac, settings);
  }
}
