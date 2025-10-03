## 0.5.0

- **BREAKING CHANGES:**
  - Removed printer language detection functionality
    - Removed `checkPrinterLanguage()` method from API
    - Removed `CheckPrinterLanguageResponse` class
    - Removed `PrinterLanguage` enum
  - Updated `PrinterDiscoveryFilter` to remove language filtering
    - Removed `languages` parameter - now only filters by `connectionTypes`
    - `DiscoveredPrinterDTO` no longer includes `printerLanguage` field
  - Simplified example app to single-file stress test application
  - Updated documentation to reflect API changes
  - Added TSPL (TSC Printer Language) support
    - `printTsplRawData` - Send raw TSPL commands to printer
    - `printTsplHtml` - Convert HTML to bitmap and print on TSPL label printer
    - `getTSPLPrinterStatus` - Get TSPL printer status with detailed error codes
  - Added TSPL status code mapping (0x00-0x80)
- Documentation updates
  - Added TSPL usage guide with examples
  - Updated README with TSPL examples
  - Added TSPL example file in example app

## 0.1.0

- Initial release with ESC/POS and ZPL support
- USB and network connectivity
- HTML to bitmap conversion
- Printer discovery and status checking
