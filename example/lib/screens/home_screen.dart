import 'dart:async';

import 'package:flutter/material.dart';
import 'package:pos_printers/pos_printers.dart';
import '../models/printer_item.dart';
import '../services/printer_service.dart';
import '../utils/snackbar_helper.dart';
import '../widgets/network_settings_dialog.dart';
import '../widgets/printer_list_tile.dart';

/// Main screen displaying printer lists and actions
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  /// Printer service
  final PrinterService _printerService = PrinterService();

  /// Global key for ScaffoldMessenger
  final GlobalKey<ScaffoldMessengerState> _scaffoldMessengerKey =
      GlobalKey<ScaffoldMessengerState>();

  /// Helper for snackbars
  late final SnackBarHelper _snackBarHelper;

  /// List of saved (connected) printers
  final List<PrinterItem> _savedPrinters = [
    // Example: can be loaded from settings/DB, currently empty
    // PrinterItem(discoveredPrinter: ... , isSaved: true),
  ];

  /// List of found (not saved) printers
  final List<PrinterItem> _foundPrinters = [];

  /// List of connected printers (only saved)
  List<PrinterItem> get _connectedPrinters => _savedPrinters;

  /// Scroll controllers for lists
  final ScrollController _connectedScrollController = ScrollController();
  final ScrollController _foundScrollController = ScrollController();

  bool _isSearching = false;
  StreamSubscription<PrinterConnectionParamsDTO>? _searchSubscription;
  StreamSubscription<PrinterConnectionEvent>? _connectionEventsSub;

  @override
  void initState() {
    super.initState();
    _snackBarHelper = SnackBarHelper(_scaffoldMessengerKey);
    // Subscribe to USB attach/detach events
    _connectionEventsSub = _printerService.connectionEvents.listen((event) {
      if (!mounted) return;
      final savedIdx =
          _savedPrinters.indexWhere((p) => p.connectionParams.id == event.id);
      final foundIdx =
          _foundPrinters.indexWhere((p) => p.connectionParams.id == event.id);
      if (event.type == PrinterConnectionEventType.attached) {
        _snackBarHelper.showInfoSnackbar('USB printer attached: ${event.id}');
        if (savedIdx != -1) {
          setState(() {
            _savedPrinters[savedIdx].isConnected = true;
          });
        } else if (foundIdx == -1 && event.printer != null) {
          setState(() {
            _foundPrinters.add(PrinterItem(
                connectionParams: event.printer!,
                isConnected: true,
                isSaved: false));
          });
        }
      } else if (event.type == PrinterConnectionEventType.detached) {
        _snackBarHelper.showInfoSnackbar('USB printer detached: ${event.id}');
        if (savedIdx != -1) {
          setState(() {
            _savedPrinters[savedIdx].isConnected = false;
          });
        } else if (foundIdx != -1) {
          setState(() {
            _foundPrinters.removeAt(foundIdx);
          });
        }
      }
    });
  }

  @override
  void dispose() {
    _searchSubscription?.cancel();
    _connectionEventsSub?.cancel();
    _connectedScrollController.dispose();
    _foundScrollController.dispose();
    _printerService.dispose();
    super.dispose();
  }

  /// Start searching for printers
  Future<void> _findPrinters() async {
    if (_isSearching) return;

    setState(() {
      _isSearching = true;
      _foundPrinters.clear();
    });

    _searchSubscription?.cancel();

    try {
      final stream = _printerService.findPrinters();
      _searchSubscription = stream.listen(
        (discoveredPrinter) {
          final exists = _foundPrinters.any((p) => _printerService.samePrinter(
              p.connectionParams, discoveredPrinter));
          if (!exists && mounted) {
            setState(() {
              _foundPrinters
                  .add(PrinterItem(connectionParams: discoveredPrinter));
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

  /// Connect to a printer
  Future<void> _connectToPrinter(PrinterItem item) async {
    try {
      if (mounted) {
        final alreadyIn = _savedPrinters.any((p) => _printerService.samePrinter(
            p.connectionParams, item.connectionParams));
        if (!alreadyIn) {
          setState(() {
            _foundPrinters.removeWhere((p) => _printerService.samePrinter(
                p.connectionParams, item.connectionParams));
            _savedPrinters.add(item);
          });
          _snackBarHelper.showSuccessSnackbar('Connected successfully!');
        }
      }
    } catch (e) {
      _snackBarHelper.showErrorSnackbar('Connect error: $e');
    }
  }

  /// Disconnect from a printer
  Future<void> _disconnectPrinter(PrinterItem item) async {
    try {
      if (mounted) {
        setState(() {
          _savedPrinters.removeWhere((p) => _printerService.samePrinter(
              p.connectionParams, item.connectionParams));
        });
        _snackBarHelper.showInfoSnackbar('Disconnected.');
      }
    } catch (e) {
      _snackBarHelper.showErrorSnackbar('Disconnect error: $e');
    }
  }

  /// Get printer status
  Future<void> _getStatus(PrinterItem item) async {
    try {
      final result = await _printerService.getPrinterStatus(item);
      debugPrint(
          'Status for ${item.connectionParams.id} => success=${result.success}, status=${result.status}, error=${result.errorMessage}');
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

  /// Print HTML on ESC/POS printer
  Future<void> _printEscHtml(PrinterItem item) async {
    try {
      await _printerService.printEscHtml(item);
      if (mounted) _snackBarHelper.showSuccessSnackbar('ESC/POS HTML sent.');
    } catch (e) {
      _snackBarHelper.showErrorSnackbar('printReceiptHTML error: $e');
    }
  }

  /// Print raw ESC/POS commands
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

  /// Open cash drawer
  Future<void> _openCashDrawer(PrinterItem item) async {
    try {
      await _printerService.openCashDrawer(item);
      if (mounted) {
        _snackBarHelper.showSuccessSnackbar('Cash drawer command sent.');
      }
    } catch (e) {
      _snackBarHelper.showErrorSnackbar('Open cash drawer error: $e');
    }
  }

  /// Set network settings via active connection
  Future<void> _setNetSettingsViaConnection(
      PrinterItem item, NetworkParams settings) async {
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

  /// Configure network settings via UDP broadcast
  Future<void> _configureNetViaUDP(
      PrinterItem item, NetworkParams settings) async {
    final mac = item.connectionParams.networkParams?.macAddress;
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

  /// Show network settings dialog
  Future<void> _showNetworkSettingsDialog(
      {required PrinterItem item, required bool isUdp}) async {
    // Pre-fill with current printer IP if connected and setting via connection
    String? initialIp;
    if (!isUdp &&
        item.connectionParams.connectionType ==
            PosPrinterConnectionType.network) {
      initialIp = item.connectionParams.networkParams?.ipAddress;
    }

    // Create default settings with pre-filled data
    final initialSettings = NetworkParams(
      ipAddress: initialIp ?? '', // Use current IP if available
      mask: '255.255.255.0', // Default or fetch if possible
      gateway: '', // Default or fetch if possible
      dhcp: false, // Default or fetch if possible
    );

    try {
      final settings = await showDialog<NetworkParams>(
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

  Future<void> _checkLanguage(PrinterItem item) async {
    try {
      setState(() {
        item.isBusy = true;
      }); // Update UI to show busy state
      await _printerService.checkPrinterLanguage(item);
      setState(() {
        item.isBusy = false; // Reset busy state after checking
      });
    } catch (e) {
      _snackBarHelper.showErrorSnackbar('Get printer language error: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final isWide = MediaQuery.of(context).size.width > 900;
    return ScaffoldMessenger(
      key: _scaffoldMessengerKey,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('POS Printers Example'),
          actions: [
            if (_connectedPrinters.isNotEmpty)
              IconButton(
                icon: const Icon(Icons.print_outlined),
                tooltip: 'Print test on all connected',
                onPressed: () async {
                  if (mounted) {
                    _snackBarHelper.showSuccessSnackbar('Print jobs sent.');
                  }
                },
              ),
            IconButton(
              icon: const Icon(Icons.refresh),
              tooltip: 'Find Printers',
              onPressed: _isSearching ? null : _findPrinters,
            ),
          ],
        ),
        floatingActionButton: isWide
            ? null
            : FloatingActionButton(
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
        body: LayoutBuilder(
          builder: (context, constraints) {
            if (isWide) {
              return Row(
                children: [
                  Expanded(
                    flex: 1,
                    child: Card(
                      margin: const EdgeInsets.all(12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Padding(
                            padding: const EdgeInsets.all(12.0),
                            child: Text(
                              'Saved Printers (${_connectedPrinters.length})',
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                          ),
                          const Divider(height: 1),
                          Expanded(
                            child: _connectedPrinters.isEmpty
                                ? const Center(child: Text('No saved printers'))
                                : Scrollbar(
                                    controller: _connectedScrollController,
                                    thumbVisibility: true,
                                    child: ListView.builder(
                                      controller: _connectedScrollController,
                                      itemCount: _connectedPrinters.length,
                                      itemBuilder: (context, index) {
                                        final item = _connectedPrinters[index];
                                        return SavedPrinterTile(
                                          item: item,
                                          onDisconnect: () =>
                                              _disconnectPrinter(item),
                                          onGetStatus: () => _getStatus(item),
                                          onSetNetworkSettings: () =>
                                              _showNetworkSettingsDialog(
                                                  item: item, isUdp: false),
                                          onOpenCashDrawer: () =>
                                              _openCashDrawer(item),
                                        );
                                      },
                                    ),
                                  ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const VerticalDivider(width: 1, thickness: 1),
                  Expanded(
                    flex: 1,
                    child: Card(
                      margin: const EdgeInsets.all(12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Padding(
                            padding: const EdgeInsets.all(12.0),
                            child: Text(
                              _isSearching
                                  ? 'Searching for printers...'
                                  : 'Found Printers (${_foundPrinters.length})',
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                          ),
                          const Divider(height: 1),
                          Expanded(
                            child: _foundPrinters.isEmpty && !_isSearching
                                ? const Center(
                                    child: Text('No printers found.'))
                                : Scrollbar(
                                    controller: _foundScrollController,
                                    thumbVisibility: true,
                                    child: ListView.builder(
                                      controller: _foundScrollController,
                                      itemCount: _foundPrinters.length,
                                      itemBuilder: (context, index) {
                                        final item = _foundPrinters[index];
                                        return FoundPrinterTile(
                                          item: item,
                                          onAdd: () => _connectToPrinter(item),
                                          onCheckLanguage: () =>
                                              _checkLanguage(item),
                                          onConfigureUdp: item.connectionParams
                                                      .connectionType ==
                                                  PosPrinterConnectionType
                                                      .network
                                              ? () =>
                                                  _showNetworkSettingsDialog(
                                                      item: item, isUdp: true)
                                              : null,
                                        );
                                      },
                                    ),
                                  ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              );
            } else {
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (_connectedPrinters.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.all(8.0),
                      child: Text(
                          'Saved Printers (${_connectedPrinters.length})',
                          style: Theme.of(context).textTheme.titleMedium),
                    ),
                  if (_connectedPrinters.isNotEmpty)
                    SizedBox(
                      height: 180,
                      child: Scrollbar(
                        controller: _connectedScrollController,
                        thumbVisibility: true,
                        child: ListView.builder(
                          controller: _connectedScrollController,
                          itemCount: _connectedPrinters.length,
                          itemBuilder: (context, index) {
                            final item = _connectedPrinters[index];
                            return SavedPrinterTile(
                              item: item,
                              onDisconnect: () => _disconnectPrinter(item),
                              onGetStatus: () => _getStatus(item),
                              onSetNetworkSettings: () =>
                                  _showNetworkSettingsDialog(
                                      item: item, isUdp: false),
                              onOpenCashDrawer: () => _openCashDrawer(item),
                            );
                          },
                        ),
                      ),
                    ),
                  if (_connectedPrinters.isNotEmpty)
                    const Divider(height: 20, thickness: 1),
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
                            child:
                                Text('No printers found. Tap search button.'))
                        : Scrollbar(
                            controller: _foundScrollController,
                            thumbVisibility: true,
                            child: ListView.builder(
                              controller: _foundScrollController,
                              itemCount: _foundPrinters.length,
                              itemBuilder: (context, index) {
                                final item = _foundPrinters[index];
                                return FoundPrinterTile(
                                  item: item,
                                  onAdd: () => _connectToPrinter(item),
                                  onCheckLanguage: () => _checkLanguage(item),
                                  onConfigureUdp:
                                      item.connectionParams.connectionType ==
                                              PosPrinterConnectionType.network
                                          ? () => _showNetworkSettingsDialog(
                                              item: item, isUdp: true)
                                          : null,
                                );
                              },
                            ),
                          ),
                  ),
                ],
              );
            }
          },
        ),
      ),
    );
  }
}
