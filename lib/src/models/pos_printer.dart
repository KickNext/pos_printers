import 'dart:typed_data';

import 'package:pos_printers/src/enums/paper_size.dart';
import 'package:pos_printers/src/pos_printers.pigeon.dart';

/// Класс для чековых принтеров (ESC/POS).
class POSPrinter {
  /// Параметры подключения, неизменные.
  final PrinterConnectionParams params;

  /// Прочие поля, если необходимо (например, статус, needReboot, etc.).
  /// Здесь, для примера, оставляем их как были.
  String status;
  bool isConnecting;
  bool needReboot;

  POSPrinter({
    required this.params,
    required this.status,
    required this.isConnecting,
    required this.needReboot,
  });

  /// Удобный named-конструктор, если нужно создавать из [PrinterConnectionParams] без остальной логики.
  factory POSPrinter.fromDTO(PrinterConnectionParams dto) {
    return POSPrinter(
      params: dto,
      status: 'Unknown status',
      isConnecting: false,
      needReboot: false,
    );
  }

  /// Подключение к принтеру (ESC/POS).
  Future<void> connectPrinter() async {
    await POSPrintersApi().connectPrinter(params);
  }

  /// Печать HTML (рендерим на нативной стороне).
  Future<void> printHTML(String html, PaperSize paperSize,
      {bool upsideDown = false}) async {
    await POSPrintersApi().printHTML(params, html, paperSize.value, upsideDown);
  }

  /// Печать сырых ESC/POS команд.
  Future<void> printData(Uint8List data, PaperSize paperSize,
      {bool upsideDown = false}) async {
    await POSPrintersApi().printData(params, data, paperSize.value, upsideDown);
  }

  /// Открыть денежный ящик.
  Future<void> openCashBox() async {
    await POSPrintersApi().openCashBox(params);
  }

  /// Обновление сетевых настроек.
  Future<POSPrinter> updateNetSettings({
    required String ip,
    required String mask,
    required String gateway,
    required bool dhcp,
  }) async {
    final netSettings = NetSettingsDTO(
      ipAddress: ip,
      mask: mask,
      gateway: gateway,
      dhcp: dhcp,
    );
    await POSPrintersApi().setNetSettingsToPrinter(params, netSettings);
    // Возвращаем копию с needReboot=true, остальное без изменений.
    return copyWith(needReboot: true);
  }

  /// Скопировать объект, меняя некоторые поля.
  POSPrinter copyWith({
    String? status,
    bool? isConnecting,
    bool? needReboot,
  }) {
    return POSPrinter(
      params: params, // не меняется
      status: status ?? this.status,
      isConnecting: isConnecting ?? this.isConnecting,
      needReboot: needReboot ?? this.needReboot,
    );
  }
}
