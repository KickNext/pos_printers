import 'package:pos_printers/pos_printers.dart';

/// Модель для хранения информации о принтере в пользовательском интерфейсе.
class PrinterItem {
  /// Оригинальный объект обнаруженного принтера
  final DiscoveredPrinterDTO discoveredPrinter;

  /// Параметры подключения к принтеру
  late final PrinterConnectionParams connectionParams;

  /// Флаг, указывающий, является ли это принтером этикеток
  final bool isLabelPrinter;

  /// Язык для принтера этикеток (CPCL, TSPL, ZPL)
  LabelPrinterLanguage? language;

  /// Создаёт объект [PrinterItem] на основе обнаруженного принтера.
  ///
  /// [discoveredPrinter] - найденный принтер из поиска
  /// [language] - опциональный язык для принтера этикеток
  /// [isLabelPrinter] - флаг, определяющий тип принтера (по умолчанию обычный ESC/POS)
  PrinterItem({
    required this.discoveredPrinter,
    this.language,
    this.isLabelPrinter = false,
  }) {
    // Инициализация параметров подключения на основе обнаруженного принтера
    if (discoveredPrinter.type == PosPrinterConnectionType.usb) {
      connectionParams = PrinterConnectionParams(
        connectionType: PosPrinterConnectionType.usb,
        usbParams: UsbParams(
          vendorId: discoveredPrinter.usbParams!.vendorId,
          productId: discoveredPrinter.usbParams!.productId,
          usbSerialNumber: discoveredPrinter.usbParams!.usbSerialNumber,
          manufacturer: discoveredPrinter.usbParams!.manufacturer,
          productName: discoveredPrinter.usbParams!.productName,
        ),
      );
    } else {
      connectionParams = PrinterConnectionParams(
        connectionType: PosPrinterConnectionType.network,
        networkParams: NetworkParams(
          ipAddress: discoveredPrinter.networkParams!.ipAddress,
          macAddress: discoveredPrinter.networkParams!.macAddress,
        ),
      );
    }
  }
}
