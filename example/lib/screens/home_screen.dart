import 'dart:async';

import 'package:flutter/material.dart';
import 'package:pos_printers/pos_printers.dart';
import '../models/printer_item.dart';
import '../services/printer_service.dart';
import '../utils/snackbar_helper.dart';
import '../widgets/network_settings_dialog.dart';
import '../widgets/printer_list_tile.dart';

/// Главный экран приложения, отображающий списки принтеров и функции работы с ними
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  /// Сервис для работы с принтерами
  final PrinterService _printerService = PrinterService();

  /// Глобальный ключ для ScaffoldMessenger
  final GlobalKey<ScaffoldMessengerState> _scaffoldMessengerKey =
      GlobalKey<ScaffoldMessengerState>();

  /// Вспомогательный класс для работы со снэкбарами
  late final SnackBarHelper _snackBarHelper;

  /// Список найденных принтеров
  final List<PrinterItem> _foundPrinters = [];

  /// Список подключённых принтеров
  final List<PrinterItem> _connectedPrinters = [];

  bool _isSearching = false;
  StreamSubscription<DiscoveredPrinterDTO>? _searchSubscription;

  @override
  void initState() {
    super.initState();
    _snackBarHelper = SnackBarHelper(_scaffoldMessengerKey);
  }

  @override
  void dispose() {
    _searchSubscription?.cancel();
    _printerService.dispose();
    super.dispose();
  }

  /// Запуск поиска принтеров
  Future<void> _findPrinters() async {
    if (_isSearching) return;

    setState(() {
      _isSearching = true;
      _foundPrinters.clear();
      _connectedPrinters.clear();
    });

    _searchSubscription?.cancel();

    try {
      final stream = _printerService.findPrinters();
      _searchSubscription = stream.listen(
        (discoveredPrinter) {
          final exists = _foundPrinters.any((p) => _printerService.samePrinter(
              p.discoveredPrinter, discoveredPrinter));
          if (!exists && mounted) {
            setState(() {
              _foundPrinters
                  .add(PrinterItem(discoveredPrinter: discoveredPrinter));
            });
          }
        },
        onDone: () {
          if (mounted) {
            setState(() => _isSearching = false);
            _snackBarHelper.showInfoSnackbar('Search finished.');
          }
          _searchSubscription = null;
        },
        onError: (err) {
          debugPrint('Search stream error: $err');
          if (mounted) {
            setState(() => _isSearching = false);
            _snackBarHelper.showErrorSnackbar('Search error: $err');
          }
          _searchSubscription = null;
        },
        cancelOnError: true,
      );
      _printerService.awaitDiscoveryComplete().catchError((e) {
        if (mounted && _isSearching) {
          setState(() => _isSearching = false);
          _snackBarHelper.showErrorSnackbar('Discovery failed: $e');
        }
      });
    } catch (e) {
      debugPrint('Error starting search: $e');
      if (mounted) {
        setState(() => _isSearching = false);
        _snackBarHelper.showErrorSnackbar('Error starting search: $e');
      }
    }
  }

  /// Подключение к принтеру
  Future<void> _connectToPrinter(PrinterItem item) async {
    try {
      await _printerService.connectToPrinter(item);
      if (mounted) {
        final alreadyIn = _connectedPrinters.any((p) => _printerService
            .samePrinter(p.discoveredPrinter, item.discoveredPrinter));
        if (!alreadyIn) {
          setState(() {
            _foundPrinters.removeWhere((p) => _printerService.samePrinter(
                p.discoveredPrinter, item.discoveredPrinter));
            _connectedPrinters.add(item);
          });
          _snackBarHelper.showSuccessSnackbar('Connected successfully!');
        }
      }
    } catch (e) {
      _snackBarHelper.showErrorSnackbar('Connect error: $e');
    }
  }

  /// Отключение от принтера
  Future<void> _disconnectPrinter(PrinterItem item) async {
    try {
      await _printerService.disconnectPrinter(item);
      if (mounted) {
        setState(() {
          _connectedPrinters.removeWhere((p) => _printerService.samePrinter(
              p.discoveredPrinter, item.discoveredPrinter));
        });
        _snackBarHelper.showInfoSnackbar('Disconnected.');
      }
    } catch (e) {
      _snackBarHelper.showErrorSnackbar('Disconnect error: $e');
    }
  }

  /// Запрос статуса принтера
  Future<void> _getStatus(PrinterItem item) async {
    try {
      final result = await _printerService.getPrinterStatus(item);
      debugPrint(
          'Status for ${item.discoveredPrinter.id} => success=${result.success}, status=${result.status}, error=${result.errorMessage}');
      if (mounted) {
        if (result.success) {
          _snackBarHelper.showInfoSnackbar('Status: ${result.status ?? "N/A"}');
        } else {
          _snackBarHelper
              .showErrorSnackbar('Get status failed: ${result.errorMessage}');
        }
      }
    } catch (e) {
      _snackBarHelper.showErrorSnackbar('Get status error: $e');
    }
  }

  /// Печать HTML на ESC/POS принтере
  Future<void> _printEscHtml(PrinterItem item) async {
    try {
      await _printerService.printEscHtml(item);
      if (mounted) _snackBarHelper.showSuccessSnackbar('ESC/POS HTML sent.');
    } catch (e) {
      _snackBarHelper.showErrorSnackbar('printReceiptHTML error: $e');
    }
  }

  /// Печать сырых ESC/POS команд
  Future<void> _printEscPosData(PrinterItem item) async {
    try {
      await _printerService.printEscPosData(item);
      if (mounted) {
        _snackBarHelper.showSuccessSnackbar('ESC/POS Raw data sent.');
      }
    } catch (e) {
      _snackBarHelper.showErrorSnackbar('printReceiptData error: $e');
    }
  }

  /// Печать лейбла сырыми командами
  Future<void> _printLabelRaw(PrinterItem item) async {
    try {
      await _printerService.printLabelRaw(item);
      if (mounted) {
        _snackBarHelper
            .showSuccessSnackbar('${item.language!.name} Raw data sent.');
      }
    } catch (e) {
      _snackBarHelper.showErrorSnackbar('printLabelData error: $e');
    }
  }

  /// Печать HTML на лейбл-принтер
  Future<void> _printLabelHtml(PrinterItem item) async {
    try {
      await _printerService.printLabelHtml(item);
      if (mounted) {
        _snackBarHelper
            .showSuccessSnackbar('${item.language!.name} HTML sent.');
      }
    } catch (e) {
      _snackBarHelper.showErrorSnackbar('printLabelHTML error: $e');
    }
  }

  /// Настройка сетевых параметров через активное соединение
  Future<void> _setNetSettingsViaConnection(
      PrinterItem item, NetSettingsDTO settings) async {
    _snackBarHelper
        .showInfoSnackbar('Applying network settings via connection...');
    try {
      await _printerService.setNetSettingsViaConnection(item, settings);
      if (mounted) {
        _snackBarHelper.showSuccessSnackbar(
            'Network settings applied! Printer restart required.');
      }
    } catch (e) {
      _snackBarHelper.showErrorSnackbar('Error applying network settings: $e');
    }
  }

  /// Настройка сетевых параметров через UDP broadcast
  Future<void> _configureNetViaUDP(
      PrinterItem item, NetSettingsDTO settings) async {
    final mac = item.discoveredPrinter.networkParams?.macAddress;
    if (mac == null || mac.isEmpty) {
      _snackBarHelper.showErrorSnackbar(
          'MAC Address not found for this printer. Cannot configure via UDP.');
      return;
    }
    _snackBarHelper
        .showInfoSnackbar('Applying network settings via UDP to $mac...');
    try {
      await _printerService.configureNetViaUDP(item, settings);
      if (mounted) {
        _snackBarHelper.showSuccessSnackbar(
            'Network settings sent via UDP to $mac! Printer restart required.');
      }
    } catch (e) {
      _snackBarHelper.showErrorSnackbar(
          'Error applying network settings via UDP to $mac: $e');
    }
  }

  /// Отображение диалога настройки сетевых параметров
  Future<void> _showNetworkSettingsDialog(
      {required PrinterItem item, required bool isUdp}) async {
    // Pre-fill with current printer IP if connected and setting via connection
    String? initialIp;
    if (!isUdp &&
        item.discoveredPrinter.type == PosPrinterConnectionType.network) {
      initialIp = item.connectionParams.networkParams?.ipAddress;
    }

    // Create default settings with pre-filled data
    final initialSettings = NetSettingsDTO(
      ipAddress: initialIp ?? '', // Use current IP if available
      mask: '255.255.255.0', // Default or fetch if possible
      gateway: '', // Default or fetch if possible
      dhcp: false, // Default or fetch if possible
    );

    try {
      final settings = await showDialog<NetSettingsDTO>(
        context: context,
        barrierDismissible: true,
        builder: (context) => NetworkSettingsDialog(
          initialSettings: initialSettings,
        ),
      );

      if (settings == null) {
        if (mounted) {
          _snackBarHelper.showInfoSnackbar('Network settings cancelled.');
        }
        return;
      }

      if (mounted) {
        if (isUdp) {
          await _configureNetViaUDP(item, settings);
        } else {
          await _setNetSettingsViaConnection(item, settings);
        }
      }
    } catch (e) {
      if (mounted) {
        _snackBarHelper.showErrorSnackbar('Error showing dialog: $e');
      }
    }
  }

  /// Переключение режима перевёрнутой печати
  Future<void> _toggleUpsideDownMode(PrinterItem item, bool value) async {
    setState(() {
      item.isUpsideDown = value;
    });
    _snackBarHelper.showSuccessSnackbar(
        'Режим перевернутой печати установлен на: ${value ? "ВКЛ" : "ВЫКЛ"}');
  }

  /// Обработка выбора языка из PrinterListTile
  void _handleLanguageSelected(PrinterItem item, LabelPrinterLanguage? lang) {
    setState(() {
      item.language = lang;
    });
  }

  @override
  Widget build(BuildContext context) {
    return ScaffoldMessenger(
      key: _scaffoldMessengerKey,
      child: Scaffold(
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
                      if (p.language != null) {
                        await _printLabelHtml(p);
                        await Future.delayed(const Duration(milliseconds: 500));
                        await _printLabelRaw(p);
                      } else {
                        _snackBarHelper.showErrorSnackbar(
                            'Select language for ${p.discoveredPrinter.id}');
                      }
                    } else {
                      await _printEscHtml(p);
                      await Future.delayed(const Duration(milliseconds: 500));
                      await _printEscPosData(p);
                    }
                  }
                  if (mounted) {
                    _snackBarHelper.showSuccessSnackbar('Print jobs sent.');
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
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                itemCount: _connectedPrinters.length,
                itemBuilder: (context, index) {
                  final item = _connectedPrinters[index];
                  return PrinterListTile(
                    item: item,
                    isConnected: true,
                    onConnect: (_) {}, // Already connected
                    onDisconnect: _disconnectPrinter,
                    onGetStatus: _getStatus,
                    onToggleUpsideDown: _toggleUpsideDownMode,
                    onSetNetworkSettings: (item) =>
                        _showNetworkSettingsDialog(item: item, isUdp: false),
                    onConfigureUdp: (_) {}, // Not applicable for connected
                    onLanguageSelected: _handleLanguageSelected,
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
              child: _foundPrinters.isEmpty && !_isSearching
                  ? const Center(
                      child: Text('No printers found. Tap search button.'))
                  : ListView.builder(
                      itemCount: _foundPrinters.length,
                      itemBuilder: (context, index) {
                        final item = _foundPrinters[index];
                        return PrinterListTile(
                          item: item,
                          isConnected: false,
                          onConnect: _connectToPrinter,
                          onDisconnect: (_) {}, // Not connected
                          onGetStatus: (_) {}, // Not connected
                          onToggleUpsideDown: (_, __) {}, // Not connected
                          onSetNetworkSettings: (_) {}, // Not connected
                          onConfigureUdp: (item) => _showNetworkSettingsDialog(
                              item: item, isUdp: true),
                          onLanguageSelected: _handleLanguageSelected,
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
