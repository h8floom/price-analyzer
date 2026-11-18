package ru.priceanalyzer.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.TreeSet;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class ExcelPriceReport {
    private ExcelPriceReport() {}

    public static byte[] write(PriceReports.Snapshot snapshot) throws IOException {
        var report = ReportSeries.from(snapshot);
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var summary = book.createSheet("Сводка");
            var data = book.createSheet("Исходные данные");
            var headings = book.createCellStyle();
            headings.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headings.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = book.createFont();
            font.setColor(IndexedColors.WHITE.getIndex());
            font.setBold(true);
            headings.setFont(font);
            var currency = style(book, "#,##0.00 \"₽\"");
            var percent = style(book, "0.00%");
            var date = style(book, "dd.mm.yyyy");

            widths(summary, 42, 26, 18, 16);
            widths(data, 16, 42, 28, 18, 16);
            text(summary.createRow(0), 0, "Price Analyzer — Анализ цен", headings);
            text(summary.createRow(1), 0, report.title());
            text(summary.createRow(2), 0, "Период " + report.period());
            text(summary.createRow(3), 0, report.priceBasis());
            header(summary, 4, headings, "Минимум", "Максимум", "Среднее", "Магазинов");
            var metrics = summary.createRow(5);
            if (report.summary().min() != null) {
                number(metrics, 0, report.summary().min(), currency);
                number(metrics, 1, report.summary().max(), currency);
                number(metrics, 2, report.summary().average(), currency);
            }
            metrics.createCell(3).setCellValue(report.counts().size());

            int index = 8;
            header(summary, index++, headings, "Товар", "Магазин", "Цена", "Дата");
            for (var offer : report.offers()) {
                var row = summary.createRow(index++);
                text(row, 0, snapshot.products().get(offer.productId()).name());
                text(row, 1, offer.store());
                number(row, 2, offer.price(), currency);
                date(row, 3, offer.date(), date);
            }
            if (report.offers().isEmpty()) text(summary.createRow(index++), 0, "За выбранный период нет цен.");
            header(summary, ++index, headings, "Группа", "Серий", "Изменение, ₽", "Изменение, %");
            for (var group : snapshot.rows()) {
                var row = summary.createRow(++index);
                text(row, 0, group.label());
                row.createCell(1).setCellValue(group.observationSeries());
                number(row, 2, group.averageChange(), currency);
                number(row, 3, group.averagePercent().movePointLeft(2), percent);
            }

            header(data, 0, headings, "ID товара", "Товар", "Магазин", "Цена", "Дата");
            int rawRow = 1;
            for (var price : snapshot.prices()) {
                var row = data.createRow(rawRow++);
                row.createCell(0).setCellValue(price.productId());
                text(row, 1, snapshot.products().get(price.productId()).name());
                text(row, 2, price.store());
                number(row, 3, price.price(), currency);
                date(row, 4, price.date(), date);
            }
            data.createFreezePane(0, 1);
            data.setAutoFilter(new CellRangeAddress(0, rawRow - 1, 0, 4));

            if (!report.counts().isEmpty()) {
                var drawing = summary.createDrawingPatriarch();
                if (!report.trends().isEmpty()) {
                    trendChart(drawing, data, report, headings, date, currency);
                }
                countsChart(drawing, data, report, headings);
            }
            book.write(output);
            return output.toByteArray();
        }
    }

    private static void trendChart(XSSFDrawing drawing, XSSFSheet data, ReportSeries report,
                                   CellStyle headings, CellStyle date, CellStyle currency) {
        var stores = report.chartStores();
        var dates = new TreeSet<LocalDate>();
        stores.forEach(store -> dates.addAll(report.trends().get(store).keySet()));
        final int start = 9;
        text(row(data, 0), start, "Дата", headings);
        data.setColumnWidth(start, 15 * 256);
        for (int i = 0; i < stores.size(); i++) {
            text(row(data, 0), start + i + 1, stores.get(i), headings);
            data.setColumnWidth(start + i + 1, 22 * 256);
        }
        int index = 1;
        for (var day : dates) {
            var row = row(data, index++);
            date(row, start, day, date);
            for (int i = 0; i < stores.size(); i++) {
                BigDecimal price = report.trends().get(stores.get(i)).get(day);
                if (price != null) number(row, start + i + 1, price, currency);
            }
        }
        var chart = chart(drawing, 0, "Динамика цен" + report.coverage());
        chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);
        chart.displayBlanksAs(DisplayBlanks.SPAN);
        var datesAxis = chart.createDateAxis(AxisPosition.BOTTOM);
        datesAxis.setTitle("Дата наблюдения");
        datesAxis.setNumberFormat("dd.mm.yy");
        var values = chart.createValueAxis(AxisPosition.LEFT);
        values.setTitle("Цена, ₽");
        datesAxis.crossAxis(values);
        values.crossAxis(datesAxis);
        var chartData = chart.createData(ChartTypes.LINE, datesAxis, values);
        var calendar = XDDFDataSourcesFactory.fromNumericCellRange(data, range(index - 1, start));
        for (int i = 0; i < stores.size(); i++) {
            int column = start + i + 1;
            var prices = XDDFDataSourcesFactory.fromNumericCellRange(data, range(index - 1, column));
            var series = (XDDFLineChartData.Series) chartData.addSeries(calendar, prices);
            series.setTitle(stores.get(i), new CellReference(data.getSheetName(), 0, column, true, true));
            series.setMarkerStyle(MarkerStyle.CIRCLE);
            series.setSmooth(false);
        }
        chart.plot(chartData);
    }

    private static void countsChart(XSSFDrawing drawing, XSSFSheet data, ReportSeries report, CellStyle headings) {
        int index = 1;
        text(row(data, 0), 6, "Магазин", headings);
        text(row(data, 0), 7, "Наблюдений", headings);
        data.setColumnWidth(6, 28 * 256);
        data.setColumnWidth(7, 16 * 256);
        for (var entry : report.plotCounts()) {
            var row = row(data, index++);
            text(row, 6, entry.getKey());
            row.createCell(7).setCellValue(entry.getValue());
        }
        var chart = chart(drawing, report.trends().isEmpty() ? 0 : 19, "Ценовые наблюдения по магазинам");
        var stores = chart.createCategoryAxis(AxisPosition.LEFT);
        stores.setTitle("Магазин");
        var values = chart.createValueAxis(AxisPosition.BOTTOM);
        values.setTitle("Записей о ценах");
        values.setMinimum(0);
        values.setNumberFormat("0");
        stores.crossAxis(values);
        values.crossAxis(stores);
        var bars = (XDDFBarChartData) chart.createData(ChartTypes.BAR, stores, values);
        bars.setBarDirection(BarDirection.BAR);
        bars.setBarGrouping(BarGrouping.CLUSTERED);
        bars.addSeries(XDDFDataSourcesFactory.fromStringCellRange(data, range(index - 1, 6)),
            XDDFDataSourcesFactory.fromNumericCellRange(data, range(index - 1, 7))).setTitle("Наблюдений");
        chart.plot(bars);
    }

    private static XSSFChart chart(XSSFDrawing drawing, int row, String title) {
        var chart = drawing.createChart(drawing.createAnchor(0, 0, 0, 0, 5, row, 15, row + 17));
        chart.setTitleText(title);
        chart.setTitleOverlay(false);
        return chart;
    }

    private static CellRangeAddress range(int end, int column) {
        return new CellRangeAddress(1, end, column, column);
    }

    private static XSSFRow row(XSSFSheet sheet, int index) {
        var existing = sheet.getRow(index);
        return existing == null ? sheet.createRow(index) : existing;
    }

    private static void widths(XSSFSheet sheet, int... widths) {
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
    }

    private static CellStyle style(XSSFWorkbook book, String format) {
        var style = book.createCellStyle();
        style.setDataFormat(book.createDataFormat().getFormat(format));
        return style;
    }

    private static void header(XSSFSheet sheet, int index, CellStyle style, String... cells) {
        var row = sheet.createRow(index);
        for (int i = 0; i < cells.length; i++) text(row, i, cells[i], style);
    }

    private static void text(XSSFRow row, int column, String text) {
        row.createCell(column).setCellValue(text);
    }

    private static void text(XSSFRow row, int column, String text, CellStyle style) {
        text(row, column, text);
        row.getCell(column).setCellStyle(style);
    }

    private static void number(XSSFRow row, int column, BigDecimal value, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void date(XSSFRow row, int column, LocalDate day, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellValue(day);
        cell.setCellStyle(style);
    }
}
