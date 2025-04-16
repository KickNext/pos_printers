import 'package:pos_printers/src/pos_printers.pigeon.dart';

extension PrinterConnectionParamsExt on PrinterConnectionParams {
  Map<String, dynamic> toJson() {
    return {
      'connectionType': connectionType.name,
      'vendorId': vendorId,
      'productId': productId,
      'usbSerialNumber': usbSerialNumber,
      'macAddress': macAddress,
      'ipAddress': ipAddress,
      'mask': mask,
      'gateway': gateway,
      'dhcp': dhcp,
      'manufacturer': manufacturer,
      'productName': productName,
    };
  }

  static PrinterConnectionParams fromJson(Map<String, dynamic> json) {
    return PrinterConnectionParams(
      connectionType: PosPrinterConnectionType.values
          .firstWhere((e) => e.name == json['connectionType']),
      vendorId: json['vendorId'],
      productId: json['productId'],
      usbSerialNumber: json['usbSerialNumber'],
      macAddress: json['macAddress'],
      ipAddress: json['ipAddress'],
      mask: json['mask'],
      gateway: json['gateway'],
      dhcp: json['dhcp'],
      manufacturer: json['manufacturer'],
      productName: json['productName'],
    );
  }
}
