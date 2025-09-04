# Android TSPL Program Manual

v3.1.6

## 1. Instruction

This manual describes how to implement TSPL printing.Constant variable are defined in TSPLConst class.

## 2. TSPLPrinter

### 2.1. TSPLPrinter

Constructor to create print objects.

```
TSPLPrinter(IDeviceConnection connection)
```

**Parameter**

- `connection` - Connected object, available via POSConnect.createDevice(deviceType).

### 2.2. size

This method defines the label width and length.

```
TSPLPrinter sizeInch(int width, int height)  // English system (inch)
TSPLPrinter sizeMm(int width, int height)    // Metric system (mm)
```

**Parameter**

- `width` - Label width (inch/mm)
- `height` - Label height (inch/ mm)

**Return**
TSPLPrinter Instance

### 2.3. gap

This method defines the gap distance between two labels

```
TSPLPrinter gapInch(double m, double n)  // English system (inch)
TSPLPrinter gapMm(double m, double n)    // Metric system (mm)
```

**Parameter**

- `m` - The gap distance between two labels
- `n` - The offset distance of the gap

**Return**
TSPLPrinter Instance

### 2.4. speed

This method defines the print speed

```
TSPLPrinter speed(double speed)
```

**Parameter**

- `speed` - Printing speed in inch per second

**Return**
TSPLPrinter Instance

### 2.5. density

This method sets the printing darkness.

```
TSPLPrinter density(int density)
```

**Parameter**

- `density` - Darkness level, 0~15.

**Return**
TSPLPrinter Instance

### 2.6. cls

This method clears the image buffer.

```
TSPLPrinter cls()
```

**Return**
TSPLPrinter Instance

### 2.7. offset

This command defines the selective, extra label feeding length each form feed takes, which, especially in peel-off mode and cutter mode, is used to adjust label stop position, so as for label to register at proper places for the intended purposes. The printer back tracks the extra feeding length before the next run of printing.

```
TSPLPrinter offsetInch(double offset)  // English system (inch)
TSPLPrinter offsetMm(double offset)    // Metric system (mm)
```

**Parameter**

- `offset` - The offset distance (inch or mm) -1 ≤ offset ≤ 1 (inch)

**Return**
TSPLPrinter Instance

### 2.8. direction

This method defines the printout direction and mirror image. This will be stored in the printer memory.

```
TSPLPrinter direction(int direction)
TSPLPrinter direction(int direction, boolean isMirror)
```

**Parameter**

- `direction` - Printout direction

| Variable          | Description |
| ----------------- | ----------- |
| DIRECTION_FORWARD | FORWARD     |
| DIRECTION_REVERSE | REVERSE     |

- `isMirror` - mirror image(true or false),Default value:false.

**Return**
TSPLPrinter Instance

### 2.9. feed

This method feeds label with the specified length. The length is specified by dot.

```
TSPLPrinter feed(int length)
```

**Parameter**

- `length` - Length,unit: dot (1 ≤ length ≤ 9999)

**Return**
TSPLPrinter Instance

### 2.10. reference

This method defines the reference point of the label. The reference (origin) point varies with the print direction.

```
TSPLPrinter reference(int x, int y)
```

**Parameter**

- `x` - Horizontal coordinate (in dots)
- `y` - Vertical coordinate (in dots)

**Return**
TSPLPrinter Instance

### 2.11. bar

This method draws a bar on the label format.

```
TSPLPrinter bar(int x, int y, int width, int height)
```

**Parameter**

- `x` - The upper left corner x-coordinate (in dots)
- `y` - The upper left corner y-coordinate (in dots)
- `width` - Bar width (in dots)
- `height` - Bar height (in dots)

**Return**
TSPLPrinter Instance

### 2.12. box

This method draws rectangles on the label.

```
TSPLPrinter box(int x, int y, int width, int height, int thickness)
```

**Parameter**

- `x` - Specify x-coordinate of upper left corner (in dots)
- `y` - Specify y-coordinate of upper left corner (in dots)
- `width` - rectangles width (in dots)
- `height` - rectangles height (in dots)
- `thickness` - Line thickness (in dots)

**Return**
TSPLPrinter Instance

### 2.13. backFeed

This method feeds the label in reverse. The length is specified by dot.

```
TSPLPrinter backFeed(int length)
```

**Parameter**

- `length` - Length unit: dot (1 ≤ length ≤ 9999)

**Return**
TSPLPrinter Instance

### 2.14. formFeed

This method feeds label to the beginning of next label.

```
TSPLPrinter formFeed()
```

**Return**
TSPLPrinter Instance

### 2.15. home

This method will feed label until the internal sensor has determined the origin. Size and gap of the label should be defined before using this method.

```
TSPLPrinter home()
```

**Return**
TSPLPrinter Instance

### 2.16. print

This method prints the label format currently stored in the image buffer.

```
void print()
void print(int count)
```

**Parameter**

- `count` - Specifies how many sets of labels will be printed. Default value:1.

**Return**
TSPLPrinter Instance

### 2.17. codePage

This method defines the code page of international character set.

```
TSPLPrinter codePage(String page)
```

**Parameter**

- `page` - Name or number of code page.

**7-bit code page:**
| page | Name |
|------|------|
| USA | United States |
| BRI | British |
| GER | German |
| FRE | French |
| DAN | Danish |
| ITA | Italian |
| SPA | Spanish |
| SWE | Swedish |
| SWI | Swiss |

**8-bit code page:**
| page | Name |
|------|------|
| 437 | United States |
| 737 | Greek |
| 850 | Multilingual |
| 851 | Greek 1 |
| 852 | Slavic |
| 855 | Cyrillic |
| 857 | Turkish |
| 860 | Portuguese |
| 861 | Icelandic |
| 862 | Hebrew |
| 863 | Canadian/French |
| 864 | Arabic |
| 865 | Nordic |
| 866 | Russian |
| 869 | Greek 2 |

**Windows code page:**
| page | Name |
|------|------|
| 1250 | Central Europe |
| 1251 | Cyrillic |
| 1252 | Latin I |
| 1253 | Greek |
| 1254 | Turkish |
| 1255 | Hebrew |
| 1256 | Arabic |
| 1257 | Baltic |
| 1258 | Vietnam |
| 932 | Japanese Shift-JIS |
| 936 | Simplified Chinese GBK |
| 949 | Korean |
| 950 | Traditional Chinese Big5 |
| UTF-8 | UTF 8 |

**ISO code page:**
| page | Name |
|------|------|
| 8859-1 | Latin 1 |
| 8859-2 | Latin 2 |
| 8859-3 | Latin 3 |
| 8859-4 | Baltic |
| 8859-5 | Cyrillic |
| 8859-6 | Arabic |
| 8859-7 | Greek |
| 8859-8 | Hebrew |
| 8859-9 | Turkish |
| 8859-10 | Latin 6 |
| 8859-15 | Latin 9 |

**Return**
TSPLPrinter Instance

### 2.18. sound

This method controls the sound frequency of the beeper. There are 10 levels of sounds. The timing control can be set by the "interval" parameter.

```
TSPLPrinter sound(int level, int interval)
```

**Parameter**

- `level` - Sound level:0~9
- `interval` - Sound interval: 1~4095.(in ms)

**Return**
TSPLPrinter Instance

### 2.19. limitFeed

Limit the maximum length of the fixed clearance correction execution, and if the gap presence cannot be measured within this length range, set the sensor mode in the continuous paper mode.

```
TSPLPrinter limitFeedInch(int length)  // English system (inch)
TSPLPrinter limitFeedMm(int length)    // Metric system (mm)
```

**Parameter**

- `length` - The maximum length for sensor detecting

**Return**
TSPLPrinter Instance

### 2.20. barCode

This method prints 1D barcodes.

```
TSPLPrinter barcode(int x, int y, String codeType, int height, String content)
TSPLPrinter barcode(int x, int y, String codeType, int height, boolean readable, int rotation, String content)
TSPLPrinter barcode(int x, int y, String codeType, int height, int readable, int rotation, int narrow, int wide, String content)
```

**Parameter**

- `x` - Specify the x-coordinate bar code on the label
- `y` - Specify the y-coordinate bar code on the label
- `codeType` - Code type

| Variable           | Description                                                    |
| ------------------ | -------------------------------------------------------------- |
| CODE_TYPE_128      | Code 128, switching code subset automatically.                 |
| CODE_TYPE_128M     | Code 128, switching code subset manually.                      |
| CODE_TYPE_EAN128   | EAN128, switching code subset automatically.                   |
| CODE_TYPE_25       | Interleaved 2 of 5.                                            |
| CODE_TYPE_25C      | Interleaved 2 of 5 with check digit.                           |
| CODE_TYPE_39       | Code 39, switching standard and full ASCII mode automatically. |
| CODE_TYPE_39C      | Code 39 with check digit.                                      |
| CODE_TYPE_93       | Code 93.                                                       |
| CODE_TYPE_EAN13    | EAN 13.                                                        |
| CODE_TYPE_EAN13_2  | EAN 13 with 2 digits add-on.                                   |
| CODE_TYPE_EAN13_5  | EAN 13 with 5 digits add-on.                                   |
| CODE_TYPE_EAN8     | EAN 8.                                                         |
| CODE_TYPE_EAN8_2   | EAN 8 with 2 digits add-on.                                    |
| CODE_TYPE_EAN8_5   | EAN 8 with 5 digits add-on.                                    |
| CODE_TYPE_CODA     | Codabar.                                                       |
| CODE_TYPE_POST     | Postnet.                                                       |
| CODE_TYPE_UPCA     | UPC-A.                                                         |
| CODE_TYPE_UPCA_2   | UPC-A with 2 digits add-on.                                    |
| CODE_TYPE_UPCA_5   | UPC-A with 5 digits add-on.                                    |
| CODE_TYPE_UPCE     | UPC-E.                                                         |
| CODE_TYPE_UPCE_2   | UPC-E with 2 digits add-on.                                    |
| CODE_TYPE_UPCE_5   | UPC-E with 5 digits add-on.                                    |
| CODE_TYPE_CPOST    | China post.                                                    |
| CODE_TYPE_MSI      | MSI.                                                           |
| CODE_TYPE_MSIC     | MSI with check digit.                                          |
| CODE_TYPE_PLESSEY  | PLESSEY.                                                       |
| CODE_TYPE_ITF14    | ITF14.                                                         |
| CODE_TYPE_EAN14    | EAN14.                                                         |
| CODE_TYPE_11       | Code 11.                                                       |
| CODE_TYPE_TELEPEN  | Telepen.                                                       |
| CODE_TYPE_TELEPENN | Telepen number.                                                |
| CODE_TYPE_PLANET   | Planet.                                                        |
| CODE_TYPE_CODE49   | Code 49.                                                       |
| CODE_TYPE_DPI      | Deutsche Post Identcode.                                       |
| CODE_TYPE_DPL      | Deutsche Post Leitcode.                                        |

- `height` - Bar code height (in dots)
- `readable` - human readable , Default value:READABLE_LEFT

| Variable        | Description                     |
| --------------- | ------------------------------- |
| READABLE_NONE   | not readable                    |
| READABLE_LEFT   | human readable aligns to left   |
| READABLE_CENTER | human readable aligns to center |
| READABLE_RIGHT  | human readable aligns to right  |

- `rotation` - Default value:ROTATION_0

| Variable     | Description                  |
| ------------ | ---------------------------- |
| ROTATION_0   | No rotation                  |
| ROTATION_90  | Rotate 90 degrees clockwise  |
| ROTATION_180 | Rotate 180 degrees clockwise |
| ROTATION_270 | Rotate 270 degrees clockwise |

- `narrow` - Width of narrow element (in dots), Default value:2
- `wide` - Width of wide element (in dots),Default value:2
- `content` - Content of barcode

**Return**
TSPLPrinter Instance

### 2.21. bitmap

This method draws bitmap images.

```
TSPLPrinter bitmap(int x, int y, int mode, int width, Bitmap bmp)
TSPLPrinter bitmap(int x, int y, int mode, int width, Bitmap bmp, AlgorithmType algorithmType)
```

Transferring images to printers through compression, only applicable to some models

```
TSPLPrinter bitmapCompression(int x, int y, int mode, int width, Bitmap bmp, AlgorithmType algorithmType)
```

**Parameter**

- `x` - Specify the x-coordinate
- `y` - Specify the y-coordinate
- `mode` - Graphic modes listed below:

| Variable             | Description                                            |
| -------------------- | ------------------------------------------------------ |
| BMP_MODE_OVERWRITE   | OVERWRITE,Only applicable to bitmap method             |
| BMP_MODE_OR          | OR,Only applicable to bitmap method                    |
| BMP_MODE_XOR         | XOR,Only applicable to bitmap method                   |
| BMP_MODE_OVERWRITE_C | OVERWRITE, Only applicable to bitmapCompression method |
| BMP_MODE_OR_C        | OR, Only applicable to bitmapCompression method        |
| BMP_MODE_XOR_C       | XOR, Only applicable to bitmapCompression method       |

- `width` - Print width of picture
- `bmp` - Bitmap data
- `algorithmType` - Algorithm type. Default is AlgorithmType.Threshold.
  - AlgorithmType.Dithering
  - AlgorithmType.Threshold

**Return**
TSPLPrinter Instance

### 2.22. qrcode

This method prints QR code.

```
TSPLPrinter qrcode(int x, int y, int cellWidth, int rotation, String data)
TSPLPrinter qrcode(int x, int y, String ecLevel, int cellWidth, int rotation, String data)
TSPLPrinter qrcode(int x, int y, String ecLevel, int cellWidth, String mode, int rotation, String data)
TSPLPrinter qrcode(int x, int y, String ecLevel, int cellWidth, String mode, int rotation, String model, String mask, String data)
```

**Parameter**

- `x` - The upper left corner x-coordinate of the QR code
- `y` - The upper left corner y-coordinate of the QR code
- `ecLevel` - Error correction recovery level

| Variable   | Description                    |
| ---------- | ------------------------------ |
| EC_LEVEL_L | Error correction Level L (7%)  |
| EC_LEVEL_M | Error correction Level M (15%) |
| EC_LEVEL_Q | Error correction Level Q (25%) |
| EC_LEVEL_H | Error correction Level H (30%) |

- `cellWidth` - Cell size:1~10
- `mode` - Auto / manual encode

| Variable           | Description |
| ------------------ | ----------- |
| QRCODE_MODE_AUTO   | Auto        |
| QRCODE_MODE_MANUAL | Manual      |

- `rotation` - Clockwise rotation angle, Default value:ROTATION_0

| Variable     | Description |
| ------------ | ----------- |
| ROTATION_0   | 0 degree    |
| ROTATION_90  | 90 degree   |
| ROTATION_180 | 180 degree  |
| ROTATION_270 | 270 degree  |

- `model`

| Variable        | Description                                                         |
| --------------- | ------------------------------------------------------------------- |
| QRCODE_MODEL_M1 | (default), original version                                         |
| QRCODE_MODEL_M2 | enhanced version (Almost smart phone is supported by this version.) |

- `mask` - S0~S8, default is S7
- `data` - QRCode data content.

**Return**
TSPLPrinter Instance

### 2.23. text

This method prints text on label.

```
TSPLPrinter text(int x, int y, String font, String content)
TSPLPrinter text(int x, int y, String font, int xRatio, int yRatio, String content)
TSPLPrinter text(int x, int y, String font, int rotation, int xRatio, int yRatio, String content)
```

**Parameter**

- `x` - The x-coordinate of the text
- `y` - The y-coordinate of the text
- `font` - Font name

| Variable                | Description                        |
| ----------------------- | ---------------------------------- |
| FNT_8_12                | 8 x 12 fixed pitch dot font        |
| FNT_12_20               | 12 x 20 fixed pitch dot font       |
| FNT_16_24               | 16 x 24 fixed pitch dot font       |
| FNT_24_32               | 24 x 32 fixed pitch dot font       |
| FNT_32_48               | 32 x 48 dot fixed pitch font       |
| FNT_14_19               | 14 x 19 dot fixed pitch font OCR-B |
| FNT_14_25               | 14 x25 dot fixed pitch font OCR-A  |
| FNT_21_27               | 21 x 27 dot fixed pitch font OCR-B |
| FNT_SIMPLIFIED_CHINESE  | Simplified Chinese 24x24           |
| FNT_TRADITIONAL_CHINESE | Traditional Chinese 24x24          |
| FNT_KOREAN              | Korean text 24x24                  |

- `rotation` - Clockwise rotation angle, Default value:ROTATION_0

| Variable     | Description |
| ------------ | ----------- |
| ROTATION_0   | 0 degree    |
| ROTATION_90  | 90 degree   |
| ROTATION_180 | 180 degree  |
| ROTATION_270 | 270 degree  |

- `xRatio` - Horizontal multiplication, up to 10x Available factors: 1~10
- `yRatio` - Vertical multiplication, up to 10x Available factors: 1~10
- `content` - Content of text string

**Return**
TSPLPrinter Instance

### 2.24. erase

This method clears a specified region in the image buffer.

```
TSPLPrinter erase(int x, int y, int width, int height)
```

**Parameter**

- `x` - The x-coordinate of the starting point (in dots)
- `y` - The y-coordinate of the starting point (in dots)
- `width` - The region width in x-axis direction (in dots)
- `height` - The region height in y-axis direction (in dots)

**Return**
TSPLPrinter Instance

### 2.25. reverse

This method reverses a region in image buffer.

```
TSPLPrinter reverse(int x, int y, int width, int height)
```

**Parameter**

- `x` - The x-coordinate of the starting point (in dots)
- `y` - The y-coordinate of the starting point (in dots)
- `width` - X-axis region width (in dots)
- `height` - Y-axis region height (in dots)

**Return**
TSPLPrinter Instance

### 2.26. cut

This command activates the cutter to immediately cut the labels without back feeding the label.

```
TSPLPrinter cut()
```

**Return**
TSPLPrinter Instance

### 2.27. setPeel

This method is used to enable/disable the self-peeling function. The default setting for this function is false. When this function is set true, the printer stops after each label printing, and does not print the next label until the peeled label is taken away. This setting will be saved in printer memory when turning off the power.

```
TSPLPrinter setPeel(boolean isOpen)
```

**Parameter**

- `isOpen` - true:Enable the self-peeling function false:Disable the self-peeing function

**Return**
TSPLPrinter Instance

### 2.28. setTear

This method is used to enable/disable feeding of labels to gap/black mark position for tearing off. This setting will be saved in printer memory when turning off the power

```
TSPLPrinter setTear(boolean isOpen)
```

**Parameter**

- `isOpen` - true:The label gap will stop at the tear off position after print. false:The label gap will NOT stop at the tear off position after print. The beginning of label will be aligned to print head.

**Return**
TSPLPrinter Instance

### 2.29. bline

This method sets the height of the black line and the user-defined extra label feeding length each form feed takes.

```
TSPLPrinter blineInch(double m, double n)  // English system (inch)
TSPLPrinter blineMm(double m, double n)    // Metric system (mm)
```

**Parameter**

- `m` - The height of black line either in inch or mm
- `n` - The extra label feeding length (0 ≤ n ≤ label length)

**Return**
TSPLPrinter Instance

### 2.30. printerStatus

Get printer status

```
void printerStatus(IDataCallback callback)
void printerStatus(int timeout, IDataCallback callback)
```

**Parameter**

- `timeout` - Receive timeout, Unit is ms,Default is 5000ms
- `callback` - The callback content is the corresponding printer state

```java
public interface IStatusCallback {
    void receive(int status);
}
```

| status(HEX) | Description                                 |
| ----------- | ------------------------------------------- |
| 00          | Normal                                      |
| 01          | Head opened                                 |
| 02          | Paper Jam                                   |
| 03          | Paper Jam and head opened                   |
| 04          | Out of paper                                |
| 05          | Out of paper and head opened                |
| 08          | Out of ribbon                               |
| 09          | Out of ribbon and head opened               |
| 0A          | Out of ribbon and paper jam                 |
| 0B          | Out of ribbon, paper jam and head opened    |
| 0C          | Out of ribbon and out of paper              |
| 0D          | Out of ribbon, out of paper and head opened |
| 10          | Pause                                       |
| 20          | Printing                                    |
| 80          | Other error                                 |
| -1          | Receive timeout                             |

### 2.31. isConnect

Query connection status

```
void isConnect(IStatusCallback callback)
```

**Parameter**

- `callback` - Status callback.

```java
public interface IStatusCallback {
    void receive(int status);
}
```

| status         | Description |
| -------------- | ----------- |
| STS_CONNECT    | connect     |
| STS_DISCONNECT | disconnect  |

### 2.32. setCharSet

Set character encoding,Default is "gbk"

```
void setCharSet(String charSet)
```

**Parameter**

- `charSet` - Character set name.

### 2.33. sendData

This function is used to send data to the printer.

```
TSPLPrinter sendData(byte[] data)
TSPLPrinter sendData(List<byte[]> datas)
```

**Parameter**

- `data` - Byte array to be sent
- `datas` - Byte array collection to be sent

**Return**
TSPLPrinter Instance

## ImageUtils

### handleImageEffect

This method is used to adjust the contrast and brightness of the image.

```
static Bitmap handleImageEffect(Bitmap bmp, float contrast, float brightness)
```

**Parameter**

- `bmp` - Original image
- `contrast` - Contrast,The range is 0~2
- `brightness` - Brightness,The range is -255~255

**Return**
processed image object
