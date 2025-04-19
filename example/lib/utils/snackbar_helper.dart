import 'package:flutter/material.dart';

/// Helper functions for showing Snackbar notifications
class SnackBarHelper {
  final GlobalKey<ScaffoldMessengerState> _scaffoldMessengerKey;

  SnackBarHelper(this._scaffoldMessengerKey);

  /// Shows an informational message
  void showInfoSnackbar(String message) =>
      _showSnackbar(message, backgroundColor: Colors.blueGrey);

  /// Shows a success message
  void showSuccessSnackbar(String message) =>
      _showSnackbar(message, backgroundColor: Colors.green);

  /// Shows an error message
  void showErrorSnackbar(String message) =>
      _showSnackbar(message, backgroundColor: Colors.red);

  /// Base method for displaying a Snackbar with a given style
  void _showSnackbar(String message,
      {Color? backgroundColor,
      Duration duration = const Duration(seconds: 3)}) {
    if (_scaffoldMessengerKey.currentState == null) return;

    _scaffoldMessengerKey.currentState?.hideCurrentSnackBar();
    _scaffoldMessengerKey.currentState?.showSnackBar(SnackBar(
      content: Text(message),
      backgroundColor: backgroundColor,
      duration: duration,
    ));
  }
}
