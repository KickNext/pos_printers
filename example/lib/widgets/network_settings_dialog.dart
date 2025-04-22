import 'package:flutter/material.dart';
import 'package:pos_printers/pos_printers.dart';

/// Dialog for configuring printer network settings
class NetworkSettingsDialog extends StatefulWidget {
  /// Initial settings to prefill the form fields
  final NetworkParams initialSettings;

  /// Creates a dialog for configuring printer network settings
  const NetworkSettingsDialog({
    super.key,
    required this.initialSettings,
  });

  @override
  State<NetworkSettingsDialog> createState() => _NetworkSettingsDialogState();
}

class _NetworkSettingsDialogState extends State<NetworkSettingsDialog> {
  /// Controllers for form text fields
  late final TextEditingController _ipController;
  late final TextEditingController _maskController;
  late final TextEditingController _gatewayController;

  /// DHCP usage flag (automatic IP assignment)
  bool _useDhcp = false;

  /// Global key for the form (used for validation)
  final _formKey = GlobalKey<FormState>();

  @override
  void initState() {
    super.initState();

    // Initialize controllers with initial values
    _ipController =
        TextEditingController(text: widget.initialSettings.ipAddress);
    _maskController = TextEditingController(text: widget.initialSettings.mask);
    _gatewayController =
        TextEditingController(text: widget.initialSettings.gateway);
    _useDhcp = widget.initialSettings.dhcp ?? false;
  }

  @override
  void dispose() {
    // Dispose controllers on exit
    _ipController.dispose();
    _maskController.dispose();
    _gatewayController.dispose();
    super.dispose();
  }

  /// Checks if a string is a valid IP address
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
              // DHCP option
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

              // Static IP configuration (if DHCP is not used)
              if (!_useDhcp) ...[
                const SizedBox(height: 16),
                const Text('Static IP Configuration',
                    style: TextStyle(fontWeight: FontWeight.bold)),
                const SizedBox(height: 8),

                // IP address field
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

                // Subnet mask field
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

                // Default gateway field
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
        // Cancel button
        TextButton(
          onPressed: () {
            Navigator.of(context).pop();
          },
          child: const Text('Cancel'),
        ),

        // Save button
        ElevatedButton(
          onPressed: () {
            if (_useDhcp || _formKey.currentState!.validate()) {
              // Create DTO with settings and return it
              final settings = NetworkParams(
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
