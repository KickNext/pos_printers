import 'dart:typed_data';

import 'package:pos_printers/enums/paper_size.dart';
import 'package:pos_printers/pos_printers.pigeon.dart';

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
  Future<bool> printHTML(String html, PaperSize paperSize) async {
    final result = await POSPrintersApi().printHTML(params, html, paperSize.value);
    return result.success; // Return the boolean success status
  }

  /// Печать сырых ESC/POS команд.
  Future<bool> printData(Uint8List data, PaperSize paperSize) async {
    final result = await POSPrintersApi().printData(params, data, paperSize.value);
    return result.success; // Return the boolean success status
  }

  /// Открыть денежный ящик.
  Future<bool> openCashBox() async {
    final result = await POSPrintersApi().openCashBox(params);
    return result.success; // Check the success field of OperationResult
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
