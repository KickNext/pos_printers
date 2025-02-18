import 'package:pos_printers/pos_printers.pigeon.dart';

extension PrinterConnectionParamsExt on PrinterConnectionParams {
  Map<String, dynamic> toJson() {
    return {
      'connectionType': connectionType.name,
      'usbPath': usbPath,
      'macAddress': macAddress,
      'ipAddress': ipAddress,
      'mask': mask,
      'gateway': gateway,
      'dhcp': dhcp,
    };
  }

  static PrinterConnectionParams fromJson(Map<String, dynamic> json) {
    return PrinterConnectionParams(
      connectionType: PosPrinterConnectionType.values.firstWhere((e) => e.name == json['connectionType']),
      usbPath: json['usbPath'],
      macAddress: json['macAddress'],
      ipAddress: json['ipAddress'],
      mask: json['mask'],
      gateway: json['gateway'],
      dhcp: json['dhcp'],
    );
  }
}
