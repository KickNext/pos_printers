import 'dart:typed_data';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_printers/pos_printers.dart';

PrinterConnectionParamsDTO _printer() => PrinterConnectionParamsDTO(
      id: '192.168.1.50',
      connectionType: PosPrinterConnectionType.network,
      usbParams: null,
      networkParams: NetworkParams(
        ipAddress: '192.168.1.50',
        mask: null,
        gateway: null,
        macAddress: null,
        dhcp: null,
        modelName: null,
      ),
    );

BasicMessageChannel<Object?> _channel(String method) =>
    BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.pos_printers.POSPrintersApi.$method',
      POSPrintersApi.pigeonChannelCodec,
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  late PosPrintersManager manager;
  final calls = <String>[];

  void mock(String method, Object? Function(Object? message) reply) {
    final channel = _channel(method);
    messenger.setMockDecodedMessageHandler(channel, (message) async {
      calls.add(method);
      return reply(message);
    });
    addTearDown(() => messenger.setMockDecodedMessageHandler(channel, null));
  }

  setUp(() {
    calls.clear();
    manager = PosPrintersManager();
    addTearDown(manager.dispose);
  });

  group('PrintOptions', () {
    test('default is the legacy mode', () {
      const options = PrintOptions();
      expect(options.strict, isFalse);
    });

    test('strict() has bounded defaults and converts to the DTO', () {
      final dto = const PrintOptions.strict().toDto();
      expect(const PrintOptions.strict().strict, isTrue);
      expect(dto.connectAttempts, 3);
      expect(dto.connectBudgetMs, 10000);
      expect(dto.jobTimeoutMs, 20000);
      expect(dto.statusTimeoutMs, 3000);
      expect(dto.checkStatusBefore, isTrue);
      expect(dto.checkStatusAfter, isTrue);
      expect(dto.refuseOnUnknownStatus, isFalse);
      expect(dto.postStatusDelayMs, 300);
    });

    test('strict() takes custom limits', () {
      final dto = const PrintOptions.strict(
        connectAttempts: 2,
        jobTimeout: Duration(seconds: 5),
        refuseOnUnknownStatus: true,
      ).toDto();
      expect(dto.connectAttempts, 2);
      expect(dto.jobTimeoutMs, 5000);
      expect(dto.refuseOnUnknownStatus, isTrue);
    });
  });

  group('outcome enum', () {
    test('has the documented values in a stable order', () {
      expect(PrinterOutcome.values.map((e) => e.name), <String>[
        'ready',
        'noPaper',
        'coverOpen',
        'offline',
        'timeout',
        'error',
        'unknown',
      ]);
    });
  });

  group('legacy mode (default options)', () {
    test('printEscRawDataWithOutcome uses the old method only', () async {
      mock('printData', (message) {
        final args = message! as List<Object?>;
        expect(args[2], 576);
        expect(args[3], isTrue);
        return <Object?>[];
      });

      final result = await manager.printEscRawDataWithOutcome(
        _printer(),
        Uint8List.fromList(<int>[1, 2, 3]),
        576,
        upsideDown: true,
      );

      expect(calls, <String>['printData']);
      expect(result.strict, isFalse);
      expect(result.sent, isTrue);
      expect(result.outcome, PrinterOutcome.unknown);
    });

    test('legacy failures still throw', () async {
      mock('printHTML', (_) => <Object?>['Exception', 'boom', null]);
      expect(
        manager.printEscHTMLWithOutcome(_printer(), '<b>x</b>', 576),
        throwsA(isA<PlatformException>()),
      );
    });

    test('openCashBoxWithOutcome uses openCashBox', () async {
      mock('openCashBox', (_) => <Object?>[]);
      final result = await manager.openCashBoxWithOutcome(_printer());
      expect(calls, <String>['openCashBox']);
      expect(result.strict, isFalse);
    });
  });

  group('strict mode', () {
    PrintJobResultDTO job(PrinterOutcome outcome,
            {bool sent = false, PrintStage stage = PrintStage.completed}) =>
        PrintJobResultDTO(
          outcome: outcome,
          sent: sent,
          stage: stage,
          statusBefore: 16,
          statusAfter: null,
          bytesSent: sent ? 10 : 0,
          connectAttempts: 1,
          elapsedMs: 42,
          message: 'm',
        );

    test('printEscRawDataWithOutcome returns the refusal outcome', () async {
      mock('printDataStrict', (message) {
        final args = message! as List<Object?>;
        final options = args[4] as StrictPrintOptionsDTO;
        expect(options.connectAttempts, 3);
        return <Object?>[
          job(PrinterOutcome.coverOpen, stage: PrintStage.preStatus)
        ];
      });

      final result = await manager.printEscRawDataWithOutcome(
        _printer(),
        Uint8List.fromList(<int>[1]),
        576,
        options: const PrintOptions.strict(),
      );

      expect(calls, <String>['printDataStrict']);
      expect(result.strict, isTrue);
      expect(result.outcome, PrinterOutcome.coverOpen);
      expect(result.sent, isFalse);
      expect(result.stage, PrintStage.preStatus);
      expect(result.statusBefore, 16);
      expect(result.elapsed, const Duration(milliseconds: 42));
      expect(result.isReady, isFalse);
    });

    test('printEscHTMLWithOutcome reports a sent job', () async {
      mock('printHTMLStrict',
          (_) => <Object?>[job(PrinterOutcome.ready, sent: true)]);
      final result = await manager.printEscHTMLWithOutcome(
        _printer(),
        '<b>x</b>',
        576,
        options: const PrintOptions.strict(),
      );
      expect(result.isReady, isTrue);
      expect(result.sent, isTrue);
      expect(result.bytesSent, 10);
    });

    test('openCashBoxWithOutcome returns the write error', () async {
      mock('openCashBoxStrict',
          (_) => <Object?>[job(PrinterOutcome.error, stage: PrintStage.write)]);
      final result = await manager.openCashBoxWithOutcome(
        _printer(),
        options: const PrintOptions.strict(),
      );
      expect(result.outcome, PrinterOutcome.error);
      expect(result.stage, PrintStage.write);
    });

    test('getPrinterStatusStrict returns the real code', () async {
      mock(
          'getPrinterStatusStrict',
          (_) => <Object?>[
                PrinterStatusDTO(
                  outcome: PrinterOutcome.coverOpen,
                  statusCode: 16,
                  message: 'Cover open',
                  connectAttempts: 1,
                  elapsedMs: 10,
                )
              ]);
      final status = await manager.getPrinterStatusStrict(_printer());
      expect(status.outcome, PrinterOutcome.coverOpen);
      expect(status.statusCode, 16);
    });
  });
}
