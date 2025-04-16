// ignore_for_file: avoid_print

import 'dart:io';

Future<void> main() async {
  await _generateExportFile(
    sourceDir: 'lib/src/',
    exportFile: 'lib/pos_printers.dart',
  );
}

/// Generates an export file that includes all Dart files in the specified source directory.
///
/// This function scans the given `sourceDir` for all Dart files, excluding those that contain
/// the 'part of' directive. It then generates an export file at the specified `exportFile` path,
/// which includes export statements for each of the found Dart files.
///
/// The generated export file will contain a comment indicating that it was auto-generated.
///
/// Parameters:
/// - `sourceDir`: The directory to scan for Dart files.
/// - `exportFile`: The path where the export file will be generated.
///
/// Example usage:
/// ```dart
/// await _generateExportFile(sourceDir: 'lib/src', exportFile: 'lib/plugin_name.dart');
/// ```
///
/// Note: This function uses synchronous file operations and should be called within an asynchronous context.
Future<void> _generateExportFile({required String sourceDir, required String exportFile}) async {
  print('Generating exports for files in $sourceDir...');

  final dir = Directory(sourceDir);
  final files = dir.listSync(recursive: true).whereType<File>().where((file) => file.path.endsWith('.dart'));

  final exportLines = <String>[];

  for (final file in files) {
    final content = file.readAsStringSync();
    if (!content.contains('part of')) {
      final relativePath = file.path.replaceAll('\\', '/').replaceFirst('lib/', '');
      exportLines.add("export '$relativePath';");
    }
  }

  final exportContent = exportLines.join('\n');
  File(exportFile).writeAsStringSync('// Auto-generated exports\n\n$exportContent\n');

  print('Exports written to $exportFile');
}
