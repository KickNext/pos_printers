import 'package:flutter/material.dart';
import 'app/printer_app.dart';

/// Example app that:
/// 1) Searches for available printers (USB, Network),
/// 2) Allows connecting to multiple printers,
/// 3) Lets you specify type/language (ESC/POS, CPCL, TSPL, ZPL),
/// 4) Prints both receipt and label commands,
/// 5) Allows configuring network settings and inverted print mode.
void main() {
  WidgetsFlutterBinding.ensureInitialized(); // Ensure initialization
  runApp(const PrintersApp());
}
