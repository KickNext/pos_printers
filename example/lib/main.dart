import 'dart:async';

import 'package:flutter/material.dart';
import 'package:pos_printers/pos_printers.dart';
import 'package:pos_printers/pos_printers.pigeon.dart';
import 'package:pos_printers_example/test_html.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final PosPrintersManager _posPrintersManager = PosPrintersManager();
  final List<XPrinterDTO> _printers = [];
  bool _isSearching = false;
  XPrinterDTO? connectedPrinter;

  @override
  void initState() {
    _getPrinters();
    super.initState();
  }

  Future<void> _getPrinters() async {
    setState(() {
      _isSearching = true;
    });
    _printers.clear();
    await for (var printer in _posPrintersManager.findPrinters()) {
      _printers.add(printer);
      setState(() {});
    }
    setState(() {
      _isSearching = false;
    });
  }

  Future<void> _connectPrinter(XPrinterDTO printer) async {
    final result = await POSPrintersApi().connectPrinter(printer);
    if (result.success) {
      connectedPrinter = printer;
    }
    setState(() {});
  }

  Future<void> _testPrint() async {
    if (connectedPrinter != null) {
      await POSPrintersApi().printHTML("test", 576);
    }
  }

  Future<void> _updatePrinterSettings(XPrinterDTO printer) async {
    final netSettings = NetSettingsDTO(
      ipAddress: '192.168.2.254',
      mask: '255.255.255.0',
      gateway: '192.168.2.1',
      dhcp: false,
    );
    await POSPrintersApi().setNetSettingsToPrinter(printer, netSettings);
    await _getPrinters();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('POS Printers'),
          actions: [
            if (connectedPrinter != null)
              IconButton(
                onPressed: () async {
                  _testPrint();
                },
                icon: const Icon(Icons.print),
              ),
          ],
        ),
        floatingActionButton: FloatingActionButton(
          onPressed: _isSearching ? null : _getPrinters,
          child: _isSearching
              ? const Center(
                  child: CircularProgressIndicator(),
                )
              : const Icon(Icons.refresh),
        ),
        body: ListView.builder(
          itemCount: _printers.length,
          itemBuilder: (context, index) {
            final printer = _printers[index];
            return ListTile(
              title: Text(printer.connectionType.name.toString()),
              subtitle: Text(printer.connectionType == PosPrinterConnectionType.usb
                  ? '${printer.usbPath}'
                  : '${printer.macAddress}\n${printer.ipAddress}'),
              trailing: IconButton(
                  onPressed: () async {
                    await _updatePrinterSettings(printer);
                  },
                  icon: const Icon(Icons.self_improvement_outlined)),
              onTap: () => _connectPrinter(printer),
            );
          },
        ),
      ),
    );
  }
}
