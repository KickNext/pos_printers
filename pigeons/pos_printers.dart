// ignore_for_file: constant_identifier_names

import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/src/pos_printers.pigeon.dart',
  dartTestOut: 'test/pos_printers_test.pigeon.dart',
  kotlinOut:
      'android/src/main/kotlin/com/kicknext/pos_printers/gen/PosPrintersPluginAPI.kt',
  kotlinOptions: KotlinOptions(
    package: 'com.kicknext.pos_printers.gen',
  ),
))
enum PosPrinterConnectionType {
  usb,
  network,
}

/// Язык лейбл-принтера (CPCL / TSPL / ZPL)
enum LabelPrinterLanguage {
  cpcl,
  tspl,
  zpl,
}

/// DTO c параметрами для *подключения* к принтеру.
/// Используется для connect, print, disconnect и других операций.
/// НЕ используется для обнаружения (для этого есть DiscoveredPrinter).
class PrinterConnectionParams {
  final PosPrinterConnectionType connectionType;

  // --- Поля для идентификации и подключения ---
  // Для USB: vendorId и productId обязательны. usbSerialNumber опционален, но желателен для уникальности.
  final int? vendorId;
  final int? productId;
  final String? usbSerialNumber; // Может быть получен при обнаружении

  // Для Network: ipAddress обязателен.
  final String? ipAddress;

  // --- Информационные поля (обычно получаются при обнаружении) ---
  // Эти поля НЕ используются для установки соединения, но могут быть полезны
  // для отображения или логирования.
  final String? macAddress; // Может быть получен из SDK поиска (Network)
  final String? mask; // Может быть получен из SDK поиска (Network)
  final String? gateway; // Может быть получен из SDK поиска (Network)
  final bool? dhcp; // Может быть получен из SDK поиска (Network)
  final String? manufacturer; // Может быть получен при обнаружении (USB)
  final String? productName; // Может быть получен при обнаружении (USB)

  PrinterConnectionParams({
    required this.connectionType,
    this.vendorId,
    this.productId,
    this.usbSerialNumber,
    this.ipAddress,
    this.macAddress,
    this.mask,
    this.gateway,
    this.dhcp,
    this.manufacturer,
    this.productName,
  });
}

class NetSettingsDTO {
  final String ipAddress;
  final String mask;
  final String gateway;
  final bool dhcp;

  NetSettingsDTO({
    required this.ipAddress,
    required this.mask,
    required this.gateway,
    required this.dhcp,
  });
}

class ConnectResult {
  final bool success;
  final String? message;

  ConnectResult({
    required this.success,
    this.message,
  });
}

/// DTO с расширенной информацией о принтере
class PrinterDetailsDTO {
  final String? serialNumber;
  final String? firmwareVersion;
  final String? deviceModel;
  final String? currentStatus;

  PrinterDetailsDTO({
    this.serialNumber,
    this.firmwareVersion,
    this.deviceModel,
    this.currentStatus,
  });
}

/// Result for getting printer status.
class StatusResult {
  final bool success;
  final String? errorMessage;
  final String? status; // The actual status string if successful

  StatusResult({required this.success, this.errorMessage, this.status});
}

/// Result for getting printer serial number or other string values.
class StringResult {
  final bool success;
  final String? errorMessage;
  final String? value; // The actual string value (e.g., SN) if successful

  StringResult({required this.success, this.errorMessage, this.value});
}

/// DTO для найденного принтера (USB или Сеть) - результат поиска `findPrinters`.
/// Содержит информацию, достаточную для отображения пользователю и
/// для создания `PrinterConnectionParams` для последующего подключения.
class DiscoveredPrinter {
  /// Уникальный идентификатор *найденного* устройства в данный момент.
  /// Для USB: это временный `deviceName` (например, /dev/bus/usb/001/002). Не стабилен!
  /// Для Network: это `ip` (например, 192.168.1.100). Стабилен, если IP не меняется.
  final String id;
  final PosPrinterConnectionType type; // "usb" или "network"
  /// Человекочитаемое имя/метка принтера.
  final String
      label; // e.g. "XPrinter (VID:123, PID:456)" or "Network Printer 192.168.1.100"

  // --- Стабильные идентификаторы для ПОДКЛЮЧЕНИЯ ---
  // Используйте эти поля для создания PrinterConnectionParams!
  // USB
  final int? vendorId;
  final int? productId;
  final String? usbSerialNumber; // Может быть null, если недоступен
  // Network
  final String? ipAddress; // IP адрес
  final String? macAddress; // MAC адрес (если удалось определить)

  // --- Дополнительная информация ---
  final String? manufacturer; // Производитель (USB)
  final String? productName; // Название продукта (USB)

  DiscoveredPrinter({
    required this.id,
    required this.type,
    required this.label,
    // Stable IDs for connection
    this.vendorId,
    this.productId,
    this.usbSerialNumber,
    this.ipAddress, // Добавлен IP
    this.macAddress,
    // Additional info
    this.manufacturer,
    this.productName,
  });
}

@HostApi()
abstract class POSPrintersApi {
  /// Инициирует асинхронный поиск принтеров (USB, SDK Net, TCP Net).
  /// Найденные принтеры (`DiscoveredPrinter`) будут отправляться через `PrinterDiscoveryEventsApi.onPrinterFound`.
  /// По завершении поиска будет вызван `PrinterDiscoveryEventsApi.onDiscoveryComplete`.
  ///
  /// Жизненный цикл:
  /// 1. Вызвать `findPrinters()`.
  /// 2. Получать `DiscoveredPrinter` через `onPrinterFound`.
  /// 3. Пользователь выбирает принтер из списка найденных.
  /// 4. Создать `PrinterConnectionParams`, используя *стабильные* идентификаторы из `DiscoveredPrinter`
  ///    (VID/PID/Serial для USB; IP для Network).
  /// 5. Вызвать `connectPrinter()` с созданными параметрами.
  /// 6. Выполнять операции (печать и т.д.).
  /// 7. Вызвать `disconnectPrinter()`.
  void findPrinters();

  /// Подключается к принтеру, используя параметры из `printer`.
  /// Для USB: необходимы `vendorId`, `productId`. `usbSerialNumber` желателен.
  /// Для Network: необходим `ipAddress`.
  /// Возвращает `ConnectResult` с успехом/ошибкой подключения.
  /// При успешном подключении плагин сохраняет соединение для последующих операций.
  /// Если для этих параметров уже есть активное соединение, оно будет разорвано перед новым подключением.
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  ConnectResult connectPrinter(PrinterConnectionParams printer);

  /// Отключает принтер, идентифицированный параметрами `printer`.
  /// Используйте те же параметры (`vendorId`/`productId`/`usbSerialNumber` или `ipAddress`),
  /// которые использовались для `connectPrinter`.
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void disconnectPrinter(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  StatusResult getPrinterStatus(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  StringResult getPrinterSN(PrinterConnectionParams printer);

  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void openCashBox(PrinterConnectionParams printer);

  /// Печать HTML для обычных чековых ESC/POS принтеров.
  @async
  @TaskQueue(
      type: TaskQueueType
          .serialBackgroundThread) // Reverted: Concurrent not supported by Pigeon TaskQueueType
  void printHTML(
      PrinterConnectionParams printer, String html, int width, bool upsideDown);

  /// Печать сырых ESC/POS команд.
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread) // Reverted
  void printData(PrinterConnectionParams printer, Uint8List data, int width,
      bool upsideDown);

  /// Настройка сетевых параметров через существующее соединение
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void setNetSettingsToPrinter(
      PrinterConnectionParams printer, NetSettingsDTO netSettings);

  /// Настройка сетевых параметров через UDP broadcast (требуется MAC-адрес)
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void configureNetViaUDP(String macAddress, NetSettingsDTO netSettings);

  // ====== Новые методы для ЛЕЙБЛ-ПРИНТЕРОВ ======

  /// Печать "сырых" команд (CPCL/TSPL/ZPL), если нужно.
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread) // Reverted
  void printLabelData(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    Uint8List labelCommands,
    int width,
  );

  /// Печать HTML на лейбл-принтер (рендерим HTML -> bitmap),
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread) // Reverted
  void printLabelHTML(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    String html,
    int width,
    int height,
  );

  /// Установка базовых параметров (размер этикетки, скорость, плотность)
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  void setupLabelParams(
    PrinterConnectionParams printer,
    LabelPrinterLanguage language,
    int labelWidth,
    int labelHeight,
    int densityOrDarkness,
    int speed,
  );

  /// Получение расширенной информации о принтере
  @async
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  PrinterDetailsDTO getPrinterDetails(PrinterConnectionParams printer);
}

/// API для получения событий обнаружения принтеров из нативного кода во Flutter.
@FlutterApi()
abstract class PrinterDiscoveryEventsApi {
  /// Вызывается при обнаружении нового (уникального) принтера.
  /// `printer` содержит информацию о найденном принтере. Используйте стабильные
  /// идентификаторы из него (`vendorId`/`productId`/`usbSerialNumber` или `ipAddress`)
  /// для создания `PrinterConnectionParams` при вызове `connectPrinter`.
  void onPrinterFound(DiscoveredPrinter printer);

  /// Вызывается по завершении всего процесса поиска.
  /// `success` = true, если поиск завершился без критических ошибок (даже если ничего не найдено).
  /// `errorMessage` содержит сообщение об ошибке, если `success` = false.
  void onDiscoveryComplete(bool success, String? errorMessage);
}
