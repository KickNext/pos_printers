import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import '../screens/home_screen.dart';

/// Root application providing MaterialApp with proper localization
class PrintersApp extends StatelessWidget {
  const PrintersApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'POS Printers Demo',
      theme: ThemeData(
        primarySwatch: Colors.blue,
        visualDensity: VisualDensity.adaptivePlatformDensity,
      ),
      debugShowCheckedModeBanner: false,
      // Add proper localization support
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: const [
        Locale('ru'), // Russian
        Locale('en'), // English
      ],
      // The actual app content is in HomeScreen widget
      home: const HomeScreen(),
    );
  }
}
