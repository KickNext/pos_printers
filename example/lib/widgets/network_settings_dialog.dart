import 'package:flutter/material.dart';
import 'package:pos_printers/pos_printers.dart';

/// Диалог для настройки сетевых параметров принтера
class NetworkSettingsDialog extends StatefulWidget {
  /// Начальные настройки для заполнения полей формы
  final NetSettingsDTO initialSettings;

  /// Создаёт диалог для настройки сетевых параметров принтера
  const NetworkSettingsDialog({
    super.key,
    required this.initialSettings,
  });

  @override
  State<NetworkSettingsDialog> createState() => _NetworkSettingsDialogState();
}

class _NetworkSettingsDialogState extends State<NetworkSettingsDialog> {
  /// Контроллеры для текстовых полей формы
  late final TextEditingController _ipController;
  late final TextEditingController _maskController;
  late final TextEditingController _gatewayController;

  /// Флаг использования DHCP (автоматическое получение IP)
  bool _useDhcp = false;

  /// Глобальный ключ для формы (используется для валидации)
  final _formKey = GlobalKey<FormState>();

  @override
  void initState() {
    super.initState();

    // Инициализация контроллеров начальными значениями
    _ipController =
        TextEditingController(text: widget.initialSettings.ipAddress);
    _maskController = TextEditingController(text: widget.initialSettings.mask);
    _gatewayController =
        TextEditingController(text: widget.initialSettings.gateway);
    _useDhcp = widget.initialSettings.dhcp;
  }

  @override
  void dispose() {
    // Очистка контроллеров при выходе
    _ipController.dispose();
    _maskController.dispose();
    _gatewayController.dispose();
    super.dispose();
  }

  /// Проверяет, является ли строка валидным IP-адресом
  bool _isValidIpAddress(String value) {
    if (value.isEmpty) {
      return false;
    }

    final parts = value.split('.');
    if (parts.length != 4) {
      return false;
    }

    for (final part in parts) {
      try {
        final num = int.parse(part);
        if (num < 0 || num > 255) {
          return false;
        }
      } catch (e) {
        return false;
      }
    }

    return true;
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Network Settings'),
      content: Form(
        key: _formKey,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Опция использования DHCP
              CheckboxListTile(
                title: const Text('Use DHCP'),
                value: _useDhcp,
                onChanged: (bool? value) {
                  if (value != null) {
                    setState(() {
                      _useDhcp = value;
                    });
                  }
                },
                subtitle: const Text('Automatically obtain network settings'),
                controlAffinity: ListTileControlAffinity.leading,
                contentPadding: EdgeInsets.zero,
              ),

              // Настройки статического IP (если не используется DHCP)
              if (!_useDhcp) ...[
                const SizedBox(height: 16),
                const Text('Static IP Configuration',
                    style: TextStyle(fontWeight: FontWeight.bold)),
                const SizedBox(height: 8),

                // Поле для IP-адреса
                TextFormField(
                  controller: _ipController,
                  decoration: const InputDecoration(
                    labelText: 'IP Address',
                    hintText: '192.168.1.100',
                    border: OutlineInputBorder(),
                  ),
                  validator: (value) {
                    if (value == null || value.isEmpty) {
                      return 'Please enter an IP address';
                    }
                    if (!_isValidIpAddress(value)) {
                      return 'Please enter a valid IP address';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 8),

                // Поле для маски подсети
                TextFormField(
                  controller: _maskController,
                  decoration: const InputDecoration(
                    labelText: 'Subnet Mask',
                    hintText: '255.255.255.0',
                    border: OutlineInputBorder(),
                  ),
                  validator: (value) {
                    if (value == null || value.isEmpty) {
                      return 'Please enter a subnet mask';
                    }
                    if (!_isValidIpAddress(value)) {
                      return 'Please enter a valid subnet mask';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 8),

                // Поле для шлюза по умолчанию
                TextFormField(
                  controller: _gatewayController,
                  decoration: const InputDecoration(
                    labelText: 'Default Gateway (optional)',
                    hintText: '192.168.1.1',
                    border: OutlineInputBorder(),
                  ),
                  validator: (value) {
                    if (value != null &&
                        value.isNotEmpty &&
                        !_isValidIpAddress(value)) {
                      return 'Please enter a valid gateway or leave empty';
                    }
                    return null;
                  },
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        // Кнопка отмены
        TextButton(
          onPressed: () {
            Navigator.of(context).pop();
          },
          child: const Text('Cancel'),
        ),

        // Кнопка сохранения
        ElevatedButton(
          onPressed: () {
            if (_useDhcp || _formKey.currentState!.validate()) {
              // Создаем DTO с настройками и возвращаем его
              final settings = NetSettingsDTO(
                ipAddress: _ipController.text,
                mask: _maskController.text,
                gateway: _gatewayController.text,
                dhcp: _useDhcp,
              );
              Navigator.of(context).pop(settings);
            }
          },
          child: const Text('Save'),
        ),
      ],
    );
  }
}
