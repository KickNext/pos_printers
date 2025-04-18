import 'package:flutter/material.dart';
import 'package:pos_printers/pos_printers.dart';
import '../models/printer_item.dart';
import 'printer_list_tile.dart'; // Import the common tile

/// Displays the list of connected printers.
class ConnectedPrintersSection extends StatelessWidget {
  final List<PrinterItem> connectedPrinters;
  final Function(PrinterItem) onDisconnect;
  final Function(PrinterItem) onGetStatus;
  final Function(PrinterItem) onSetNetworkSettings;
  final Function(PrinterItem, LabelPrinterLanguage?) onLanguageSelected;
  final Function(PrinterItem) onPrintEscHtml; // Callback for printing ESC/POS HTML
  final Function(PrinterItem) onPrintEscPosData; // Callback for printing ESC/POS Raw
  final Function(PrinterItem) onPrintLabelRaw; // Callback for printing Label Raw
  final Function(PrinterItem) onPrintLabelHtml; // Callback for printing Label HTML
  final Function(PrinterItem) onSetupLabelParams; // Callback for setting label params

  const ConnectedPrintersSection({
    super.key,
    required this.connectedPrinters,
    required this.onDisconnect,
    required this.onGetStatus,
    required this.onSetNetworkSettings,
    required this.onLanguageSelected,
    required this.onPrintEscHtml,
    required this.onPrintEscPosData,
    required this.onPrintLabelRaw,
    required this.onPrintLabelHtml,
    required this.onSetupLabelParams,
  });

  @override
  Widget build(BuildContext context) {
    if (connectedPrinters.isEmpty) {
      return const SizedBox.shrink(); // Don't show anything if empty
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.all(8.0),
          child: Text('Connected Printers (${connectedPrinters.length})',
              style: Theme.of(context).textTheme.titleMedium),
        ),
        ListView.builder(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          itemCount: connectedPrinters.length,
          itemBuilder: (context, index) {
            final item = connectedPrinters[index];
            return PrinterListTile(
              item: item,
              isConnected: true,
              onConnect: (_) {}, // Already connected
              onDisconnect: onDisconnect,
              onGetStatus: onGetStatus,
              onSetNetworkSettings: onSetNetworkSettings,
              onConfigureUdp: (_) {}, // Not applicable here
              onLanguageSelected: onLanguageSelected,
              // Add print actions specific to connected printers if needed
              // Example: Add a print button directly here
              // trailingActions: [
              //    IconButton(icon: Icon(Icons.print), onPressed: () => _handlePrint(item)),
              // ]
            );
          },
        ),
        const Divider(height: 20, thickness: 1),
      ],
    );
  }

  // Example of how print actions could be handled if added to the tile directly
  // void _handlePrint(PrinterItem item) {
  //   if (item.isLabelPrinter) {
  //     if (item.language != null) {
  //       onSetupLabelParams(item); // Setup before print
  //       onPrintLabelHtml(item);
  //       // Maybe add raw print too?
  //     }
  //   } else {
  //     onPrintEscHtml(item);
  //     // Maybe add raw print too?
  //   }
  // }
}