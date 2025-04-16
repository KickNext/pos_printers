import 'package:flutter/material.dart';

/// Вспомогательные функции для показа уведомлений Snackbar
class SnackBarHelper {
  final GlobalKey<ScaffoldMessengerState> _scaffoldMessengerKey;

  SnackBarHelper(this._scaffoldMessengerKey);

  /// Показывает информационное сообщение
  void showInfoSnackbar(String message) =>
      _showSnackbar(message, backgroundColor: Colors.blueGrey);

  /// Показывает сообщение об успешной операции
  void showSuccessSnackbar(String message) =>
      _showSnackbar(message, backgroundColor: Colors.green);

  /// Показывает сообщение об ошибке
  void showErrorSnackbar(String message) =>
      _showSnackbar(message, backgroundColor: Colors.red);

  /// Базовый метод для отображения Snackbar с заданным стилем
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
