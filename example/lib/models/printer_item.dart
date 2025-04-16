import 'package:pos_printers/pos_printers.dart';

/// Модель для хранения информации о принтере в пользовательском интерфейсе.
class PrinterItem {
  /// Оригинальный объект обнаруженного принтера
  final DiscoveredPrinter discoveredPrinter;

  /// Параметры подключения к принтеру
  late final PrinterConnectionParams connectionParams;

  /// Флаг, указывающий, является ли это принтером этикеток
  final bool isLabelPrinter;

  /// Язык для принтера этикеток (CPCL, TSPL, ZPL)
  LabelPrinterLanguage? language;

  /// Флаг для режима перевёрнутой печати
  bool isUpsideDown = false;

  /// Создаёт объект [PrinterItem] на основе обнаруженного принтера.
  ///
  /// [discoveredPrinter] - найденный принтер из поиска
  /// [language] - опциональный язык для принтера этикеток
  /// [isLabelPrinter] - флаг, определяющий тип принтера (по умолчанию обычный ESC/POS)
  PrinterItem({
    required this.discoveredPrinter,
    this.language,
    this.isLabelPrinter = false,
    bool? upsideDown,
  }) {
    // Инициализация параметров подключения на основе обнаруженного принтера
    if (discoveredPrinter.type == 'usb') {
      connectionParams = PrinterConnectionParams(
        connectionType: PosPrinterConnectionType.usb,
        vendorId: discoveredPrinter.vendorId,
        productId: discoveredPrinter.productId,
        manufacturer: discoveredPrinter.manufacturer,
        productName: discoveredPrinter.productName,
        usbSerialNumber: discoveredPrinter.usbSerialNumber,
      );
    } else {
      // Предполагаем, что сетевой принтер
      final ipParts = discoveredPrinter.id.split(':');
      final ip = ipParts.isNotEmpty ? ipParts[0] : '';

      connectionParams = PrinterConnectionParams(
        connectionType: PosPrinterConnectionType.network,
        ipAddress: ip,
        macAddress: discoveredPrinter.macAddress,
      );
    }

    // Инициализация флага перевёрнутой печати, если задан
    if (upsideDown != null) {
      isUpsideDown = upsideDown;
    }
  }
}
