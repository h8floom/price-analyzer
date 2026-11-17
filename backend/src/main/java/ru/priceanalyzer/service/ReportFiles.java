package ru.priceanalyzer.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class ReportFiles {
    private ReportFiles() {}

    private static final String[] HEADINGS = {"Группа", "Серий", "Среднее изменение, ₽", "Среднее изменение, %"};

    public static byte[] xlsx(List<PriceReports.Row> rows) throws IOException {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("Динамика цен");
            var title = sheet.createRow(0);
            for (int i = 0; i < HEADINGS.length; i++) {
                title.createCell(i).setCellValue(HEADINGS[i]);
                sheet.setColumnWidth(i, (i == 0 ? 42 : 27) * 256);
            }
            sheet.createFreezePane(0, 1);
            var number = book.createCellStyle();
            number.setDataFormat(book.createDataFormat().getFormat("0.00"));
            for (int i = 0; i < rows.size(); i++) {
                var value = rows.get(i);
                var row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(value.label());
                row.createCell(1).setCellValue(value.observationSeries());
                row.createCell(2).setCellValue(value.averageChange().doubleValue());
                row.createCell(3).setCellValue(value.averagePercent().doubleValue());
                row.getCell(2).setCellStyle(number);
                row.getCell(3).setCellStyle(number);
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    public static byte[] pdf(List<PriceReports.Row> rows) throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream();
             InputStream source = ReportFiles.class.getResourceAsStream("/fonts/NotoSans-Regular.ttf")) {
            if (source == null) throw new IOException("Report font is missing");
            PDType0Font font = PDType0Font.load(document, source);
            List<String> lines = new ArrayList<>();
            if (rows.isEmpty()) lines.add("За выбранный период нет серий с двумя датами.");
            for (var row : rows) {
                lines.addAll(wrap(font, printable(font, row.label())));
                lines.add("Серий: " + row.observationSeries() + "    Изменение: "
                    + row.averageChange().toPlainString() + " руб.    Изменение: " + row.averagePercent().toPlainString() + " %");
                lines.add("");
            }
            for (int offset = 0; offset < lines.size(); offset += 45) {
                var page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (var text = new PDPageContentStream(document, page)) {
                    text.beginText();
                    text.setFont(font, 15);
                    text.newLineAtOffset(42, 790);
                    text.showText("Price Analyzer · Динамика цен");
                    text.setFont(font, 10);
                    for (String line : lines.subList(offset, Math.min(offset + 45, lines.size()))) {
                        text.newLineAtOffset(0, -16);
                        text.showText(line);
                    }
                    text.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static List<String> wrap(PDType0Font font, String text) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int character : text.codePoints().toArray()) {
            String next = new String(Character.toChars(character));
            if (font.getStringWidth(line + next) > 47_000) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(next);
        }
        lines.add(line.toString());
        return lines;
    }

    private static String printable(PDType0Font font, String text) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int character : text.codePoints().toArray()) {
            String item = Character.isISOControl(character) ? " " : new String(Character.toChars(character));
            try {
                font.encode(item);
                result.append(item);
            } catch (IllegalArgumentException missingGlyph) {
                result.append('?');
            }
        }
        return result.toString();
    }
}
