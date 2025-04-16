import 'package:flutter/material.dart';
import 'app/printer_app.dart';

/// Пример приложения, которое:
/// 1) Ищет доступные принтеры (USB, Network),
/// 2) Позволяет подключаться к нескольким принтерам,
/// 3) Даёт возможность указать тип/язык (ESC/POS, CPCL, TSPL, ZPL),
/// 4) Печатает и чековые команды, и лейбл-команды,
/// 5) Позволяет настраивать сетевые параметры и режим перевернутой печати.
void main() {
  WidgetsFlutterBinding.ensureInitialized(); // Ensure initialization
  runApp(const PrintersApp());
}
