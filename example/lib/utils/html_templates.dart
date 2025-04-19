/// Generates a simple HTML for a price tag (example)
String generatePriceTagHtml({
  required String itemName,
  required String price,
  required String unit,
  required String barcodeData,
  String storeName = 'My Store',
}) {
  return '''<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <style>
    body, html { margin: 0; padding: 0; width: 464px; height: 320px; box-sizing: border-box; font-family: Arial, sans-serif; background-color: white; color: black; display: flex; justify-content: center; align-items: center; }
    .price-tag { width: 96%; height: 96%; border: 1px solid black; padding: 10px; box-sizing: border-box; display: flex; flex-direction: column; justify-content: space-around; align-items: center; text-align: center; }
    .store-name, .item-name, .price, .unit, .barcode-area { margin: 2px 0; width: 100%; }
    .store-name { font-size: 20px; font-weight: bold; }
    .item-name { font-size: 30px; font-weight: bold; word-wrap: break-word; }
    .price { font-size: 48px; font-weight: bold; }
    .unit { font-size: 18px; }
    .barcode-area { font-size: 16px; min-height: 50px; border: 1px dashed grey; display: flex; align-items: center; justify-content: center; }
  </style>
</head>
<body>
  <div class="price-tag">
    <p class="store-name">$storeName</p>
    <p class="item-name">$itemName</p>
    <p class="price">\$$price</p>
    <p class="unit">Price per $unit</p>
    <div class="barcode-area">Barcode: $barcodeData <br/> (Image would go here)</div>
  </div>
</body>
</html>
''';
}
