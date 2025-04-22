import 'package:flutter/material.dart';
import '../models/printer_item.dart';
import 'printer_list_tile.dart';

/// Displays the list of saved (connected) printers.
class ConnectedPrintersSection extends StatelessWidget {
  final List<PrinterItem> connectedPrinters;
  final Function(PrinterItem) onDisconnect;
  final Function(PrinterItem) onGetStatus;
  final Function(PrinterItem) onSetNetworkSettings;
  final Function(PrinterItem) onLanguageSelected;
  final Function(PrinterItem) onPrintEscHtml;
  final Function(PrinterItem) onPrintEscPosData;
  final Function(PrinterItem) onPrintLabelRaw;
  final Function(PrinterItem) onPrintLabelHtml;
  final Function(PrinterItem) onSetupLabelParams;

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
      return const SizedBox.shrink();
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.all(8.0),
          child: Text('Saved Printers (${connectedPrinters.length})',
              style: Theme.of(context).textTheme.titleMedium),
        ),
        ListView.builder(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          itemCount: connectedPrinters.length,
          itemBuilder: (context, index) {
            final item = connectedPrinters[index];
            return SavedPrinterTile(
              item: item,
              onDisconnect: () => onDisconnect(item),
              onGetStatus: () => onGetStatus(item),
              onSetNetworkSettings: () => onSetNetworkSettings(item),
            );
          },
        ),
        const Divider(height: 20, thickness: 1),
      ],
    );
  }
}
