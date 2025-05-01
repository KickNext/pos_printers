import 'package:flutter/material.dart';
import '../models/printer_item.dart';
import 'printer_list_tile.dart';

/// Displays the list of found (discovered) printers.
class FoundPrintersSection extends StatelessWidget {
  final List<PrinterItem> foundPrinters;
  final bool isSearching;
  final Function(PrinterItem) onConnect;
  final Function(PrinterItem) onConfigureUdp;
  final Function(PrinterItem) onCheckLanguage;

  const FoundPrintersSection({
    super.key,
    required this.foundPrinters,
    required this.isSearching,
    required this.onConnect,
    required this.onConfigureUdp,
    required this.onCheckLanguage,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.all(8.0),
          child: Text(
              isSearching
                  ? 'Searching...'
                  : 'Found Printers (${foundPrinters.length})',
              style: Theme.of(context).textTheme.titleMedium),
        ),
        Expanded(
          child: foundPrinters.isEmpty && !isSearching
              ? const Center(
                  child: Text('No printers found. Tap search button.'))
              : ListView.builder(
                  itemCount: foundPrinters.length,
                  itemBuilder: (context, index) {
                    final item = foundPrinters[index];
                    return FoundPrinterTile(
                      item: item,
                      onAdd: () => onConnect(item),
                      onConfigureUdp: () => onConfigureUdp(item),
                      onCheckLanguage: () => onCheckLanguage(item),
                    );
                  },
                ),
        ),
      ],
    );
  }
}
