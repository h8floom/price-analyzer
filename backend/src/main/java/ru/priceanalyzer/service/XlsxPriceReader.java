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
                throw invalid("В XLSX должен быть один лист с ценами");
            }
            var sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > 10_000) throw invalid("Допустимо до 10 000 записей в XLSX");

            List<List<String>> rows = new ArrayList<>();
            for (int index = 0; index <= sheet.getLastRowNum(); index++) {
                var row = sheet.getRow(index);
                if (row == null) {
                    rows.add(List.of(""));
                    continue;
                }
                for (Cell cell : row) {
                    if (cell.getColumnIndex() >= 4 && !value(cell, index).isBlank()) {
                        throw invalid("В строке " + (index + 1) + " должно быть четыре столбца");
                    }
                }
                List<String> cells = new ArrayList<>(4);
                for (int col = 0; col < 4; col++) cells.add(value(row.getCell(col), index));
                rows.add(cells.stream().allMatch(String::isBlank) ? List.of("") : cells);
            }
            return rows;
        } catch (ApiProblem problem) {
            throw problem;
        } catch (IOException | RuntimeException unreadable) {
            throw invalid("Не удалось прочитать файл XLSX");
        }
    }

    private static String value(Cell cell, int rowIndex) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case BLANK -> "";
            case STRING -> cell.getStringCellValue().strip();
            case NUMERIC -> cell.getColumnIndex() == 3 && DateUtil.isCellDateFormatted(cell)
                ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            default -> throw invalid("Недопустимый тип ячейки в строке " + (rowIndex + 1));
        };
    }

    private static ApiProblem invalid(String message) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, message);
    }
}
