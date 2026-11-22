package ru.priceanalyzer.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import ru.priceanalyzer.api.ApiProblem;

final class XlsxPriceReader {
    private XlsxPriceReader() {}

    static List<List<String>> read(InputStream input) {
        try (var workbook = new XSSFWorkbook(input)) {
            if (workbook.getNumberOfSheets() != 1) {
                throw invalid("Для импорта нужен один лист с историей цен. Используйте шаблон или выгрузку истории.");
            }
            var sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > XlsxPriceFiles.ROW_LIMIT) throw invalid("Допустимо до 10 000 записей в XLSX");
            var header = sheet.getRow(0);
            List<String> headings = new ArrayList<>();
            if (header != null) {
                for (int col = 0; col < header.getLastCellNum(); col++) headings.add(value(header.getCell(col), 0, false));
                while (!headings.isEmpty() && headings.getLast().isEmpty()) headings.removeLast();
            }
            boolean history = headings.equals(XlsxPriceFiles.HISTORY_HEADERS);
            if (!history && !headings.equals(XlsxPriceFiles.IMPORT_HEADERS)) {
                throw invalid("Используйте заголовки productId, store, price, date или выгрузку истории цен");
            }
            int store = history ? 2 : 1;

            List<List<String>> rows = new ArrayList<>();
            rows.add(XlsxPriceFiles.IMPORT_HEADERS);
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                var row = sheet.getRow(index);
                if (row == null) {
                    rows.add(List.of(""));
                    continue;
                }
                for (Cell cell : row) {
                    if (cell.getColumnIndex() >= headings.size() && !value(cell, index, false).isBlank()) {
                        throw invalid("Лишний столбец в строке " + (index + 1));
                    }
                }
                List<String> cells = List.of(value(row.getCell(0), index, false), value(row.getCell(store), index, false),
                    value(row.getCell(store + 1), index, false), value(row.getCell(store + 2), index, true));
                rows.add(cells.stream().allMatch(String::isBlank) ? List.of("") : cells);
            }
            return rows;
        } catch (ApiProblem problem) {
            throw problem;
        } catch (IOException | RuntimeException unreadable) {
            throw invalid("Не удалось прочитать файл XLSX");
        }
    }

    private static String value(Cell cell, int rowIndex, boolean date) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case BLANK -> "";
            case STRING -> cell.getStringCellValue().strip();
            case NUMERIC -> date && DateUtil.isCellDateFormatted(cell)
                ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            default -> throw invalid("Недопустимый тип ячейки в строке " + (rowIndex + 1));
        };
    }

    private static ApiProblem invalid(String message) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, message);
    }
}
