import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_printers/pos_printers.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('renderHtmlBitmap forwards HTML options and returns PNG bytes',
      () async {
    const channel = BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.pos_printers.POSPrintersApi.renderHtmlBitmap',
      POSPrintersApi.pigeonChannelCodec,
    );
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    addTearDown(() => messenger.setMockDecodedMessageHandler(channel, null));
    messenger.setMockDecodedMessageHandler(channel, (message) async {
      expect(message, <Object?>['<b>Receipt</b>', 576, true]);
      return <Object?>[
        Uint8List.fromList(<int>[137, 80, 78, 71])
      ];
    });

    final manager = PosPrintersManager();
    addTearDown(manager.dispose);

    expect(
      await manager.renderHtmlBitmap(
        '<b>Receipt</b>',
        576,
        upsideDown: true,
      ),
      Uint8List.fromList(<int>[137, 80, 78, 71]),
    );
  });
}
