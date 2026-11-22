package ru.priceanalyzer.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

public final class PdfPriceReport {
    private static final Color INK = new Color(37, 45, 68);
    private static final Color MUTED = new Color(84, 95, 116);
    private static final Color PAPER = new Color(245, 246, 252);
    private static final Color GRID = new Color(224, 228, 240);
    private static final Color[] PALETTE = {
        new Color(92, 92, 192), new Color(19, 141, 126), new Color(182, 99, 34),
        new Color(38, 113, 166), new Color(168, 62, 110), new Color(125, 104, 37),
        new Color(69, 113, 54), new Color(117, 75, 158), new Color(121, 133, 151)
    };
    private static final float LEFT = 42;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * LEFT;
    private static final DateTimeFormatter AXIS_DATE = DateTimeFormatter.ofPattern("dd.MM.yy");

    private PdfPriceReport() {}

    public static byte[] write(PriceReports.Snapshot snapshot) throws IOException {
        var report = ReportSeries.from(snapshot);
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream();
             var input = PdfPriceReport.class.getResourceAsStream("/fonts/NotoSans-Regular.ttf")) {
            if (input == null) throw new IOException("Report font is missing");
            var font = PDType0Font.load(document, input);
            try (var pdf = new Page(document, font)) {
                pdf.next();
                pdf.paragraph("Товар  " + report.title(), 14, INK);
                pdf.paragraph("Период  " + report.period(), 10, MUTED);
                pdf.paragraph(report.priceBasis(), 9, MUTED);
                pdf.metrics(report);

                if (report.counts().isEmpty()) {
                    pdf.paragraph("За выбранный период нет ценовых наблюдений.", 11, MUTED);
                } else {
                    if (!report.trends().isEmpty()) pdf.lineChart(report);
                    pdf.donut(report);
                    var offers = report.offers().stream().map(offer -> List.of(
                        snapshot.products().get(offer.productId()).name(), offer.store(), money(offer.price()),
                        ReportSeries.DAY.format(offer.date()))).toList();
                    pdf.table("Последние цены", List.of("Товар", "Магазин", "Цена", "Дата"),
                        new float[]{195, 135, 100, WIDTH - 430}, offers);
                    var groups = snapshot.rows().stream().map(row -> List.of(row.label(),
                        Integer.toString(row.observationSeries()), money(row.averageChange()),
                        decimal(row.averagePercent().doubleValue()) + " %")).toList();
                    if (!groups.isEmpty()) pdf.table("Изменения за период", List.of("Группа", "Серий", "Изменение", "Изменение, %"),
                        new float[]{195, 85, 110, WIDTH - 390}, groups);
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static String decimal(double value) {
        return String.format(Locale.forLanguageTag("ru"), "%.2f", value);
    }

    private static String money(BigDecimal value) {
        return value == null ? "—" : value.toPlainString().replace('.', ',') + " ₽";
    }

    private static final class Page implements AutoCloseable {
        private final PDDocument document;
        private final PDType0Font font;
        private PDPageContentStream ink;
        private float y;

        Page(PDDocument document, PDType0Font font) {
            this.document = document;
            this.font = font;
        }

        void next() throws IOException {
            close();
            var page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            ink = new PDPageContentStream(document, page);
            box(LEFT, 759, WIDTH, 46, PAPER);
            box(LEFT, 759, 4, 46, PALETTE[0]);
            text(LEFT + 16, 775, "Price Analyzer — Анализ цен", 19, INK);
            line(LEFT, 45, LEFT + WIDTH, 45, GRID, 0.6f);
            text(LEFT + WIDTH - 62, 29, "Страница " + document.getNumberOfPages(), 8, MUTED);
            y = 737;
        }

        void metrics(ReportSeries report) throws IOException {
            String[] labels = {"Минимум", "Максимум", "Среднее", "Магазинов"};
            String[] values = {money(report.summary().min()), money(report.summary().max()), money(report.summary().average()),
                Integer.toString(report.counts().size())};
            float w = (WIDTH - 18) / labels.length;
            for (int i = 0; i < labels.length; i++) {
                float x = LEFT + i * (w + 6);
                box(x, y - 44, w, 48, PAPER);
                text(x + 9, y - 11, labels[i], 9, MUTED);
                text(x + 9, y - 31, values[i], 12, INK);
            }
            y -= 61;
        }

        void lineChart(ReportSeries report) throws IOException {
            paragraph("Динамика цен" + report.coverage(), 13, INK);
            var stores = report.chartStores();
            var plotted = stores.stream().flatMap(store -> report.trends().get(store).entrySet().stream()).toList();
            double min = plotted.stream().mapToDouble(p -> p.getValue().doubleValue()).min().orElseThrow();
            double max = plotted.stream().mapToDouble(p -> p.getValue().doubleValue()).max().orElseThrow();
            double pad = Math.max(0.01, (max - min) * 0.1);
            double floor = Math.max(0, min - pad);
            double span = max + pad - floor;
            var start = plotted.stream().map(java.util.Map.Entry::getKey).min(LocalDate::compareTo).orElseThrow();
            var end = plotted.stream().map(java.util.Map.Entry::getKey).max(LocalDate::compareTo).orElseThrow();
            long days = end.toEpochDay() - start.toEpochDay();
            float x = LEFT + 84, w = WIDTH - 96, bottom = y - 138;
            text(LEFT, y - 4, "Цена, ₽", 8, MUTED);
            for (int i = 0; i <= 4; i++) {
                float mark = bottom + 29 * i;
                line(x, mark, x + w, mark, GRID, 0.6f);
                text(LEFT, mark - 3, decimal(floor + span * i / 4), 7, MUTED);
            }
            text(days == 0 ? x + w / 2 - 21 : x, bottom - 15, AXIS_DATE.format(start), 8, MUTED);
            if (days > 0) text(x + w - 41, bottom - 15, AXIS_DATE.format(end), 8, MUTED);
            for (int i = 0; i < stores.size(); i++) {
                var store = stores.get(i);
                float previousX = 0, previousY = 0;
                boolean first = true;
                for (var point : report.trends().get(store).entrySet()) {
                    float px = days == 0 ? x + w / 2 : x + w * (point.getKey().toEpochDay() - start.toEpochDay()) / days;
                    float py = bottom + (float) (116 * (point.getValue().doubleValue() - floor) / span);
                    if (!first) line(previousX, previousY, px, py, PALETTE[i], 1.45f);
                    box(px - 1.7f, py - 1.7f, 3.4f, 3.4f, PALETTE[i]);
                    previousX = px;
                    previousY = py;
                    first = false;
                }
                float legendX = LEFT + (i % 2) * 248;
                float legendY = bottom - 33 - (i / 2) * 14;
                box(legendX, legendY - 1, 8, 8, PALETTE[i]);
                text(legendX + 13, legendY, fit(store, 8, 226), 8, MUTED);
            }
            y = bottom - 48 - ((stores.size() - 1) / 2) * 14;
        }

        void donut(ReportSeries report) throws IOException {
            if (y < 208) next();
            paragraph("Распределение ценовых наблюдений", 13, INK);
            int total = report.snapshot().prices().size();
            float cx = LEFT + 64, cy = y - 67;
            var counts = report.plotCounts();
            double angle = Math.PI / 2;
            for (int i = 0; i < counts.size(); i++) {
                var entry = counts.get(i);
                double step = 2 * Math.PI * entry.getValue() / total;
                ring(cx, cy, angle, angle + step, PALETTE[i]);
                angle += step;
                float x = LEFT + 139 + (i % 2) * 180;
                float yy = y - 21 - (i / 2) * 19;
                box(x, yy - 2, 8, 8, PALETTE[i]);
                text(x + 12, yy, fit(entry.getKey(), 8, 133), 8, INK);
                text(x + 146, yy, Integer.toString(entry.getValue()), 8, MUTED);
            }
            String value = Integer.toString(total);
            text(cx - font.getStringWidth(value) / 2000 * 15, cy - 5, value, 15, INK);
            y -= 130;
        }

        private void ring(float cx, float cy, double from, double to, Color color) throws IOException {
            int steps = Math.max(2, (int) Math.ceil((to - from) * 18));
            ink.setNonStrokingColor(color);
            for (int i = 0; i <= steps; i++) {
                double tilt = from + (to - from) * i / steps;
                float x = cx + (float) (49 * Math.cos(tilt));
                float yy = cy + (float) (49 * Math.sin(tilt));
                if (i == 0) ink.moveTo(x, yy); else ink.lineTo(x, yy);
            }
            for (int i = steps; i >= 0; i--) {
                double tilt = from + (to - from) * i / steps;
                ink.lineTo(cx + (float) (30 * Math.cos(tilt)), cy + (float) (30 * Math.sin(tilt)));
            }
            ink.closePath();
            ink.fill();
        }

        void table(String title, List<String> headings, float[] widths, List<List<String>> rows) throws IOException {
            if (y < 122) next();
            paragraph(title, 13, INK);
            tableRow(headings.stream().map(List::of).toList(), widths, 19, PAPER);
            for (int i = 0; i < rows.size(); i++) {
                var wrapped = new ArrayList<List<String>>();
                for (int col = 0; col < widths.length; col++) wrapped.add(wrap(rows.get(i).get(col), 9, widths[col] - 15));
                float height = 7 + 12 * wrapped.stream().mapToInt(List::size).max().orElse(1);
                if (y - height < 57) {
                    next();
                    paragraph(title + " · продолжение", 13, INK);
                    tableRow(headings.stream().map(List::of).toList(), widths, 19, PAPER);
                }
                tableRow(wrapped, widths, height, i % 2 == 0 ? Color.WHITE : PAPER);
            }
            y -= 17;
        }

        private void tableRow(List<List<String>> cells, float[] widths, float height, Color shade) throws IOException {
            box(LEFT, y - height, WIDTH, height, shade);
            float x = LEFT + 7;
            for (int i = 0; i < widths.length; i++) {
                float yy = y - 12;
                for (String value : cells.get(i)) {
                    text(x, yy, value, 9, INK);
                    yy -= 12;
                }
                x += widths[i];
            }
            y -= height;
        }

        private void paragraph(String value, int size, Color color) throws IOException {
            for (String line : wrap(value, size, WIDTH)) {
                text(LEFT, y, line, size, color);
                y -= size + 4;
            }
            y -= 3;
        }

        private List<String> wrap(String value, int size, float width) throws IOException {
            var lines = new ArrayList<String>();
            for (String paragraph : value.split("\\R", -1)) {
                StringBuilder line = new StringBuilder();
                float used = 0;
                for (int cp : paragraph.codePoints().toArray()) {
                    String character = glyph(cp);
                    float step = font.getStringWidth(character) * size / 1000;
                    if (used + step > width) {
                        lines.add(line.toString());
                        line.setLength(0);
                        used = 0;
                    }
                    line.append(character);
                    used += step;
                }
                lines.add(line.toString());
            }
            return lines;
        }

        private String fit(String value, int size, float width) throws IOException {
            var rows = wrap(value, size, width - 8);
            return rows.getFirst() + (rows.size() > 1 ? "…" : "");
        }

        private String glyph(int cp) throws IOException {
            String character = Character.isISOControl(cp) ? " " : new String(Character.toChars(cp));
            try {
                font.encode(character);
                return character;
            } catch (IllegalArgumentException unsupported) {
                return "?";
            }
        }

        private void text(float x, float baseline, String value, int size, Color color) throws IOException {
            ink.beginText();
            ink.setNonStrokingColor(color);
            ink.setFont(font, size);
            ink.newLineAtOffset(x, baseline);
            ink.showText(value);
            ink.endText();
        }

        private void box(float x, float baseline, float width, float height, Color color) throws IOException {
            ink.setNonStrokingColor(color);
            ink.addRect(x, baseline, width, height);
            ink.fill();
        }

        private void line(float x1, float y1, float x2, float y2, Color color, float width) throws IOException {
            ink.setStrokingColor(color);
            ink.setLineWidth(width);
            ink.moveTo(x1, y1);
            ink.lineTo(x2, y2);
            ink.stroke();
        }

        @Override
        public void close() throws IOException {
            if (ink != null) {
                ink.close();
                ink = null;
            }
        }
    }
}
