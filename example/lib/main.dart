import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:pos_printers/pos_printers.dart';

void main() {
  runApp(const PosPrintersDemoApp());
}

class PosPrintersDemoApp extends StatelessWidget {
  const PosPrintersDemoApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'POS Printers Demo',
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.indigo,
      ),
      home: const PosPrintersDemoScreen(),
    );
  }
}

class PosPrintersDemoScreen extends StatefulWidget {
  const PosPrintersDemoScreen({super.key});

  @override
  State<PosPrintersDemoScreen> createState() => _PosPrintersDemoScreenState();
}

class _PosPrintersDemoScreenState extends State<PosPrintersDemoScreen>
    with SingleTickerProviderStateMixin {
  final PosPrintersManager _manager = PosPrintersManager();
  final List<PrinterConnectionParamsDTO> _printers =
      <PrinterConnectionParamsDTO>[];
  final List<String> _logs = <String>[];
  StreamSubscription<PrinterConnectionEvent>? _connectionEventsSubscription;

  late final TabController _tabController;

  PrinterConnectionParamsDTO? _selectedPrinter;
  bool _isDiscovering = false;
  bool _upsideDown = false;
  bool _isStressRunning = false;

  final TextEditingController _stressIterationsController =
      TextEditingController(text: '20');
  final TextEditingController _stressConcurrencyController =
      TextEditingController(text: '4');

  final TextEditingController _escHtmlController = TextEditingController(
    text: _defaultEscHtml,
  );
  final TextEditingController _escRawController = TextEditingController(
    text: _defaultEscRaw,
  );
  final TextEditingController _zplHtmlController = TextEditingController(
    text: _defaultZplHtml,
  );
  final TextEditingController _zplRawController = TextEditingController(
    text: _defaultZplRaw,
  );
  final TextEditingController _tsplHtmlController = TextEditingController(
    text: _defaultTsplHtml,
  );
  final TextEditingController _tsplRawController = TextEditingController(
    text: _defaultTsplRaw,
  );

  final TextEditingController _udpIpController =
      TextEditingController(text: '192.168.2.217');
  final TextEditingController _udpMaskController =
      TextEditingController(text: '255.255.255.0');
  final TextEditingController _udpGatewayController =
      TextEditingController(text: '192.168.2.1');
  final TextEditingController _udpMacController =
      TextEditingController(text: '');
  bool _udpDhcp = true;

  final TextEditingController _setIpController =
      TextEditingController(text: '192.168.2.217');
  final TextEditingController _setMaskController =
      TextEditingController(text: '255.255.255.0');
  final TextEditingController _setGatewayController =
      TextEditingController(text: '192.168.2.1');
  bool _setDhcp = true;

  int _escWidth = 576;
  final int _labelWidth = 203;

  static const String _defaultEscHtml = '''
<html>
  <body style="font-family: monospace; padding: 8px;">
    <h3>ESC/POS HTML</h3>
    <p>Demo receipt line 1</p>
    <p>Demo receipt line 2</p>
    <p><b>Total: 12.34</b></p>
  </body>
</html>
''';

  static const String _defaultEscRaw = '''
TEXT ESC/POS RAW DEMO
TEXT Thank you
FEED 3
CUT
''';

  static const String _defaultZplHtml = '''
<html>
  <body style="font-family: Arial; padding: 8px;">
    <h3>ZPL HTML</h3>
    <p>SKU: DEMO-001</p>
    <p>Price: 9.99</p>
  </body>
</html>
''';

  static const String _defaultZplRaw = '''
^XA
^FO50,50^ADN,36,20^FDZPL RAW DEMO^FS
^FO50,100^BQN,2,4^FDQA,https://example.com^FS
^XZ
''';

  static const String _defaultTsplHtml = '''
<html>
  <body style="font-family: Arial; padding: 8px;">
    <h3>TSPL HTML</h3>
    <p>Item: Demo Label</p>
    <p>Qty: 1</p>
  </body>
</html>
''';

  static const String _defaultTsplRaw = '''
SIZE 58 mm, 60 mm
GAP 2 mm, 0 mm
CLS
TEXT 40,40,"3",0,1,1,"TSPL RAW DEMO"
QRCODE 40,90,H,4,A,0,"https://example.com"
PRINT 1
''';

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 4, vsync: this);
    _bindManagerStreams();
  }

  @override
  void dispose() {
    _connectionEventsSubscription?.cancel();
    _tabController.dispose();
    _manager.dispose();

    _escHtmlController.dispose();
    _escRawController.dispose();
    _zplHtmlController.dispose();
    _zplRawController.dispose();
    _tsplHtmlController.dispose();
    _tsplRawController.dispose();

    _udpIpController.dispose();
    _udpMaskController.dispose();
    _udpGatewayController.dispose();
    _udpMacController.dispose();

    _setIpController.dispose();
    _setMaskController.dispose();
    _setGatewayController.dispose();
    _stressIterationsController.dispose();
    _stressConcurrencyController.dispose();

    super.dispose();
  }

  void _bindManagerStreams() {
    _connectionEventsSubscription = _manager.connectionEvents.listen((event) {
      if (!mounted) {
        return;
      }
      final id = event.printer?.id ?? 'unknown';
      _log('USB event: ${event.type.name} ($id)');
    });
  }

  void _log(String message) {
    if (!mounted) {
      return;
    }
    final now = DateTime.now().toIso8601String().substring(11, 19);
    setState(() {
      _logs.insert(0, '[$now] $message');
      if (_logs.length > 120) {
        _logs.removeRange(120, _logs.length);
      }
    });
  }

  Future<void> _discover(
      {required PrinterDiscoveryFilter filter, required String name}) async {
    setState(() {
      _isDiscovering = true;
      _printers.clear();
      _selectedPrinter = null;
    });

    _log('Starting discovery: $name');

    try {
      await for (final printer in _manager.findPrinters(filter: filter)) {
        if (!mounted) {
          break;
        }

        setState(() {
          final exists = _printers.any((item) => item.id == printer.id);
          if (!exists) {
            _printers.add(printer);
          }
        });
        _log('Discovered printer: ${printer.id}');
      }

      await _manager.awaitDiscoveryComplete();
      _log('Discovery completed: $name, found ${_printers.length} device(s)');
    } catch (error) {
      _log('Discovery failed: $error');
    } finally {
      if (mounted) {
        setState(() {
          _isDiscovering = false;
        });
      }
    }
  }

  Future<void> _runWithSelection(String actionName,
      Future<void> Function(PrinterConnectionParamsDTO printer) action) async {
    final printer = _selectedPrinter;
    if (printer == null) {
      _log('Action "$actionName" ignored: no printer selected');
      return;
    }

    try {
      await action(printer);
      _log('Action "$actionName" completed');
    } catch (error) {
      _log('Action "$actionName" failed: $error');
    }
  }

  Future<void> _requestUsbPermission() async {
    await _runWithSelection('Request USB permission', (printer) async {
      if (printer.usbParams == null) {
        _log('Selected printer is not USB');
        return;
      }
      final result = await _manager.requestUsbPermission(printer.usbParams!);
      _log(
          'USB permission result: granted=${result.granted}, message=${result.errorMessage ?? '-'}');
    });
  }

  Future<void> _checkUsbPermission() async {
    await _runWithSelection('Check USB permission', (printer) async {
      if (printer.usbParams == null) {
        _log('Selected printer is not USB');
        return;
      }
      final result = await _manager.hasUsbPermission(printer.usbParams!);
      _log(
          'USB permission: granted=${result.granted}, message=${result.errorMessage ?? '-'}');
    });
  }

  Future<void> _printEscHtml() async {
    await _runWithSelection('ESC HTML print', (printer) async {
      await _manager.withUsbPermission(printer, () {
        return _manager.printEscHTML(
          printer,
          _escHtmlController.text,
          _escWidth,
          upsideDown: _upsideDown,
        );
      });
    });
  }

  Future<void> _printEscRaw() async {
    await _runWithSelection('ESC RAW print', (printer) async {
      final bytes = _convertEscPseudoRaw(_escRawController.text);
      await _manager.withUsbPermission(printer, () {
        return _manager.printEscRawData(
          printer,
          bytes,
          _escWidth,
          upsideDown: _upsideDown,
        );
      });
    });
  }

  Future<void> _printZplHtml() async {
    await _runWithSelection('ZPL HTML print', (printer) async {
      await _manager.printZplHtml(
        printer,
        _zplHtmlController.text,
        _labelWidth,
      );
    });
  }

  Future<void> _printZplRaw() async {
    await _runWithSelection('ZPL RAW print', (printer) async {
      await _manager.printZplRawData(
        printer,
        Uint8List.fromList(utf8.encode(_zplRawController.text)),
        _labelWidth,
      );
    });
  }

  Future<void> _printTsplHtml() async {
    await _runWithSelection('TSPL HTML print', (printer) async {
      await _manager.printTsplHtml(
        printer,
        _tsplHtmlController.text,
        _labelWidth,
      );
    });
  }

  Future<void> _printTsplRaw() async {
    await _runWithSelection('TSPL RAW print', (printer) async {
      await _manager.printTsplRawData(
        printer,
        Uint8List.fromList(utf8.encode(_tsplRawController.text)),
        _labelWidth,
      );
    });
  }

  Future<void> _openCashDrawer() async {
    await _runWithSelection('Open cash drawer', (printer) async {
      await _manager.openCashBox(printer);
    });
  }

  Future<void> _checkStatus() async {
    await _runWithSelection('Get ESC/POS status', (printer) async {
      final status = await _manager.getPrinterStatus(printer);
      _log(
          'ESC/POS status: success=${status.success}, status=${status.status}, error=${status.errorMessage}');
    });
  }

  Future<void> _checkSerialNumber() async {
    await _runWithSelection('Get serial number', (printer) async {
      final result = await _manager.getPrinterSN(printer);
      _log(
          'Serial number: success=${result.success}, value=${result.value}, error=${result.errorMessage}');
    });
  }

  Future<void> _checkZplStatus() async {
    await _runWithSelection('Get ZPL status', (printer) async {
      final result = await _manager.getZPLPrinterStatus(printer);
      _log(
          'ZPL status: success=${result.success}, code=${result.code}, error=${result.errorMessage}');
    });
  }

  Future<void> _checkTsplStatus() async {
    await _runWithSelection('Get TSPL status', (printer) async {
      final result = await _manager.getTSPLPrinterStatus(printer);
      _log(
          'TSPL status: success=${result.success}, code=${result.code}, error=${result.errorMessage}');
    });
  }

  Future<void> _configureUdp() async {
    final settings = NetworkParams(
      ipAddress: _udpIpController.text.trim(),
      mask: _udpMaskController.text.trim().isEmpty
          ? null
          : _udpMaskController.text.trim(),
      gateway: _udpGatewayController.text.trim().isEmpty
          ? null
          : _udpGatewayController.text.trim(),
      macAddress: _udpMacController.text.trim().isEmpty
          ? null
          : _udpMacController.text.trim(),
      dhcp: _udpDhcp,
    );

    try {
      await _manager.configureNetViaUDP('', settings);
      _log('UDP network configuration command sent');
    } catch (error) {
      _log('UDP network configuration failed: $error');
    }
  }

  Future<void> _setPrinterNetwork() async {
    await _runWithSelection('Set printer network settings', (printer) async {
      final settings = NetworkParams(
        ipAddress: _setIpController.text.trim(),
        mask: _setMaskController.text.trim(),
        gateway: _setGatewayController.text.trim(),
        macAddress: null,
        dhcp: _setDhcp,
      );
      await _manager.setNetSettings(printer, settings);
    });
  }

  Future<void> _runMultiConnectionStressTest() async {
    final iterations =
        int.tryParse(_stressIterationsController.text.trim()) ?? 20;
    final concurrency =
        int.tryParse(_stressConcurrencyController.text.trim()) ?? 4;

    if (iterations <= 0 || concurrency <= 0) {
      _log(
          'Stress test input is invalid: iterations and concurrency must be > 0');
      return;
    }

    await _runWithSelection('Multi-connection stress test', (printer) async {
      if (_isStressRunning) {
        _log('Stress test is already running');
        return;
      }

      setState(() {
        _isStressRunning = true;
      });

      final stopwatch = Stopwatch()..start();
      int successCount = 0;
      int failedCount = 0;

      Future<void> worker(int workerIndex) async {
        for (int index = workerIndex;
            index < iterations;
            index += concurrency) {
          try {
            final StatusResult result;
            if (printer.connectionType == PosPrinterConnectionType.usb) {
              result = await _manager.withUsbPermission(
                printer,
                () => _manager.getPrinterStatus(printer),
              );
            } else {
              result = await _manager.getPrinterStatus(printer);
            }

            if (result.success) {
              successCount++;
              _log('Stress #${index + 1}: success (${result.status})');
            } else {
              failedCount++;
              _log(
                  'Stress #${index + 1}: status failed (${result.errorMessage ?? result.status})');
            }
          } catch (error) {
            failedCount++;
            _log('Stress #${index + 1}: exception ($error)');
          }
        }
      }

      try {
        _log(
            'Stress test started: iterations=$iterations, concurrency=$concurrency');
        await Future.wait(
          List<Future<void>>.generate(
            concurrency,
            (int workerIndex) => worker(workerIndex),
          ),
        );
      } finally {
        stopwatch.stop();
        if (mounted) {
          setState(() {
            _isStressRunning = false;
          });
        }
      }

      _log(
        'Stress test completed: success=$successCount, failed=$failedCount, elapsed=${stopwatch.elapsedMilliseconds}ms',
      );
    });
  }

  Uint8List _convertEscPseudoRaw(String source) {
    final lines = const LineSplitter().convert(source);
    final output = BytesBuilder(copy: false);

    for (final rawLine in lines) {
      final line = rawLine.trim();
      if (line.isEmpty) {
        continue;
      }
      if (line.toUpperCase() == 'ESC @') {
        output.add(<int>[0x1B, 0x40]);
      } else if (line.toUpperCase() == 'CUT') {
        output.add(<int>[0x1D, 0x56, 0x42, 0x00]);
      } else if (line.toUpperCase().startsWith('FEED')) {
        final parts = line.split(' ');
        final count = parts.length > 1 ? int.tryParse(parts[1]) ?? 1 : 1;
        output.add(List<int>.filled(count, 0x0A));
      } else if (line.toUpperCase().startsWith('TEXT ')) {
        output.add(utf8.encode(line.substring(5)));
        output.addByte(0x0A);
      } else {
        output.add(utf8.encode(line));
        output.addByte(0x0A);
      }
    }

    return output.toBytes();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('POS Printers Demo'),
        bottom: TabBar(
          controller: _tabController,
          isScrollable: true,
          tabs: const <Tab>[
            Tab(icon: Icon(Icons.search), text: 'Discovery'),
            Tab(icon: Icon(Icons.usb), text: 'Printer'),
            Tab(icon: Icon(Icons.print), text: 'Print'),
            Tab(icon: Icon(Icons.settings_ethernet), text: 'Network'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        children: <Widget>[
          _buildDiscoveryTab(),
          _buildPrinterTab(),
          _buildPrintTab(),
          _buildNetworkTab(),
        ],
      ),
    );
  }

  Widget _buildDiscoveryTab() {
    return Column(
      children: <Widget>[
        Padding(
          padding: const EdgeInsets.all(12),
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            children: <Widget>[
              ElevatedButton.icon(
                onPressed: _isDiscovering
                    ? null
                    : () => _discover(
                          name: 'ALL',
                          filter: PrinterDiscoveryFilter(
                            connectionTypes: const <DiscoveryConnectionType>[
                              DiscoveryConnectionType.usb,
                              DiscoveryConnectionType.sdk,
                              DiscoveryConnectionType.tcp,
                            ],
                          ),
                        ),
                icon: const Icon(Icons.search),
                label: const Text('Discover All'),
              ),
              ElevatedButton.icon(
                onPressed: _isDiscovering
                    ? null
                    : () => _discover(
                          name: 'USB',
                          filter: PrinterDiscoveryFilter(
                            connectionTypes: const <DiscoveryConnectionType>[
                              DiscoveryConnectionType.usb,
                            ],
                          ),
                        ),
                icon: const Icon(Icons.usb),
                label: const Text('Discover USB'),
              ),
              ElevatedButton.icon(
                onPressed: _isDiscovering
                    ? null
                    : () => _discover(
                          name: 'NETWORK',
                          filter: PrinterDiscoveryFilter(
                            connectionTypes: const <DiscoveryConnectionType>[
                              DiscoveryConnectionType.sdk,
                              DiscoveryConnectionType.tcp,
                            ],
                          ),
                        ),
                icon: const Icon(Icons.wifi),
                label: const Text('Discover Network'),
              ),
            ],
          ),
        ),
        const Divider(height: 1),
        Expanded(
          child: _printers.isEmpty
              ? const Center(child: Text('No printers discovered yet'))
              : ListView.builder(
                  itemCount: _printers.length,
                  itemBuilder: (BuildContext context, int index) {
                    final printer = _printers[index];
                    final selected = _selectedPrinter?.id == printer.id;
                    final isUsb =
                        printer.connectionType == PosPrinterConnectionType.usb;
                    return ListTile(
                      selected: selected,
                      leading: Icon(isUsb ? Icons.usb : Icons.wifi),
                      title: Text(printer.id),
                      subtitle: Text(isUsb
                          ? 'USB: VID=${printer.usbParams?.vendorId} PID=${printer.usbParams?.productId}'
                          : 'Network: ${printer.networkParams?.ipAddress ?? '-'}'),
                      trailing: selected
                          ? const Icon(Icons.check_circle, color: Colors.green)
                          : null,
                      onTap: () {
                        setState(() {
                          _selectedPrinter = printer;
                        });
                        _log('Selected printer: ${printer.id}');
                      },
                    );
                  },
                ),
        ),
        const Divider(height: 1),
        SizedBox(
          height: 180,
          child: _buildLogPanel(),
        ),
      ],
    );
  }

  Widget _buildPrinterTab() {
    final printer = _selectedPrinter;
    return ListView(
      padding: const EdgeInsets.all(12),
      children: <Widget>[
        Card(
          child: ListTile(
            title: const Text('Selected Printer'),
            subtitle: Text(printer?.id ?? 'Not selected'),
          ),
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: <Widget>[
            ElevatedButton.icon(
              onPressed: _requestUsbPermission,
              icon: const Icon(Icons.security),
              label: const Text('Request USB Permission'),
            ),
            ElevatedButton.icon(
              onPressed: _checkUsbPermission,
              icon: const Icon(Icons.verified_user),
              label: const Text('Check USB Permission'),
            ),
            ElevatedButton.icon(
              onPressed: _checkStatus,
              icon: const Icon(Icons.info),
              label: const Text('ESC/POS Status'),
            ),
            ElevatedButton.icon(
              onPressed: _checkSerialNumber,
              icon: const Icon(Icons.tag),
              label: const Text('Get Serial Number'),
            ),
            ElevatedButton.icon(
              onPressed: _checkZplStatus,
              icon: const Icon(Icons.label),
              label: const Text('ZPL Status'),
            ),
            ElevatedButton.icon(
              onPressed: _checkTsplStatus,
              icon: const Icon(Icons.confirmation_number),
              label: const Text('TSPL Status'),
            ),
            ElevatedButton.icon(
              onPressed: _openCashDrawer,
              icon: const Icon(Icons.meeting_room_outlined),
              label: const Text('Open Cash Drawer'),
            ),
          ],
        ),
        const SizedBox(height: 12),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text('Multi-Connection Stress Test',
                    style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                Row(
                  children: <Widget>[
                    Expanded(
                      child: _buildTextField(
                          _stressIterationsController, 'Iterations'),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: _buildTextField(
                          _stressConcurrencyController, 'Concurrency'),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                ElevatedButton.icon(
                  onPressed:
                      _isStressRunning ? null : _runMultiConnectionStressTest,
                  icon: const Icon(Icons.bolt),
                  label: Text(_isStressRunning
                      ? 'Stress Test Running...'
                      : 'Run Multi-Connection Stress Test'),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        SizedBox(height: 320, child: _buildLogPanel()),
      ],
    );
  }

  Widget _buildPrintTab() {
    return ListView(
      padding: const EdgeInsets.all(12),
      children: <Widget>[
        SwitchListTile.adaptive(
          value: _upsideDown,
          onChanged: (bool value) {
            setState(() {
              _upsideDown = value;
            });
            _log('UpsideDown: ${value ? 'ON' : 'OFF'}');
          },
          title: const Text('UpsideDown (180°)'),
          subtitle: const Text('Applied only to ESC print methods for now'),
        ),
        const SizedBox(height: 8),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                const Text('ESC/POS width'),
                const SizedBox(height: 8),
                DropdownButton<int>(
                  value: _escWidth,
                  items: const <DropdownMenuItem<int>>[
                    DropdownMenuItem(
                        value: 384, child: Text('58mm (384 dots)')),
                    DropdownMenuItem(
                        value: 576, child: Text('80mm (576 dots)')),
                  ],
                  onChanged: (int? value) {
                    if (value == null) {
                      return;
                    }
                    setState(() {
                      _escWidth = value;
                    });
                  },
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 8),
        _buildLanguageCard(
          title: 'ESC/POS',
          htmlController: _escHtmlController,
          rawController: _escRawController,
          onHtmlPrint: _printEscHtml,
          onRawPrint: _printEscRaw,
          rawHint: 'Pseudo RAW format supports: ESC @, TEXT ..., FEED n, CUT',
        ),
        const SizedBox(height: 8),
        _buildLanguageCard(
          title: 'ZPL',
          htmlController: _zplHtmlController,
          rawController: _zplRawController,
          onHtmlPrint: _printZplHtml,
          onRawPrint: _printZplRaw,
          rawHint: 'Raw ZPL commands, e.g. ^XA ... ^XZ',
        ),
        const SizedBox(height: 8),
        _buildLanguageCard(
          title: 'TSPL',
          htmlController: _tsplHtmlController,
          rawController: _tsplRawController,
          onHtmlPrint: _printTsplHtml,
          onRawPrint: _printTsplRaw,
          rawHint: 'Raw TSPL commands, e.g. SIZE/GAP/CLS/TEXT/PRINT',
        ),
        const SizedBox(height: 12),
        SizedBox(height: 260, child: _buildLogPanel()),
      ],
    );
  }

  Widget _buildNetworkTab() {
    return ListView(
      padding: const EdgeInsets.all(12),
      children: <Widget>[
        Card(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text('Configure Network via UDP',
                    style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                _buildTextField(_udpIpController, 'IP address'),
                _buildTextField(_udpMaskController, 'Mask'),
                _buildTextField(_udpGatewayController, 'Gateway'),
                _buildTextField(_udpMacController, 'MAC address (optional)'),
                SwitchListTile.adaptive(
                  value: _udpDhcp,
                  onChanged: (bool value) {
                    setState(() {
                      _udpDhcp = value;
                    });
                  },
                  title: const Text('DHCP'),
                  contentPadding: EdgeInsets.zero,
                ),
                const SizedBox(height: 8),
                ElevatedButton.icon(
                  onPressed: _configureUdp,
                  icon: const Icon(Icons.settings_ethernet),
                  label: const Text('Configure via UDP'),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 10),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text('Set Network to Selected Printer',
                    style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                _buildTextField(_setIpController, 'IP address'),
                _buildTextField(_setMaskController, 'Mask'),
                _buildTextField(_setGatewayController, 'Gateway'),
                SwitchListTile.adaptive(
                  value: _setDhcp,
                  onChanged: (bool value) {
                    setState(() {
                      _setDhcp = value;
                    });
                  },
                  title: const Text('DHCP'),
                  contentPadding: EdgeInsets.zero,
                ),
                const SizedBox(height: 8),
                ElevatedButton.icon(
                  onPressed: _setPrinterNetwork,
                  icon: const Icon(Icons.save),
                  label: const Text('Apply to Selected Printer'),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        SizedBox(height: 260, child: _buildLogPanel()),
      ],
    );
  }

  Widget _buildLanguageCard({
    required String title,
    required TextEditingController htmlController,
    required TextEditingController rawController,
    required Future<void> Function() onHtmlPrint,
    required Future<void> Function() onRawPrint,
    required String rawHint,
  }) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            TextField(
              controller: htmlController,
              minLines: 4,
              maxLines: 8,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                labelText: 'HTML payload',
              ),
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: <Widget>[
                ElevatedButton.icon(
                  onPressed: onHtmlPrint,
                  icon: const Icon(Icons.html),
                  label: Text('Print $title HTML'),
                ),
              ],
            ),
            const SizedBox(height: 12),
            TextField(
              controller: rawController,
              minLines: 4,
              maxLines: 8,
              decoration: InputDecoration(
                border: const OutlineInputBorder(),
                labelText: 'RAW payload',
                helperText: rawHint,
              ),
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: <Widget>[
                ElevatedButton.icon(
                  onPressed: onRawPrint,
                  icon: const Icon(Icons.memory),
                  label: Text('Print $title RAW'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTextField(TextEditingController controller, String label) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: TextField(
        controller: controller,
        decoration: InputDecoration(
          border: const OutlineInputBorder(),
          labelText: label,
        ),
      ),
    );
  }

  Widget _buildLogPanel() {
    return Container(
      decoration: BoxDecoration(
        border: Border.all(color: Colors.black12),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            color: Colors.black12,
            child: const Text('Execution Log'),
          ),
          Expanded(
            child: _logs.isEmpty
                ? const Center(child: Text('No logs yet'))
                : ListView.builder(
                    reverse: false,
                    itemCount: _logs.length,
                    itemBuilder: (BuildContext context, int index) {
                      return Padding(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 10, vertical: 4),
                        child: Text(
                          _logs[index],
                          style: const TextStyle(fontSize: 12),
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}
