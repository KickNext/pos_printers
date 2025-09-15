import 'package:flutter/material.dart';
import 'package:pos_printers/pos_printers.dart';
import 'dart:math';
import 'dart:async';
import 'dart:typed_data';

void main() {
  runApp(const PrinterTestApp());
}

class PrinterTestApp extends StatelessWidget {
  const PrinterTestApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'POS Printer Stress Test',
      theme: ThemeData(
        primarySwatch: Colors.blue,
        useMaterial3: true,
      ),
      home: const PrinterTestScreen(),
    );
  }
}

class PrinterTestScreen extends StatefulWidget {
  const PrinterTestScreen({super.key});

  @override
  State<PrinterTestScreen> createState() => _PrinterTestScreenState();
}

class _PrinterTestScreenState extends State<PrinterTestScreen> {
  final PosPrintersManager _printersManager = PosPrintersManager();
  final List<PrinterConnectionParamsDTO> _foundPrinters = [];
  PrinterConnectionParamsDTO? _selectedPrinter;
  bool _isDiscovering = false;
  bool _isStressTesting = false;
  String _statusText = 'Ready to work';
  String _stressTestStatus = '';

  // Generate random client number from 10 to 99 on startup
  late final int _clientNumber;
  int _testCounter = 0;

  @override
  void initState() {
    super.initState();
    _clientNumber = Random().nextInt(90) + 10; // from 10 to 99
    _setupPrinterManager();
  }

  void _setupPrinterManager() {
    // Listen for found printers
    _printersManager.discoveryStream.listen((printer) {
      setState(() {
        if (!_foundPrinters.any((p) => p.id == printer.id)) {
          _foundPrinters.add(printer);
        }
      });
    });

    // Listen for connection events
    _printersManager.connectionEvents.listen((event) {
      setState(() {
        switch (event.type) {
          case PrinterConnectionEventType.attached:
            _statusText = 'Printer attached: ${event.printer?.id}';
            break;
          case PrinterConnectionEventType.detached:
            _statusText = 'Printer detached: ${event.printer?.id}';
            break;
        }
      });
    });
  }

  // Stress testing methods
  Future<void> _runStressTest() async {
    if (_selectedPrinter == null) {
      setState(() {
        _statusText = 'Select a printer for stress test';
      });
      return;
    }

    setState(() {
      _isStressTesting = true;
      _testCounter = 0;
      _stressTestStatus = 'Client $_clientNumber: Starting stress test...';
    });

    try {
      // Create all print tasks and run them simultaneously
      final futures = <Future<void>>[];

      // Add 5 HTML receipts
      for (int i = 1; i <= 5; i++) {
        futures.add(_printHtmlReceipt(i));
      }

      // Add 5 RAW receipts
      for (int i = 1; i <= 5; i++) {
        futures.add(_printRawReceipt(i));
      }

      setState(() {
        _stressTestStatus =
            'Client $_clientNumber: Sending all 10 receipts simultaneously...';
      });

      // Execute all print tasks simultaneously
      await Future.wait(futures);

      setState(() {
        _testCounter = 10;
        _stressTestStatus =
            'Client $_clientNumber: Stress test completed! (10/10)';
        _statusText =
            'Stress test for client $_clientNumber completed successfully!';
      });
    } catch (e) {
      setState(() {
        _stressTestStatus = 'Client $_clientNumber: Error - $e';
        _statusText = 'Stress test for client $_clientNumber failed: $e';
      });
    } finally {
      setState(() {
        _isStressTesting = false;
      });
    }
  }

  Future<void> _printHtmlReceipt(int receiptNumber) async {
    final htmlContent = '''
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <style>
            body { font-family: monospace; font-size: 12px; margin: 0; padding: 10px; }
            .header { text-align: center; font-weight: bold; margin-bottom: 10px; }
            .info { margin: 5px 0; }
            .separator { text-align: center; margin: 10px 0; }
        </style>
    </head>
    <body>
        <div class="header">STRESS TEST HTML RECEIPT</div>
        <div class="separator">=========================================</div>
        <div class="info">Client Number: $_clientNumber</div>
        <div class="info">HTML Receipt: $receiptNumber of 5</div>
        <div class="info">Time: ${DateTime.now().toString().substring(0, 19)}</div>
        <div class="separator">=========================================</div>
        <div class="info">Item: Test Product $receiptNumber</div>
        <div class="info">Quantity: 1 pcs</div>
        <div class="info">Price: \$${(receiptNumber * 10)}.00</div>
        <div class="separator">=========================================</div>
        <div class="info">TOTAL: \$${(receiptNumber * 10)}.00</div>
        <div class="separator">=========================================</div>
        <br><br><br>
    </body>
    </html>
    ''';

    await _printersManager.printEscHTML(
      _selectedPrinter!,
      htmlContent,
      576, // 80mm paper width
    );
  }

  Future<void> _printRawReceipt(int receiptNumber) async {
    // Create simple RAW ESC/POS receipt
    final rawCommands = <int>[
      // Initialize printer
      0x1B, 0x40,

      // Center text
      0x1B, 0x61, 0x01,

      // Header
      ...('STRESS TEST RAW RECEIPT\n').codeUnits,
      ...('=========================================\n').codeUnits,

      // Left align
      0x1B, 0x61, 0x00,

      // Receipt information
      ...('Client Number: $_clientNumber\n').codeUnits,
      ...('RAW Receipt: $receiptNumber of 5\n').codeUnits,
      ...('Time: ${DateTime.now().toString().substring(0, 19)}\n').codeUnits,
      ...('=========================================\n').codeUnits,
      ...('Item: Test Product $receiptNumber\n').codeUnits,
      ...('Quantity: 1 pcs\n').codeUnits,
      ...('Price: \$${(receiptNumber * 10)}.00\n').codeUnits,
      ...('=========================================\n').codeUnits,
      ...('TOTAL: \$${(receiptNumber * 10)}.00\n').codeUnits,
      ...('=========================================\n').codeUnits,

      // Several line feeds
      0x0A, 0x0A, 0x0A,

      // Paper cut
      0x1D, 0x56, 0x41, 0x10,
    ];

    await _printersManager.printEscRawData(
      _selectedPrinter!,
      Uint8List.fromList(rawCommands),
      576, // 80mm paper width
    );
  }

  @override
  void dispose() {
    _printersManager.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('POS Printer Stress Test'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status - more compact
            Container(
              padding: const EdgeInsets.all(12.0),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.primaryContainer,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                _statusText,
                style: Theme.of(context).textTheme.titleSmall,
                textAlign: TextAlign.center,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            const SizedBox(height: 12),

            // Printer discovery buttons
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: _isDiscovering ? null : _discoverUsbPrinters,
                    icon: const Icon(Icons.usb),
                    label: const Text('USB Printers'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: _isDiscovering ? null : _discoverNetworkPrinters,
                    icon: const Icon(Icons.wifi),
                    label: const Text('Network Printers'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),

            // Found printers list
            Text('Found Printers:',
                style: Theme.of(context)
                    .textTheme
                    .titleSmall
                    ?.copyWith(fontWeight: FontWeight.bold)),
            const SizedBox(height: 6),
            Expanded(
              child: _foundPrinters.isEmpty
                  ? Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        border: Border.all(color: Colors.grey.shade300),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: const Center(
                        child: Text(
                          'No printers found\nPress discovery buttons above',
                          textAlign: TextAlign.center,
                          style: TextStyle(color: Colors.grey),
                        ),
                      ),
                    )
                  : ListView.builder(
                      itemCount: _foundPrinters.length,
                      itemBuilder: (context, index) {
                        final printer = _foundPrinters[index];
                        final isSelected = _selectedPrinter?.id == printer.id;
                        return Card(
                          margin: const EdgeInsets.only(bottom: 4),
                          color: isSelected
                              ? Theme.of(context).colorScheme.primaryContainer
                              : null,
                          child: ListTile(
                            dense: true,
                            leading: Icon(
                              printer.connectionType ==
                                      PosPrinterConnectionType.usb
                                  ? Icons.usb
                                  : Icons.wifi,
                              size: 20,
                            ),
                            title: Text(
                              _getPrinterTitle(printer),
                              style: const TextStyle(fontSize: 14),
                            ),
                            subtitle: Text(
                              _getPrinterSubtitle(printer),
                              style: const TextStyle(fontSize: 12),
                            ),
                            trailing: isSelected
                                ? const Icon(Icons.check_circle,
                                    color: Colors.green, size: 18)
                                : null,
                            onTap: () => _selectPrinter(printer),
                          ),
                        );
                      },
                    ),
            ),
            const SizedBox(height: 12),

            // Test print buttons
            if (_selectedPrinter != null) ...[
              Text('Test Printing:',
                  style: Theme.of(context)
                      .textTheme
                      .titleSmall
                      ?.copyWith(fontWeight: FontWeight.bold)),
              const SizedBox(height: 6),
              Row(
                children: [
                  Expanded(
                    child: ElevatedButton.icon(
                      onPressed: _printTestReceipt,
                      icon: const Icon(Icons.receipt, size: 18),
                      label:
                          const Text('Receipt', style: TextStyle(fontSize: 12)),
                      style: ElevatedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 8),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: ElevatedButton.icon(
                      onPressed: _printTestLabel,
                      icon: const Icon(Icons.local_offer, size: 18),
                      label:
                          const Text('Label', style: TextStyle(fontSize: 12)),
                      style: ElevatedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 8),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: ElevatedButton.icon(
                      onPressed: _openCashDrawer,
                      icon: const Icon(Icons.meeting_room_outlined, size: 18),
                      label:
                          const Text('Drawer', style: TextStyle(fontSize: 12)),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.brown.shade400,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(vertical: 8),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Row(
                children: [
                  Expanded(
                    child: ElevatedButton.icon(
                      onPressed: _checkPrinterStatus,
                      icon: const Icon(Icons.info, size: 18),
                      label:
                          const Text('Status', style: TextStyle(fontSize: 12)),
                      style: ElevatedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 8),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: ElevatedButton.icon(
                      onPressed: _detectPrinterLanguage,
                      icon: const Icon(Icons.translate, size: 18),
                      label: const Text('Language',
                          style: TextStyle(fontSize: 12)),
                      style: ElevatedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 8),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),

              // Client info and stress test
              Container(
                padding: const EdgeInsets.all(12.0),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.secondaryContainer,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Column(
                  children: [
                    Text(
                      'CLIENT #$_clientNumber',
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                            color: Theme.of(context)
                                .colorScheme
                                .onSecondaryContainer,
                          ),
                    ),
                    const SizedBox(height: 8),
                    if (_stressTestStatus.isNotEmpty) ...[
                      Text(
                        _stressTestStatus,
                        style: Theme.of(context).textTheme.bodySmall,
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 8),
                    ],
                    SizedBox(
                      width: double.infinity,
                      child: ElevatedButton.icon(
                        onPressed: _isStressTesting ? null : _runStressTest,
                        icon: _isStressTesting
                            ? const SizedBox(
                                width: 16,
                                height: 16,
                                child:
                                    CircularProgressIndicator(strokeWidth: 2),
                              )
                            : const Icon(Icons.speed),
                        label: Text(_isStressTesting
                            ? 'Running stress test...'
                            : 'STRESS TEST (5 HTML + 5 RAW) 80mm'),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.orange,
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 12),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _getPrinterTitle(PrinterConnectionParamsDTO printer) {
    if (printer.connectionType == PosPrinterConnectionType.usb) {
      return printer.usbParams?.productName ?? 'USB Printer';
    } else {
      return 'Network Printer';
    }
  }

  String _getPrinterSubtitle(PrinterConnectionParamsDTO printer) {
    if (printer.connectionType == PosPrinterConnectionType.usb) {
      final usb = printer.usbParams!;
      return 'VID:${usb.vendorId} PID:${usb.productId}';
    } else {
      return 'IP: ${printer.networkParams!.ipAddress}';
    }
  }

  void _selectPrinter(PrinterConnectionParamsDTO printer) {
    setState(() {
      _selectedPrinter = printer;
      _statusText = 'Selected printer: ${_getPrinterTitle(printer)}';
    });
  }

  Future<void> _discoverUsbPrinters() async {
    setState(() {
      _isDiscovering = true;
      _statusText = 'Searching for USB printers...';
      _foundPrinters.clear();
    });

    try {
      final stream = _printersManager.findPrinters(
        filter: PrinterDiscoveryFilter(
          connectionTypes: [DiscoveryConnectionType.usb],
          languages: [],
        ),
      );

      await for (final printer in stream) {
        setState(() {
          _foundPrinters.add(printer);
        });
      }

      setState(() {
        _statusText = 'Found USB printers: ${_foundPrinters.length}';
      });
    } catch (e) {
      setState(() {
        _statusText = 'USB discovery error: $e';
      });
    } finally {
      setState(() {
        _isDiscovering = false;
      });
    }
  }

  Future<void> _discoverNetworkPrinters() async {
    setState(() {
      _isDiscovering = true;
      _statusText = 'Searching for network printers...';
      _foundPrinters.clear();
    });

    try {
      final stream = _printersManager.findPrinters(
        filter: PrinterDiscoveryFilter(
          connectionTypes: [
            DiscoveryConnectionType.sdk,
            DiscoveryConnectionType.tcp,
          ],
          languages: [],
        ),
      );

      await for (final printer in stream) {
        setState(() {
          _foundPrinters.add(printer);
        });
      }

      setState(() {
        _statusText = 'Found network printers: ${_foundPrinters.length}';
      });
    } catch (e) {
      setState(() {
        _statusText = 'Network discovery error: $e';
      });
    } finally {
      setState(() {
        _isDiscovering = false;
      });
    }
  }

  Future<void> _printTestReceipt() async {
    if (_selectedPrinter == null) return;

    setState(() {
      _statusText = 'Printing receipt...';
    });

    try {
      final now = DateTime.now().toString().substring(0, 19);
      final testReceiptHtml = '''
<html>
<head>
<meta charset="UTF-8">
<style>
body { font-family: monospace; font-size: 12px; margin: 10px; }
.center { text-align: center; }
.bold { font-weight: bold; }
.line { border-top: 1px dashed #000; margin: 5px 0; }
table { width: 100%; border-collapse: collapse; }
td { padding: 2px 0; }
.right { text-align: right; }
</style>
</head>
<body>
<div class="center bold">ТЕСТОВЫЙ ЧЕК</div>
<div class="center">Магазин "Пример"</div>
<div class="center">Тел: +7 (999) 123-45-67</div>
<div class="line"></div>

<table>
<tr><td>Товар 1</td><td class="right">100.00</td></tr>
<tr><td>Товар 2</td><td class="right">250.50</td></tr>
<tr><td>Скидка 10%</td><td class="right">-35.05</td></tr>
</table>

<div class="line"></div>
<div class="bold">ИТОГО: 315.45 руб.</div>
<div class="line"></div>

<div class="center">Спасибо за покупку!</div>
<div class="center">$now</div>
</body>
</html>
      ''';

      await _printersManager.printEscHTML(
          _selectedPrinter!, testReceiptHtml, 384);

      setState(() {
        _statusText = 'Чек отправлен на печать';
      });
    } catch (e) {
      setState(() {
        _statusText = 'Ошибка печати чека: $e';
      });
    }
  }

  Future<void> _printTestLabel() async {
    if (_selectedPrinter == null) return;

    setState(() {
      _statusText = 'Печать этикетки...';
    });

    try {
      final today = DateTime.now().toString().substring(0, 10);
      final testLabelHtml = '''
<html>
<head>
<meta charset="UTF-8">
<style>
body { font-family: Arial, sans-serif; font-size: 10px; margin: 5px; width: 200px; }
.center { text-align: center; }
.bold { font-weight: bold; }
.barcode { font-family: monospace; font-size: 24px; text-align: center; margin: 5px 0; }
</style>
</head>
<body>
<div class="center bold">ТЕСТОВАЯ ЭТИКЕТКА</div>
<div class="center">Артикул: TEST001</div>
<div class="barcode">*1234567890*</div>
<div class="center">Цена: 299.99 руб.</div>
<div class="center">$today</div>
</body>
</html>
      ''';

      await _printersManager.printZplHtml(
          _selectedPrinter!, testLabelHtml, 203);

      setState(() {
        _statusText = 'Этикетка отправлена на печать';
      });
    } catch (e) {
      setState(() {
        _statusText = 'Ошибка печати этикетки: $e';
      });
    }
  }

  Future<void> _checkPrinterStatus() async {
    if (_selectedPrinter == null) return;

    setState(() {
      _statusText = 'Проверка статуса принтера...';
    });

    try {
      final status = await _printersManager.getPrinterStatus(_selectedPrinter!);

      setState(() {
        _statusText =
            'Статус: ${status.success ? status.status : status.errorMessage}';
      });
    } catch (e) {
      setState(() {
        _statusText = 'Ошибка получения статуса: $e';
      });
    }
  }

  Future<void> _detectPrinterLanguage() async {
    if (_selectedPrinter == null) return;

    setState(() {
      _statusText = 'Определение языка принтера...';
    });

    try {
      final response =
          await _printersManager.checkPrinterLanguage(_selectedPrinter!);

      setState(() {
        _statusText =
            'Язык принтера: ${response.printerLanguage.name.toUpperCase()}';
      });
    } catch (e) {
      setState(() {
        _statusText = 'Ошибка определения языка: $e';
      });
    }
  }

  Future<void> _openCashDrawer() async {
    if (_selectedPrinter == null) return;
    setState(() {
      _statusText = 'Opening cash drawer...';
    });
    try {
      await _printersManager.openCashBox(_selectedPrinter!);
      if (mounted) {
        setState(() {
          _statusText = 'Cash drawer command sent';
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _statusText = 'Ошибка открытия ящика: $e';
        });
      }
    }
  }
}
