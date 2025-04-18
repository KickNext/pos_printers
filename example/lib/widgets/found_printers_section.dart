import 'package:flutter/material.dart';
import 'package:pos_printers/pos_printers.dart';
import '../models/printer_item.dart';
import 'printer_list_tile.dart'; // Import the common tile

/// Displays the list of found (discovered) printers.
class FoundPrintersSection extends StatelessWidget {
  final List<PrinterItem> foundPrinters;
  final bool isSearching;
  final Function(PrinterItem) onConnect;
  final Function(PrinterItem) onConfigureUdp;
  final Function(PrinterItem, LabelPrinterLanguage?) onLanguageSelected;

  const FoundPrintersSection({
    super.key,
    required this.foundPrinters,
    required this.isSearching,
    required this.onConnect,
    required this.onConfigureUdp,
    required this.onLanguageSelected,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.all(8.0),
          child: Text(
              isSearching ? 'Searching...' : 'Found Printers (${foundPrinters.length})',
              style: Theme.of(context).textTheme.titleMedium),
        ),
        Expanded(
          child: foundPrinters.isEmpty && !isSearching
              ? const Center(child: Text('No printers found. Tap search button.'))
              : ListView.builder(
                  itemCount: foundPrinters.length,
                  itemBuilder: (context, index) {
                    final item = foundPrinters[index];
                    return PrinterListTile(
                      item: item,
                      isConnected: false,
                      onConnect: onConnect,
                      onDisconnect: (_) {}, // Not applicable
                      onGetStatus: (_) {}, // Not applicable
                      onSetNetworkSettings: (_) {}, // Not applicable
                      onConfigureUdp: onConfigureUdp,
                      onLanguageSelected: onLanguageSelected,
                    );
                  },
                ),
        ),
      ],
    );
  }
}