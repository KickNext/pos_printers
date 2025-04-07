import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart'; // Needed for Uint8List and PlatformException
import 'package:pos_printers/pos_printers.dart';
import 'package:pos_printers/pos_printers.pigeon.dart'; // Keep for DTOs and Enums

/// Пример приложения, которое:
/// 1) Ищет доступные принтеры (USB, Network),
/// 2) Позволяет подключаться к нескольким принтерам,
/// 3) Даёт возможность указать тип/язык (ESC/POS, CPCL, TSPL, ZPL),
/// 4) Печатает и чековые команды, и лейбл-команды.
void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

/// Модель, объединяющая PrinterConnectionParams и выбранный язык (если это лейбл-принтер).
class PrinterItem {
  final PrinterConnectionParams params;
  // Язык нужен только для лейбл-принтеров. Для ESC/POS методы его не требуют.
  // Сделаем nullable, чтобы обозначить, что это может быть ESC/POS или язык не выбран.
  LabelPrinterLanguage? language;
  // Добавим флаг для удобства
  bool get isLabelPrinter => language != null;

  PrinterItem({
    required this.params,
    this.language, // По умолчанию null (ESC/POS или не выбран)
  });
}

class _MyAppState extends State<MyApp> {
  /// Менеджер для взаимодействия с принтерами
  final PosPrintersManager _posPrintersManager = PosPrintersManager();

  /// Глобальный ключ для ScaffoldMessenger
  final GlobalKey<ScaffoldMessengerState> _scaffoldMessengerKey =
      GlobalKey<ScaffoldMessengerState>();

  /// Список найденных принтеров
  final List<PrinterItem> _foundPrinters = [];

  /// Список подключённых
  final List<PrinterItem> _connectedPrinters = [];

  bool _isSearching = false;
  StreamSubscription<PrinterConnectionParams>? _searchSubscription;
  StreamSubscription<ConnectResult>? _connectionEventsSubscription;

  @override
  void initState() {
    super.initState();
    // Подписываемся на поток событий подключения
    _connectionEventsSubscription =
        _posPrintersManager.connectionEvents.listen((connectResult) {
      // TODO: Добавить более детальную обработку событий (USB attach/detach)
      // Например, удалять принтер из _connectedPrinters при USB_DETACHED
      // или обновлять UI при CONNECT_FAIL
      debugPrint(
          'Connection Event >> success=${connectResult.success} msg=${connectResult.message}');
      // Avoid showing snackbar if context is not available (e.g., during dispose)
      if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
            content: Text(
                'Connection Event: ${connectResult.message ?? (connectResult.success ? 'Success' : 'Failed')}'),
            duration: const Duration(seconds: 2),
          ),
        );
      }
    });
  }

  @override
  void dispose() {
    _searchSubscription?.cancel();
    _connectionEventsSubscription?.cancel();
    _posPrintersManager.dispose(); // Используем метод dispose менеджера
    super.dispose();
  }

  /// Запуск поиска принтеров
  Future<void> _findPrinters() async {
    if (_isSearching) return; // Предотвращаем повторный запуск

    setState(() {
      _isSearching = true;
      _foundPrinters.clear();
      _connectedPrinters.clear(); // Очищаем и подключенные при новом поиске
    });

    _searchSubscription?.cancel(); // Отменяем предыдущую подписку, если была

    try {
      final stream = _posPrintersManager.findPrinters();
      _searchSubscription = stream.listen(
        (printerParams) {
          // Проверяем, нет ли уже такого принтера в списке найденных
          final exists =
              _foundPrinters.any((p) => _samePrinter(p.params, printerParams));
          if (!exists && mounted) {
            // Check mounted before setState
            setState(() {
              _foundPrinters.add(PrinterItem(params: printerParams));
            });
          }
        },
        onDone: () {
          if (mounted) {
            // Проверяем, что виджет все еще в дереве
            setState(() => _isSearching = false);
            _scaffoldMessengerKey.currentState?.showSnackBar(
              const SnackBar(
                  content: Text('Search finished.'),
                  duration: Duration(seconds: 1)),
            );
          }
          _searchSubscription = null;
        },
        onError: (err) {
          debugPrint('Ошибка при поиске: $err');
          if (mounted) {
            setState(() => _isSearching = false);
            _scaffoldMessengerKey.currentState?.showSnackBar(
              SnackBar(
                  content: Text('Search error: $err'),
                  backgroundColor: Colors.red),
            );
          }
          _searchSubscription = null;
        },
        cancelOnError: true, // Отменяем подписку при ошибке
      );
    } catch (e) {
      debugPrint('Error starting search: $e');
      if (mounted) {
        setState(() => _isSearching = false);
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
              content: Text('Error starting search: $e'),
              backgroundColor: Colors.red),
        );
      }
    }
  }

  /// Подключаемся к принтеру
  Future<void> _connectToPrinter(PrinterItem item) async {
    // Опционально: показать индикатор загрузки
    try {
      final result = await _posPrintersManager.connectPrinter(item.params);
      if (result.success && mounted) {
        final alreadyIn =
            _connectedPrinters.any((p) => _samePrinter(p.params, item.params));
        if (!alreadyIn) {
          setState(() {
            // Убираем из найденных и добавляем в подключенные
            _foundPrinters
                .removeWhere((p) => _samePrinter(p.params, item.params));
            _connectedPrinters.add(item);
          });
          _scaffoldMessengerKey.currentState?.showSnackBar(
            const SnackBar(
                content: Text('Connected successfully!'),
                backgroundColor: Colors.green),
          );
        }
      } else if (mounted) {
        debugPrint('Connect failed: ${result.message}');
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
              content:
                  Text('Connect failed: ${result.message ?? 'Unknown reason'}'),
              backgroundColor: Colors.orange),
        );
      }
    } catch (e) {
      debugPrint('Connect error: $e');
      if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
              content: Text('Connect error: $e'), backgroundColor: Colors.red),
        );
      }
    } finally {
      // Опционально: скрыть индикатор загрузки
    }
  }

  /// Отключаемся от принтера
  Future<void> _disconnectPrinter(PrinterItem item) async {
    try {
      final result = await _posPrintersManager.disconnectPrinter(item.params);
      if (result.success && mounted) {
        setState(() {
          _connectedPrinters
              .removeWhere((p) => _samePrinter(p.params, item.params));
          // Опционально: добавить обратно в список найденных, если нужно
          // _foundPrinters.add(item);
        });
        _scaffoldMessengerKey.currentState?.showSnackBar(
          const SnackBar(
              content: Text('Disconnected.'), duration: Duration(seconds: 1)),
        );
      } else if (mounted) {
        // Show error message from result if disconnect failed
         _scaffoldMessengerKey.currentState?.showSnackBar(
           SnackBar(
               content: Text('Disconnect failed: ${result.errorMessage ?? "Unknown reason"}'),
               backgroundColor: Colors.orange),
         );
      }
    } catch (e) { // Catch potential exceptions from the manager call itself if needed
      debugPrint('Disconnect error: $e');
      if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
              content: Text('Disconnect error: $e'), // Error from exception
              backgroundColor: Colors.red),
        );
      }
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
    try {
      final result = await _posPrintersManager.getPrinterStatus(item.params);
      debugPrint(
          'Status for ${item.params.usbPath ?? item.params.ipAddress} => success=${result.success}, status=${result.status}, error=${result.errorMessage}');
      if (mounted) {
         if (result.success) {
           _scaffoldMessengerKey.currentState?.showSnackBar(
             SnackBar(content: Text('Status: ${result.status ?? "N/A"}')),
           );
         } else {
            _scaffoldMessengerKey.currentState?.showSnackBar(
             SnackBar(
                 content: Text('Get status failed: ${result.errorMessage ?? "Unknown reason"}'),
                 backgroundColor: Colors.orange),
           );
         }
      }
    } catch (e) {
      // Catch potential exceptions from the manager call itself if needed
      debugPrint('Get status error: $e');
      if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
              content: Text('Get status error: $e'), // Error from exception
              backgroundColor: Colors.red),
        );
      }
    }
  }

  /// Пример печати HTML для чекового (ESC/POS)
  Future<void> _printEscHtml(PrinterItem item) async {
    // Убедимся, что это не лейбл-принтер
    if (item.isLabelPrinter) {
      debugPrint('Skipping ESC/POS HTML print for label printer.');
      return;
    }
    try {
      final result = await _posPrintersManager.printReceiptHTML(
        item.params,
        "<h1>ESC/POS Html</h1><p>Some text</p>",
        576, // 80mm width in dots (for 203 dpi)
      );
      debugPrint('printReceiptHTML success=${result.success}, error=${result.errorMessage}');
      if (mounted && result.success) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          const SnackBar(
              content: Text('ESC/POS HTML sent.'),
              duration: Duration(seconds: 1)),
        );
      } else if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
           SnackBar(
               content: Text('Failed to send ESC/POS HTML: ${result.errorMessage ?? "Unknown reason"}'),
               backgroundColor: Colors.orange),
        );
      }
    } catch (e) {
      debugPrint('printReceiptHTML error: $e');
      if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
              content: Text('printReceiptHTML error: $e'), // Error from exception
              backgroundColor: Colors.red),
        );
      }
    }
  }

  /// Печать ESC/POS сырых команд
  Future<void> _printEscPosData(PrinterItem item) async {
    if (item.isLabelPrinter) {
      debugPrint('Skipping ESC/POS raw print for label printer.');
      return;
    }
    try {
      List<int> bytes = [];
      // ESC @ - Initialize printer
      bytes.addAll([0x1B, 0x40]);
      // ESC a 1 => center alignment
      bytes.addAll([0x1B, 0x61, 0x01]);
      // Text
      bytes.addAll("Hello ESC/POS\n".codeUnits);
      // LF (line feed)
      bytes.add(0x0A);
      // GS V m n => cut paper (partial cut)
      bytes.addAll([
        0x1D,
        0x56,
        0x41,
        0x10
      ]); // Or use 0x1D 0x56 0x01 for full cut on some models

      final result = await _posPrintersManager.printReceiptData(item.params,
          Uint8List.fromList(bytes), 576); // width might be ignored
      debugPrint('printReceiptData success=${result.success}, error=${result.errorMessage}');
      if (mounted && result.success) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          const SnackBar(
              content: Text('ESC/POS Raw data sent.'),
              duration: Duration(seconds: 1)),
        );
      } else if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
           SnackBar(
               content: Text('Failed to send ESC/POS Raw data: ${result.errorMessage ?? "Unknown reason"}'),
               backgroundColor: Colors.orange),
        );
      }
    } catch (e) {
      debugPrint('printReceiptData error: $e');
      if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
              content: Text('printReceiptData error: $e'), // Error from exception
              backgroundColor: Colors.red),
        );
      }
    }
  }

  /// Печать лейбла сырыми командами (CPCL/TSPL/ZPL)
  Future<void> _printLabelRaw(PrinterItem item) async {
    if (!item.isLabelPrinter || item.language == null) {
      debugPrint(
          'Skipping raw label print: Not a label printer or language not set.');
      if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          const SnackBar(
              content: Text('Select label language first!'),
              backgroundColor: Colors.orange),
        );
      }
      return;
    }
    // Пример: CPCL
    // Обычно для cpcl: "! 0 200 200 400 1 ... PRINT"
    // Для tspl: "SIZE 60 mm, 40 mm ... PRINT 1"
    // Для zpl: "^XA ^FO50,50 ^A0N,30,30 ^FD Hello^FS ^XZ"
    String commands;
    switch (item.language!) {
      // Use ! because we checked for null above
      case LabelPrinterLanguage.cpcl:
        // Example for a 58x40mm label (approx 464x320 dots at 203dpi)
        commands =
            "! 0 200 200 320 1\r\n" // Set label height to 320 dots, quantity 1
            "TEXT 4 0 50 50 Hello CPCL\r\n" // Font 4 (default), size 0, at (50,50)
            "PRINT\r\n"; // Print command
        break;
      case LabelPrinterLanguage.tspl:
        // Example for a 58x40mm label
        commands = "SIZE 58 mm, 40 mm\r\n"
            "GAP 2 mm, 0 mm\r\n" // Adjust gap as needed
            "CLS\r\n" // Clear buffer
            "TEXT 50,50,\"ROMAN.TTF\",0,12,12,\"Hello TSPL\"\r\n" // Use a built-in font, size 12x12
            "PRINT 1,1\r\n"; // Print 1 label, 1 copy
        break;
      case LabelPrinterLanguage.zpl:
        // Example for a 58x40mm label (approx 464x320 dots at 203dpi)
        commands = "^XA\r\n" // Start format
            "^PW464\r\n" // Print width
            "^LL320\r\n" // Label length
            "^FO50,50^A0N,30,30^FDHello ZPL^FS\r\n" // Font 0, Normal, 30x30 size, at (50,50)
            "^XZ\r\n"; // End format
        break;
      // No default needed as enum covers all cases
    }

    try {
      final data = Uint8List.fromList(
          commands.codeUnits); // Use default encoding (UTF-8)
      final result = await _posPrintersManager.printLabelData(
        item.params,
        item.language!, // Use !
        data,
        576, // width might be ignored for raw commands
      );
      debugPrint('printLabelData success=${result.success}, error=${result.errorMessage}');
      if (mounted && result.success) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
              content: Text('${item.language!.name} Raw data sent.'),
              duration: const Duration(seconds: 1)),
        );
      } else if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
           SnackBar(
               content: Text('Failed to send ${item.language!.name} Raw data: ${result.errorMessage ?? "Unknown reason"}'),
               backgroundColor: Colors.orange),
        );
      }
    } catch (e) {
      debugPrint('printLabelData error: $e');
      if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
              content: Text('printLabelData error: $e'), // Error from exception
              backgroundColor: Colors.red),
        );
      }
    }
  }

  /// Печать HTML на лейбл-принтер
  Future<void> _printLabelHtml(PrinterItem item) async {
    if (!item.isLabelPrinter || item.language == null) {
      debugPrint(
          'Skipping HTML label print: Not a label printer or language not set.');
      if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          const SnackBar(
              content: Text('Select label language first!'),
              backgroundColor: Colors.orange),
        );
      }
      return;
    }
    // Пример HTML для ценника
    final html = generatePriceTagHtml(
      itemName: 'Awesome Gadget',
      price: '99.99',
      barcodeData: '123456789012', // Пример EAN-13
      unit: 'pcs',
    );
    try {
      // Размеры в точках для 58x40 мм при 203 dpi (8 точек/мм)
      const int widthDots = 464; // 58 * 8
      const int heightDots = 320; // 40 * 8

      final result = await _posPrintersManager.printLabelHTML(
        item.params,
        item.language!, // Use !
        html,
        widthDots,
        heightDots,
      );
      debugPrint('printLabelHTML success=${result.success}, error=${result.errorMessage}');
      if (mounted && result.success) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
              content: Text('${item.language!.name} HTML sent.'),
              duration: const Duration(seconds: 1)),
        );
      } else if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
           SnackBar(
               content: Text('Failed to send ${item.language!.name} HTML: ${result.errorMessage ?? "Unknown reason"}'),
               backgroundColor: Colors.orange),
        );
      }
    } catch (e) {
      debugPrint('printLabelHTML error: $e');
      if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
              content: Text('printLabelHTML error: $e'), // Error from exception
              backgroundColor: Colors.red),
        );
      }
    }
  }

  /// Пример смены настроек лейбл (размер, скорость, плотность)
  Future<void> _setupLabelParams(PrinterItem item) async {
    if (!item.isLabelPrinter || item.language == null) {
      debugPrint(
          'Skipping label setup: Not a label printer or language not set.');
      if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          const SnackBar(
              content: Text('Select label language first!'),
              backgroundColor: Colors.orange),
        );
      }
      return;
    }
    try {
      // Примерные значения, могут отличаться для разных принтеров/языков
      const int labelWidthMm = 58;
      const int labelHeightMm = 40;
      const int density = 15; // Usually 0-15
      const int speed = 4; // Usually 1-5 or similar range

      final result = await _posPrintersManager.setupLabelParams(
        item.params,
        item.language!, // Use !
        labelWidthMm, // Передаем в мм или точках в зависимости от того, что ожидает SDK/принтер
        labelHeightMm, // Уточнить в документации SDK или экспериментально
        density,
        speed,
      );
      debugPrint('setupLabelParams success=${result.success}, error=${result.errorMessage}');
      if (mounted && result.success) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
              content: Text('${item.language!.name} params set.'),
              duration: const Duration(seconds: 1)),
        );
      } else if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
           SnackBar(
               content: Text('Failed to set ${item.language!.name} params: ${result.errorMessage ?? "Unknown reason"}'),
               backgroundColor: Colors.orange),
        );
      }
    } catch (e) {
      debugPrint('setupLabelParams error: $e');
      if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
              content: Text('setupLabelParams error: $e'), // Error from exception
              backgroundColor: Colors.red),
        );
      }
    }
  }

  /// Enables DHCP for a network printer
  Future<void> _enableDhcp(PrinterItem item) async {
    // Ensure it's a network printer
    if (item.params.connectionType != PosPrinterConnectionType.network) {
      debugPrint('DHCP setting only applicable for network printers.');
       if (mounted) {
         _scaffoldMessengerKey.currentState?.showSnackBar(
           const SnackBar(
               content: Text('DHCP setting only for network printers.'),
               backgroundColor: Colors.orange),
         );
       }
      return;
    }

    // Show loading indicator? (Optional)
    _scaffoldMessengerKey.currentState?.showSnackBar(
      const SnackBar(content: Text('Attempting to enable DHCP...')),
    );

    try {
      // Construct NetSettingsDTO - use placeholders for IP/Mask/Gateway when enabling DHCP
      final netSettings = NetSettingsDTO(
        ipAddress: '', // Placeholder, likely ignored by native code when dhcp is true
        mask: '',      // Placeholder
        gateway: '',   // Placeholder
        dhcp: true,           // Enable DHCP
      );

      final result = await _posPrintersManager.setNetSettings(item.params, netSettings);
      debugPrint('setNetSettings (DHCP) success=${result.success}, error=${result.errorMessage}');

      if (mounted) {
        if (result.success) {
          _scaffoldMessengerKey.currentState?.showSnackBar(
            const SnackBar(
                content: Text('DHCP enabled successfully! Printer may restart or require re-scan.'),
                backgroundColor: Colors.green),
          );
          // Optional: Consider automatically disconnecting or prompting user to re-scan
        } else {
          _scaffoldMessengerKey.currentState?.showSnackBar(
            SnackBar(
                content: Text('Failed to enable DHCP: ${result.errorMessage ?? "Unknown reason"}'),
                backgroundColor: Colors.red),
          );
        }
      }
    } catch (e) {
      debugPrint('Error enabling DHCP: $e');
      if (mounted) {
        _scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
              content: Text('Error enabling DHCP: $e'),
              backgroundColor: Colors.red),
        );
      }
    } finally {
      // Hide loading indicator? (Optional)
    }
  }


  /// Строит виджет для выбора языка принтера
  Widget _buildLanguageSelector(PrinterItem item, bool isConnectedList) {
    return PopupMenuButton<LabelPrinterLanguage?>(
      // Allow null for ESC/POS representation
      tooltip: "Select Printer Type/Language",
      initialValue: item.language, // Показываем текущий выбор
      onSelected: (LabelPrinterLanguage? lang) {
        setState(() {
          item.language = lang;
        });
        // Если принтер уже подключен, можно сразу отправить команду setup
        if (isConnectedList && item.isLabelPrinter) {
          _setupLabelParams(item);
        }
      },
      itemBuilder: (ctx) {
        // Опция для ESC/POS (представлена как null)
        const escPosItem = PopupMenuItem<LabelPrinterLanguage?>(
          value: null, // Используем null для представления ESC/POS
          child: Text('ESC/POS (Receipt)'),
        );
        // Опции для языков лейблов
        final labelItems = LabelPrinterLanguage.values
            .map((e) => PopupMenuItem<LabelPrinterLanguage?>(
                  value: e,
                  child: Text(e.name), // CPCL, TSPL, ZPL
                ))
            .toList();
        return [escPosItem, ...labelItems];
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8.0),
        child: Row(
          // Используем Row для текста и иконки
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              item.isLabelPrinter ? item.language?.name ?? 'Label' : 'ESC/POS',
              style: TextStyle(color: Theme.of(context).primaryColor),
            ),
            const Icon(Icons.arrow_drop_down, size: 20),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      theme: ThemeData(
        primarySwatch: Colors.blue,
        visualDensity: VisualDensity.adaptivePlatformDensity,
      ),
      scaffoldMessengerKey: _scaffoldMessengerKey,
      home: Scaffold(
        appBar: AppBar(
          title: const Text('POS Printers Example'),
          actions: [
            // Кнопка для пакетной печати на всех подключенных
            if (_connectedPrinters.isNotEmpty)
              IconButton(
                icon: const Icon(Icons.print_outlined),
                tooltip: 'Print test on all connected',
                onPressed: () async {
                  for (final p in _connectedPrinters) {
                    if (p.isLabelPrinter) {
                      // Печать для лейбл-принтера (если язык выбран)
                      if (p.language != null) {
                        await _setupLabelParams(
                            p); // Установить параметры перед печатью
                        await _printLabelHtml(p);
                        await Future.delayed(
                            const Duration(milliseconds: 500)); // Пауза
                        await _printLabelRaw(p);
                      } else {
                        debugPrint(
                            'Skipping label print for ${p.params.usbPath ?? p.params.ipAddress}: language not set');
                        if (mounted) {
                          _scaffoldMessengerKey.currentState?.showSnackBar(
                            SnackBar(
                                content: Text(
                                    'Select language for ${p.params.usbPath ?? p.params.ipAddress}'),
                                backgroundColor: Colors.orange),
                          );
                        }
                      }
                    } else {
                      // Печать для ESC/POS принтера
                      await _printEscHtml(p);
                      await Future.delayed(
                          const Duration(milliseconds: 500)); // Пауза
                      await _printEscPosData(p);
                    }
                  }
                  if (mounted) {
                    _scaffoldMessengerKey.currentState?.showSnackBar(
                      const SnackBar(
                          content: Text('Print jobs sent.'),
                          duration: Duration(seconds: 1)),
                    );
                  }
                },
              ),
          ],
        ),
        floatingActionButton: FloatingActionButton(
          onPressed: _isSearching ? null : _findPrinters,
          tooltip: 'Find Printers',
          child: _isSearching
              ? const SizedBox(
                  width: 24,
                  height: 24,
                  child: CircularProgressIndicator(
                      color: Colors.white, strokeWidth: 3))
              : const Icon(Icons.search),
        ),
        body: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // --- Секция "Подключённые принтеры" ---
            if (_connectedPrinters.isNotEmpty)
              Padding(
                padding: const EdgeInsets.all(8.0),
                child: Text('Connected Printers (${_connectedPrinters.length})',
                    style: Theme.of(context).textTheme.titleMedium),
              ),
            if (_connectedPrinters.isNotEmpty)
              ListView.builder(
                shrinkWrap: true, // Важно для ListView внутри Column
                physics:
                    const NeverScrollableScrollPhysics(), // Отключаем скролл этого списка
                itemCount: _connectedPrinters.length,
                itemBuilder: (context, index) {
                  final item = _connectedPrinters[index];
                  String title;
                  String usbDetails = '';
                  if (item.params.connectionType ==
                      PosPrinterConnectionType.usb) {
                    title =
                        'USB: ${item.params.productName ?? item.params.usbPath}'; // Show product name or path
                    usbDetails =
                        'VID:${item.params.vendorId?.toRadixString(16) ?? 'N/A'} '
                        'PID:${item.params.productId?.toRadixString(16) ?? 'N/A'} '
                        'SN:${item.params.usbSerialNumber ?? 'N/A'}';
                  } else {
                    title = 'NET: ${item.params.ipAddress}';
                  }
                  return Card(
                    margin: const EdgeInsets.symmetric(
                        horizontal: 8.0, vertical: 4.0),
                    child: ListTile(
                      title: Text(title),
                      // Combine language selector with USB details if available
                      subtitle: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          _buildLanguageSelector(item, true),
                          if (item.params.connectionType == PosPrinterConnectionType.usb) ...[
                             if (item.params.usbPath != null)
                               Padding(
                                 padding: const EdgeInsets.only(top: 4.0),
                                 child: Text('Path: ${item.params.usbPath}', style: Theme.of(context).textTheme.bodySmall),
                               ),
                             if (usbDetails.isNotEmpty)
                               Padding(
                                 padding: const EdgeInsets.only(top: 2.0), // Adjust spacing
                                 child: Text(usbDetails, style: Theme.of(context).textTheme.bodySmall),
                               ),
                          ]
                        ],
                      ),
                      leading: Icon(
                        item.params.connectionType ==
                                PosPrinterConnectionType.usb
                            ? Icons.usb
                            : Icons.wifi,
                        color: Colors.green,
                      ),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          // Add DHCP button only for network printers
                          if (item.params.connectionType != PosPrinterConnectionType.network)
                            IconButton(
                              icon: const Icon(Icons.settings_ethernet), // Or Icons.network_check
                              tooltip: 'Enable DHCP',
                              onPressed: () => _enableDhcp(item),
                            ),
                          IconButton(
                            icon: const Icon(Icons.info_outline),
                            tooltip: 'Get Status',
                            onPressed: () => _getStatus(item),
                          ),
                          IconButton(
                            icon: const Icon(Icons.link_off),
                            tooltip: 'Disconnect',
                            onPressed: () => _disconnectPrinter(item),
                            color: Colors.redAccent,
                          ),
                        ],
                      ),
                    ),
                  );
                },
              ),
            if (_connectedPrinters.isNotEmpty)
              const Divider(height: 20, thickness: 1),

            // --- Секция "Найденные принтеры" ---
            Padding(
              padding: const EdgeInsets.all(8.0),
              child: Text(
                  _isSearching
                      ? 'Searching...'
                      : 'Found Printers (${_foundPrinters.length})',
                  style: Theme.of(context).textTheme.titleMedium),
            ),
            Expanded(
              // Этот ListView занимает оставшееся место
              child: _foundPrinters.isEmpty && !_isSearching
                  ? const Center(
                      child: Text('No printers found. Tap search button.'))
                  : ListView.builder(
                      itemCount: _foundPrinters.length,
                      itemBuilder: (context, index) {
                        final item = _foundPrinters[index];
                        String title;
                        String usbDetails = '';
                        if (item.params.connectionType ==
                            PosPrinterConnectionType.usb) {
                          title =
                              'USB: ${item.params.productName ?? item.params.usbPath}'; // Show product name or path
                          usbDetails =
                              'VID:${item.params.vendorId?.toRadixString(16) ?? 'N/A'} '
                              'PID:${item.params.productId?.toRadixString(16) ?? 'N/A'} '
                              'MFR:${item.params.manufacturer ?? 'N/A'} '
                              'SN:${item.params.usbSerialNumber ?? 'N/A'}';
                        } else {
                          title =
                              'NET: ${item.params.ipAddress} / MAC: ${item.params.macAddress ?? 'N/A'}';
                        }

                        return Card(
                          margin: const EdgeInsets.symmetric(
                              horizontal: 8.0, vertical: 4.0),
                          child: ListTile(
                            title: Text(title),
                            subtitle: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                _buildLanguageSelector(item, false), // Виджет выбора языка
                                if (item.params.connectionType == PosPrinterConnectionType.usb) ...[
                                  if (item.params.usbPath != null)
                                    Padding(
                                      padding: const EdgeInsets.only(top: 4.0),
                                      child: Text('Path: ${item.params.usbPath}', style: Theme.of(context).textTheme.bodySmall),
                                    ),
                                  if (usbDetails.isNotEmpty)
                                    Padding(
                                      padding: const EdgeInsets.only(top: 2.0), // Adjust spacing
                                      child: Text(usbDetails, style: Theme.of(context).textTheme.bodySmall),
                                    ),
                                ]
                              ],
                            ),
                            leading: Icon(
                              item.params.connectionType ==
                                      PosPrinterConnectionType.usb
                                  ? Icons.usb
                                  : Icons.wifi,
                              color: Colors.grey,
                            ),
                            trailing: ElevatedButton(
                              child: const Text('Connect'),
                              onPressed: () => _connectToPrinter(item),
                            ),
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

/// Генерирует простой HTML для ценника (пример)
String generatePriceTagHtml({
  required String itemName,
  required String price,
  required String unit,
  required String
      barcodeData, // Данные для штрихкода (пока не используется для генерации картинки)
  String storeName = 'My Store',
}) {
  // Примечание: Генерация штрихкода в HTML/CSS сложна.
  // Обычно используют JS-библиотеки или серверную генерацию.
  // Здесь мы просто оставим место для него.
  // Для реального использования лучше передавать картинку штрихкода (base64) или использовать
  // возможности принтера по генерации штрихкодов (через raw команды).

  return '''<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0"> <!-- Важно для масштабирования -->
  <style>
    body, html {
      margin: 0;
      padding: 0;
      width: 464px; /* Ширина в точках для 58мм @ 203dpi */
      height: 320px; /* Высота в точках для 40мм @ 203dpi */
      box-sizing: border-box;
      font-family: Arial, sans-serif; /* Используйте шрифты, поддерживаемые рендерером */
      background-color: white; /* Фон этикетки */
      color: black;
      display: flex;
      justify-content: center;
      align-items: center;
    }
    .price-tag {
      width: 96%; /* Небольшой отступ от краев */
      height: 96%;
      border: 1px solid black; /* Рамка для наглядности */
      padding: 10px;
      box-sizing: border-box;
      display: flex;
      flex-direction: column;
      justify-content: space-around; /* Распределение пространства */
      align-items: center;
      text-align: center;
    }
    .store-name, .item-name, .price, .unit, .barcode-area {
      margin: 2px 0; /* Небольшие вертикальные отступы */
      width: 100%;
    }
    .store-name {
      font-size: 20px;
      font-weight: bold;
    }
    .item-name {
      font-size: 30px;
      font-weight: bold;
      word-wrap: break-word; /* Перенос длинных слов */
    }
    .price {
      font-size: 48px;
      font-weight: bold;
    }
    .unit {
      font-size: 18px;
    }
    .barcode-area {
       font-size: 16px;
       min-height: 50px; /* Место для штрихкода */
       border: 1px dashed grey; /* Показать область штрихкода */
       display: flex;
       align-items: center;
       justify-content: center;
    }
  </style>
</head>
<body>
  <div class="price-tag">
    <p class="store-name">$storeName</p>
    <p class="item-name">$itemName</p>
    <p class="price">\$$price</p>
    <p class="unit">Price per $unit</p>
    <div class="barcode-area">
       Barcode: $barcodeData <br/> (Image would go here)
    </div>
  </div>
</body>
</html>
''';
}
