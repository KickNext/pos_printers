import 'package:pos_printers/enums/paper_size.dart';
import 'package:pos_printers/pos_printers.pigeon.dart';

class POSPrinter {
  final PosPrinterConnectionType connectionType;
  final String? usbPath;
  final String? mac;
  final String? ip;
  final String? mask;
  final String? gateway;
  final bool? dhcp;
  final String status;
  final bool isConnecting;
  bool needReboot;

  POSPrinter(
      {required this.connectionType,
      required this.usbPath,
      required this.mac,
      required this.ip,
      required this.mask,
      required this.gateway,
      required this.dhcp,
      required this.status,
      required this.isConnecting,
      required this.needReboot});

  factory POSPrinter.fromDTO(XPrinterDTO dto) {
    return POSPrinter(
      connectionType: dto.connectionType,
      usbPath: dto.usbPath,
      mac: dto.macAddress,
      ip: dto.ipAddress,
      mask: dto.mask,
      gateway: dto.gateway,
      dhcp: dto.dhcp,
      status: 'Unknown status',
      isConnecting: false,
      needReboot: false,
    );
  }

  Future<void> connectPrinter() async {
    final result = await POSPrintersApi().connectPrinter(toDTO());
    if (result.success) {
      return;
    } else {
      throw Exception(result.message);
    }
  }

  Future<bool> printHTML(String html, PaperSize paperSize) async {
    final result = await POSPrintersApi().printHTML(html, paperSize.value);
    return result;
  }

  Future<bool> openCashBox() async {
    final result = await POSPrintersApi().openCashBox(toDTO());
    return result.isNotEmpty;
  }

  POSPrinter copyWith({
    PosPrinterConnectionType? connectionType,
    String? usbPath,
    String? mac,
    String? ip,
    String? mask,
    String? gateway,
    bool? dhcp,
    String? status,
    bool? isConnecting,
    bool? needReboot,
  }) {
    return POSPrinter(
      connectionType: connectionType ?? this.connectionType,
      usbPath: usbPath ?? this.usbPath,
      mac: mac ?? this.mac,
      ip: ip ?? this.ip,
      mask: mask ?? this.mask,
      gateway: gateway ?? this.gateway,
      dhcp: dhcp ?? this.dhcp,
      status: status ?? this.status,
      isConnecting: isConnecting ?? this.isConnecting,
      needReboot: needReboot ?? this.needReboot,
    );
  }

  Future<POSPrinter> updateNetSettings(
      {required String ip, required String mask, required String gateway, required bool dhcp}) async {
    final NetSettingsDTO netSettings = NetSettingsDTO(
      ipAddress: ip,
      mask: mask,
      gateway: gateway,
      dhcp: dhcp,
    );
    await POSPrintersApi().setNetSettingsToPrinter(toDTO(), netSettings);
    return copyWith(
      ip: ip,
      mask: mask,
      gateway: gateway,
      dhcp: dhcp,
      needReboot: true,
    );
  }

  XPrinterDTO toDTO() {
    return XPrinterDTO(
      connectionType: connectionType,
      usbPath: usbPath,
      macAddress: mac,
      ipAddress: ip,
      mask: mask,
      gateway: gateway,
      dhcp: dhcp,
    );
  }
}
