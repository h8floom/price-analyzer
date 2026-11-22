package ru.priceanalyzer.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import ru.priceanalyzer.api.ApiProblem;
import ru.priceanalyzer.domain.PriceEntry;

public final class XlsxPriceFiles {
    static final List<String> IMPORT_HEADERS = List.of("productId", "store", "price", "date");
    static final List<String> HISTORY_HEADERS = List.of("ID товара", "Название товара", "Магазин", "Цена", "Дата");
    static final int ROW_LIMIT = 10_000;
    private static final LocalDate FIRST_EXCEL_DATE = LocalDate.of(1900, 1, 1);
    private static final LocalDate LAST_EXCEL_DATE = LocalDate.of(9999, 12, 31);

    private XlsxPriceFiles() {}

    public static byte[] template() throws IOException {
        return write(IMPORT_HEADERS, List.of(), Map.of());
    }

    static byte[] history(List<PriceEntry> prices, Map<Long, String> products) throws IOException {
        if (prices.size() > ROW_LIMIT) throw narrowExport();
        var bytes = write(HISTORY_HEADERS, prices, products);
        if (bytes.length > 4 * 1024 * 1024) throw narrowExport();
        return bytes;
    }

    private static ApiProblem narrowExport() {
        return new ApiProblem(HttpStatus.PAYLOAD_TOO_LARGE, "Выберите один товар или сократите период выгрузки");
    }

    private static byte[] write(List<String> headers, List<PriceEntry> prices, Map<Long, String> products) throws IOException {
        try (var book = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("История цен");
            var heading = book.createCellStyle();
            heading.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = book.createFont();
            font.setColor(IndexedColors.WHITE.getIndex());
            font.setBold(true);
            heading.setFont(font);
            var format = book.createDataFormat();
            var money = book.createCellStyle();
            money.setDataFormat(format.getFormat("#,##0.00 \"₽\""));
            var day = book.createCellStyle();
            day.setDataFormat(format.getFormat("dd.mm.yyyy"));
            var top = sheet.createRow(0);
            int[] widths = headers.equals(IMPORT_HEADERS) ? new int[]{22, 36, 18, 18} : new int[]{22, 45, 36, 18, 18};
            for (int column = 0; column < headers.size(); column++) {
                var cell = top.createCell(column);
                cell.setCellValue(headers.get(column));
                cell.setCellStyle(heading);
                sheet.setColumnWidth(column, widths[column] * 256);
            }
            for (int index = 0; index < prices.size(); index++) {
                var entry = prices.get(index);
                var row = sheet.createRow(index + 1);
                // A text cell preserves a database identifier even beyond Excel's numeric precision.
                row.createCell(0).setCellValue(Long.toString(entry.productId()));
                row.createCell(1).setCellValue(products.get(entry.productId()));
                row.createCell(2).setCellValue(entry.store());
                var price = row.createCell(3);
                price.setCellValue(entry.price().doubleValue());
                price.setCellStyle(money);
                var date = row.createCell(4);
                if (entry.date().isBefore(FIRST_EXCEL_DATE) || entry.date().isAfter(LAST_EXCEL_DATE)) {
                    date.setCellValue(entry.date().toString());
                } else {
                    date.setCellValue(entry.date());
                    date.setCellStyle(day);
                }
            }
            sheet.createFreezePane(0, 1);
            if (!prices.isEmpty()) sheet.setAutoFilter(new CellRangeAddress(0, prices.size(), 0, headers.size() - 1));
            book.write(bytes);
            return bytes.toByteArray();
        }
    }
}
