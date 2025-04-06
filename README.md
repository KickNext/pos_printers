# pos_printers

Flutter плагин для работы с POS-принтерами (чековыми ESC/POS и этикеточными) через USB и Network на Android. Использует нативный SDK Xprinter и предоставляет унифицированный API для Dart.

## Возможности

*   **Обнаружение принтеров:**
    *   Поиск USB-принтеров.
    *   Поиск сетевых принтеров (UDP broadcast). **Примечание:** Обнаружение по сети наиболее надежно работает с принтерами Xprinter из-за ограничений используемого SDK. Для сторонних сетевых принтеров рекомендуется использовать прямое подключение по IP-адресу.
*   **Подключение/Отключение:** Управление подключениями к нескольким принтерам одновременно.
*   **Печать:**
    *   **ESC/POS (Чековые принтеры):**
        *   Печать HTML (рендерится в Bitmap на стороне Android).
        *   Отправка сырых ESC/POS команд (`Uint8List`).
    *   **Label (Этикеточные принтеры):**
        *   Поддержка языков: **CPCL, TSPL, ZPL**.
        *   Печать HTML (рендерится в Bitmap).
        *   Отправка сырых команд (`Uint8List`) для выбранного языка.
*   **Управление:**
    *   Получение статуса принтера.
    *   Получение серийного номера принтера.
    *   Открытие денежного ящика.
    *   Настройка параметров этикетки (размер, скорость, плотность) для CPCL, TSPL, ZPL.
    *   Настройка сетевых параметров принтера (IP, маска, шлюз, DHCP).
*   **Получение деталей принтера:**
    *   Метод `getPrinterDetails` для получения SN и статуса принтера в одном DTO (`PrinterDetailsDTO`).
    *   Объект `PrinterConnectionParams` для USB-принтеров теперь содержит дополнительные поля для идентификации: `vendorId`, `productId`, `manufacturer`, `productName`, `usbSerialNumber` (если доступны и есть разрешение на доступ к USB).
*   **Обработка событий:**
    *   Поток `connectionEvents` для отслеживания статусов подключения, отключения, а также событий **отключения (`USB detached`)** и **подключения (`USB attached`)** USB-устройств. Ваше приложение должно обрабатывать эти события для обновления UI и управления состоянием (например, удалять отключенный принтер из списка или предлагать переподключиться к новому USB-пути, используя VID/PID/SN для идентификации).

## Начало работы

### 1. Добавление зависимости

Добавьте плагин в ваш `pubspec.yaml`:

```yaml
dependencies:
  pos_printers: ^latest_version # Замените на актуальную версию
```

### 2. Использование

Рекомендуется использовать класс `PosPrintersManager` для взаимодействия с плагином.

```dart
import 'package:pos_printers/pos_printers.dart';
import 'package:pos_printers/pos_printers.pigeon.dart'; // Для DTO и Enums

// Создаем экземпляр менеджера (лучше делать это в StatefulWidget или через DI)
final PosPrintersManager printerManager = PosPrintersManager();

// --- Поиск принтеров ---
void findAvailablePrinters() {
  print('Starting printer search...');
  printerManager.findPrinters().listen(
    (PrinterConnectionParams printer) {
      print('Found printer: ${printer.connectionType.name} - ${printer.usbPath ?? printer.ipAddress}');
      // Добавляем принтер в список UI
    },
    onDone: () => print('Printer search finished.'),
    onError: (error) => print('Printer search error: $error'),
  );
}

// --- Подключение к принтеру ---
Future<void> connect(PrinterConnectionParams printerParams) async {
  try {
    final result = await printerManager.connectPrinter(printerParams);
    if (result.success) {
      print('Connected successfully to ${printerParams.usbPath ?? printerParams.ipAddress}');
      // Сохраняем подключенный принтер
    } else {
      print('Connection failed: ${result.message}');
    }
  } catch (e) {
    print('Connection error: $e');
  }
}

// --- Печать чека (ESC/POS) ---
Future<void> printReceipt(PrinterConnectionParams printerParams) async {
  try {
    // Печать HTML
    await printerManager.printReceiptHTML(
      printerParams,
      '<h1>Test Receipt</h1><p>Total: \$10.00</p>',
      576, // Ширина для 80мм бумаги (203 dpi)
    );

    // Печать сырых команд
    List<int> commands = [0x1B, 0x40]; // Initialize printer
    commands.addAll('Raw ESC/POS text\n'.codeUnits);
    commands.addAll([0x1D, 0x56, 0x41, 0x10]); // Partial cut
    await printerManager.printReceiptData(printerParams, Uint8List.fromList(commands), 576);

  } catch (e) {
    print('Receipt printing error: $e');
  }
}

// --- Печать этикетки (TSPL) ---
Future<void> printLabel(PrinterConnectionParams printerParams) async {
  try {
    // Установка параметров (пример для TSPL)
    await printerManager.setupLabelParams(
      printerParams,
      LabelPrinterLanguage.tspl,
      58, // Ширина мм
      40, // Высота мм
      15, // Плотность
      4,  // Скорость
    );

    // Печать сырых команд TSPL
    String tsplCommands = "SIZE 58 mm, 40 mm\r\nCLS\r\nTEXT 50,50,\"ROMAN.TTF\",0,12,12,\"Hello TSPL\"\r\nPRINT 1,1\r\n";
    await printerManager.printLabelData(
      printerParams,
      LabelPrinterLanguage.tspl,
      Uint8List.fromList(tsplCommands.codeUnits),
      464, // Ширина в точках (может игнорироваться)
    );

  } catch (e) {
    print('Label printing error: $e');
  }
}

// --- Получение деталей ---
Future<void> getDetails(PrinterConnectionParams printerParams) async {
   try {
     final details = await printerManager.getPrinterDetails(printerParams);
     print('Printer Details:');
     print('  SN: ${details.serialNumber ?? 'N/A'}');
     print('  Status: ${details.currentStatus ?? 'N/A'}');
     // Модель и прошивка обычно null для этого SDK
     print('  Model: ${details.deviceModel ?? 'N/A'}');
     print('  Firmware: ${details.firmwareVersion ?? 'N/A'}');
   } catch (e) {
     print('Get details error: $e');
   }
}


// --- Обработка событий подключения ---
void listenToConnectionEvents() {
  printerManager.connectionEvents.listen((event) {
    print('Connection Event: success=${event.success}, message=${event.message}');
    if (event.message?.contains('USB detached') ?? false) {
      // Извлекаем путь отключенного USB
      final path = event.message!.split(':').last.trim();
      print('USB Printer detached: $path');
      // Удаляем принтер с этим путем из списка подключенных в UI
    } else if (event.message?.contains('USB attached') ?? false) {
      print('USB device attached. Rescanning might be needed or new printers might appear.');
      // Можно инициировать новый поиск или обновить UI
    }
    // Обработка других сообщений (успешное подключение, ошибки)
  });
}

// Не забудьте вызвать dispose для менеджера, когда он больше не нужен
// printerManager.dispose();

```

### 3. Пример приложения

Более полный пример использования находится в директории `example`. Он демонстрирует поиск, подключение к нескольким принтерам, выбор языка для этикеточных принтеров и различные виды печати.

## Генерация кода Pigeon

Если вы вносите изменения в определения в `pigeons/pos_printers.dart`, не забудьте перегенерировать код:

```bash
flutter pub run pigeon --input pigeons/pos_printers.dart
# или dart run pigeon --input pigeons/pos_printers.dart
