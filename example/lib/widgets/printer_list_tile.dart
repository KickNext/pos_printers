import 'package:flutter/material.dart';
import 'package:pos_printers/pos_printers.dart';
import '../models/printer_item.dart';

/// Виджет для отображения информации о принтере в списке
class PrinterListTile extends StatelessWidget {
  /// Данные принтера
  final PrinterItem item;

  /// Флаг подключения (для определения доступных действий)
  final bool isConnected;

  /// Callback для подключения к принтеру
  final Function(PrinterItem) onConnect;

  /// Callback для отключения от принтера
  final Function(PrinterItem) onDisconnect;

  /// Callback для получения статуса принтера
  final Function(PrinterItem) onGetStatus;

  /// Callback для настройки сетевых параметров
  final Function(PrinterItem) onSetNetworkSettings;

  /// Callback для настройки сети через UDP
  final Function(PrinterItem) onConfigureUdp;

  /// Callback для выбора языка принтера этикеток
  final Function(PrinterItem, LabelPrinterLanguage?) onLanguageSelected;

  /// Создаёт виджет [PrinterListTile] для отображения информации о принтере.
  const PrinterListTile({
    super.key,
    required this.item,
    required this.isConnected,
    required this.onConnect,
    required this.onDisconnect,
    required this.onGetStatus,
    required this.onSetNetworkSettings,
    required this.onConfigureUdp,
    required this.onLanguageSelected,
  });

  @override
  Widget build(BuildContext context) {
    final bool isNetwork =
        item.discoveredPrinter.type == PosPrinterConnectionType.network;
    final bool hasMAC =
        item.discoveredPrinter.networkParams?.macAddress?.isNotEmpty ?? false;

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 8.0, vertical: 4.0),
      elevation: isConnected ? 2.0 : 1.0,
      color: isConnected ? Colors.blue.shade50 : null,
      child: Padding(
        padding: const EdgeInsets.all(8.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Заголовок с типом принтера и основной информацией
            Row(
              children: [
                Icon(
                  isNetwork ? Icons.wifi : Icons.usb,
                  color: isConnected ? Colors.green : Colors.grey,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        item.discoveredPrinter.type ==
                                PosPrinterConnectionType.usb
                            ? 'USB Printer'
                            : 'Network Printer',
                        style: const TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 16,
                        ),
                      ),
                      Text(
                        'ID: ${item.discoveredPrinter.id}',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      if (item.discoveredPrinter.usbParams?.manufacturer !=
                          null)
                        Text(
                          'Manufacturer: ${item.discoveredPrinter.usbParams?..manufacturer}',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                    ],
                  ),
                ),
                if (isConnected)
                  IconButton(
                    icon: const Icon(Icons.close),
                    tooltip: 'Disconnect',
                    onPressed: () => onDisconnect(item),
                  )
                else
                  IconButton(
                    icon: const Icon(Icons.link),
                    tooltip: 'Connect',
                    onPressed: () => onConnect(item),
                  ),
              ],
            ),

            const Divider(),

            // Специфические для принтера этикеток опции
            if (item.isLabelPrinter)
              _buildLabelPrinterOptions(context),

            // Опции настройки сети (только для сетевых принтеров)
            if (isNetwork && isConnected) _buildNetworkSettingsOption(context),

            // Опция настройки через UDP (если есть MAC-адрес)
            if (isNetwork && hasMAC && !isConnected)
              ListTile(
                leading: const Icon(Icons.settings_ethernet),
                title: const Text('Configure via UDP'),
                dense: true,
                onTap: () => onConfigureUdp(item),
              ),

            // Статус для подключенных принтеров
            if (isConnected)
              ListTile(
                leading: const Icon(Icons.info_outline),
                title: const Text('Get Status'),
                dense: true,
                onTap: () => onGetStatus(item),
              ),
          ],
        ),
      ),
    );
  }

  /// Строит виджеты для опций принтера этикеток
  Widget _buildLabelPrinterOptions(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Выпадающий список для выбора языка принтера
        Row(
          children: [
            const Text('Printer Language:'),
            const SizedBox(width: 8),
            DropdownButton<LabelPrinterLanguage>(
              value: item.language,
              hint: const Text('Select language'),
              onChanged: (LabelPrinterLanguage? newValue) {
                onLanguageSelected(item, newValue);
              },
              items: LabelPrinterLanguage.values
                  .map<DropdownMenuItem<LabelPrinterLanguage>>(
                      (LabelPrinterLanguage value) {
                return DropdownMenuItem<LabelPrinterLanguage>(
                  value: value,
                  child: Text(value.name.toUpperCase()),
                );
              }).toList(),
            ),
          ],
        ),
      ],
    );
  }

  /// Строит виджет для настройки сетевых параметров
  Widget _buildNetworkSettingsOption(BuildContext context) {
    return ListTile(
      leading: const Icon(Icons.settings),
      title: const Text('Network Settings'),
      dense: true,
      onTap: () => onSetNetworkSettings(item),
    );
  }
}
