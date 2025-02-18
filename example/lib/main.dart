import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:pos_printers/pos_printers.dart';
import 'package:pos_printers/pos_printers.pigeon.dart';

/// Пример приложения, которое:
/// 1) Ищет доступные принтеры (USB, Network),
/// 2) Позволяет подключаться к нескольким принтерам,
/// 3) Даёт возможность указать язык (escPos, cpcl, tspl, zpl),
/// 4) Печатает и чековые команды, и лейбл-команды.
void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

/// Модель, объединяющая PrinterConnectionParams и выбранный PrinterLanguage.
/// Можно хранить это в своём «промежуточном» классе.
class PrinterItem {
  final PrinterConnectionParams params;
  PrinterLanguage language;

  PrinterItem({
    required this.params,
    this.language = PrinterLanguage.escPos,
  });
}

class _MyAppState extends State<MyApp> {
  /// Менеджер, который ловит колбэки newPrinter(...) и connectionHandler(...)
  final PosPrintersManager _posPrintersManager = PosPrintersManager();

  /// Список найденных принтеров
  final List<PrinterItem> _foundPrinters = [];

  /// Список подключённых
  final List<PrinterItem> _connectedPrinters = [];

  bool _isSearching = false;
  StreamSubscription<PrinterConnectionParams>? _searchSubscription;

  @override
  void initState() {
    super.initState();
    // Подписываемся на поток статусов
    _posPrintersManager.printerStatusStreamController.stream.listen((connectResult) {
      debugPrint('connectionHandler >> success=${connectResult.success} msg=${connectResult.message}');
    });
  }

  @override
  void dispose() {
    _searchSubscription?.cancel();
    _posPrintersManager.printerStatusStreamController.close();
    super.dispose();
  }

  /// Запуск поиска принтеров
  Future<void> _findPrinters() async {
    setState(() {
      _isSearching = true;
      _foundPrinters.clear();
    });

    final stream = _posPrintersManager.findPrinters();
    _searchSubscription = stream.listen(
      (printerParams) {
        setState(() {
          _foundPrinters.add(PrinterItem(params: printerParams));
        });
      },
      onDone: () => setState(() => _isSearching = false),
      onError: (err) {
        debugPrint('Ошибка при поиске: $err');
        setState(() => _isSearching = false);
      },
    );
  }

  /// Подключаемся к принтеру
  Future<void> _connectToPrinter(PrinterItem item) async {
    final result = await POSPrintersApi().connectPrinter(item.params);
    if (result.success) {
      final alreadyIn = _connectedPrinters.any((p) => _samePrinter(p.params, item.params));
      if (!alreadyIn) {
        setState(() => _connectedPrinters.add(item));
      }
    } else {
      debugPrint('Connect failed: ${result.message}');
    }
  }

  /// Сравниваем по usbPath/ipAddress
  bool _samePrinter(PrinterConnectionParams a, PrinterConnectionParams b) {
    if (a.connectionType != b.connectionType) return false;
    if (a.connectionType == PosPrinterConnectionType.usb) {
      return a.usbPath == b.usbPath;
    } else {
      return a.ipAddress == b.ipAddress;
    }
  }

  /// Запрос статуса
  Future<void> _getStatus(PrinterItem item) async {
    final status = await POSPrintersApi().getPrinterStatus(item.params);
    debugPrint('Status => $status');
  }

  /// Пример печати HTML для чекового (ESC/POS)
  Future<void> _printEscHtml(PrinterItem item) async {
    final ok = await POSPrintersApi().printHTML(
      item.params,
      "<h1>ESC/POS Html</h1><p>Some text</p>",
      576, // 80mm
    );
    debugPrint('printHTML success=$ok');
  }

  /// Печать ESC/POS сырых команд
  Future<void> _printEscPosData(PrinterItem item) async {
    List<int> bytes = [];
    // ESC a 1 => центр
    bytes.addAll([0x1B, 0x61, 0x01]);
    bytes.addAll("Hello ESC/POS\n".codeUnits);
    // LF
    bytes.add(0x0A);
    // cut partial
    bytes.addAll([0x1D, 0x56, 0x41, 0x10]);

    final ok = await POSPrintersApi().printData(item.params, Uint8List.fromList(bytes), 576);
    debugPrint('printData => $ok');
  }

  /// Печать лейбла сырыми командами (CPCL/TSPL/ZPL)
  Future<void> _printLabelRaw(PrinterItem item) async {
    // Пример: CPCL
    // Обычно для cpcl: \"! 0 200 200 400 1 ... PRINT\"
    // Для tspl: \"SIZE 60 mm, 40 mm ... PRINT 1\"
    // Для zpl: \"^XA ^FO50,50 ^A0N,30,30 ^FD Hello^FS ^XZ\"
    String commands;
    switch (item.language) {
      case PrinterLanguage.cpcl:
        commands = "! 0 200 200 400 1\\nTEXT 50 50 0 Hello CPCL\\nPRINT\\n";
        break;
      case PrinterLanguage.tspl:
        commands = "SIZE 60 mm,40 mm\\nCLS\\nTEXT 50,50," "4\" + \",0,1,1," "\"Hello TSPL\"\\nPRINT 1\\n";
        break;
      case PrinterLanguage.zpl:
        commands = "^XA^FO50,50^ADN,30,20^FDHello ZPL^FS^XZ";
        break;
      default:
        debugPrint('No label commands for language: ${item.language}');
        return;
    }

    final data = Uint8List.fromList(commands.codeUnits);
    final ok = await POSPrintersApi().printLabelData(
      item.params,
      item.language,
      data,
      576, // not always used, but let's pass
    );
    debugPrint('printLabelData => $ok');
  }

  /// Печать HTML на лейбл-принтер
  Future<void> _printLabelHtml(PrinterItem item) async {
    final html = generatePriceTagHtml(
      itemName: 'Product name',
      price: '123.45',
      barcodeData: '123456789012',
      unit: 'kg',
    );
    final ok = await POSPrintersApi().printLabelHTML(
      item.params,
      item.language,
      html,
      444, // ширина в px
      1000, // высота
    );
    debugPrint('printLabelHTML => $ok');
  }

  /// Пример смены настроек лейбл (размер, скорость, плотность)
  Future<void> _setupLabelParams(PrinterItem item) async {
    final ok = await POSPrintersApi().setupLabelParams(
      item.params,
      item.language,
      64, // width mm
      40, // height mm
      15, // densityOrDarkness
      3, // speed
    );
    debugPrint('setupLabelParams => $ok');
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Multiple Printers Example'),
          actions: [
            if (_connectedPrinters.isNotEmpty)
              IconButton(
                icon: const Icon(Icons.print),
                onPressed: () async {
                  // Пример \"пакетной печати\" на всех
                  for (final p in _connectedPrinters) {
                    if (p.language == PrinterLanguage.escPos) {
                      await _printEscHtml(p);
                      await _printEscPosData(p);
                    } else {
                      await _setupLabelParams(p);
                      await _printLabelHtml(p);
                      await _printLabelRaw(p);
                    }
                  }
                },
              ),
          ],
        ),
        floatingActionButton: FloatingActionButton(
          onPressed: _isSearching ? null : _findPrinters,
          child: _isSearching ? const CircularProgressIndicator(color: Colors.white) : const Icon(Icons.search),
        ),
        body: Column(
          children: [
            // Секция \"Подключённые\"
            if (_connectedPrinters.isNotEmpty)
              Container(
                color: Colors.green.shade50,
                child: Column(
                  children: [
                    const ListTile(
                      title: Text('Connected printers'),
                      subtitle: Text('Tap to get status / change type'),
                    ),
                    for (final cp in _connectedPrinters)
                      ListTile(
                        title: Text(
                          '${cp.params.connectionType.name} => ${cp.params.usbPath ?? cp.params.ipAddress}',
                        ),
                        subtitle: Text('Language: ${cp.language.name}'),
                        onTap: () => _getStatus(cp),
                        trailing: PopupMenuButton<PrinterLanguage>(
                          onSelected: (lang) {
                            setState(() {
                              cp.language = lang;
                            });
                          },
                          itemBuilder: (ctx) => PrinterLanguage.values
                              .map((e) => PopupMenuItem(
                                    value: e,
                                    child: Text(e.name),
                                  ))
                              .toList(),
                        ),
                      ),
                  ],
                ),
              ),
            const Divider(),
            Expanded(
              child: ListView.builder(
                itemCount: _foundPrinters.length,
                itemBuilder: (context, index) {
                  final item = _foundPrinters[index];
                  final title = item.params.connectionType == PosPrinterConnectionType.usb
                      ? 'USB: ${item.params.usbPath}'
                      : 'NET: ${item.params.ipAddress} / MAC: ${item.params.macAddress}';
                  return ListTile(
                    title: Text(title),
                    subtitle: Text('Language: ${item.language.name}'),
                    onTap: () => _connectToPrinter(item),
                    trailing: PopupMenuButton<PrinterLanguage>(
                      onSelected: (lang) {
                        setState(() {
                          item.language = lang;
                        });
                      },
                      itemBuilder: (ctx) => PrinterLanguage.values
                          .map((e) => PopupMenuItem(
                                value: e,
                                child: Text(e.name),
                              ))
                          .toList(),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

String generatePriceTagHtml({
  required String itemName,
  required String price,
  required String unit,
  required String barcodeData,
  String storeName = 'Your Store Name',
}) {
  return '''<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <style>
    body, html {
      margin: 0;
      padding: 0;
      background-color: #f0f0f0;
    }
    .price-tag {
      width: 100%; /* Ширина блока относительно экрана */
      aspect-ratio: 58 / 40; /* Сохранение пропорций */
      background-color: white;
      color: black;
      font-family: Arial, sans-serif;
      padding: 5%;
      box-sizing: border-box;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      align-items: center;
      text-align: center;
    }
    .store-name, .item-name, .price, .unit {
      margin: 0;
    }
    .store-name {
      font-size: 10vw; /* Относительный размер шрифта */
      font-weight: bold;
    }
    .item-name {
      font-size: 14vw;
      font-weight: bold;
    }
    .price {
      font-size: 20vw;
      font-weight: bold;
    }
    .unit {
      font-size: 8vw;
    }
    .barcode {
      width: 80%;
      display: flex;
      justify-content: center;
    }
    .barcode img {
      max-width: 100%;
      height: auto;
    }
  </style>
</head>
<body>
  <div class="price-tag">
    <p class="store-name">$storeName</p>
    <p class="item-name">$itemName</p>
    <p class="price">\$$price</p>
    <p class="unit">Price per $unit</p>
  </div>
</body>
</html>
''';
}
