package ru.priceanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
@ExtendWith(OutputCaptureExtension.class)
class ReportApiTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach void seed() {
        jdbc.update("DELETE FROM price_records");
        jdbc.update("DELETE FROM products");
        jdbc.update("INSERT INTO products(id,name,category,unit) VALUES(701,'Кофе','Напитки','шт.')");
        price("А", 100, 1);
        price("а", 80, 10);
        price("Б", 200, 1);
        price("Б", 240, 10);
        price("В", 500, 10);
    }

    private void price(String store, int amount, int day) {
        jdbc.update("INSERT INTO price_records(product_id,store,price,observed_on) VALUES(701,?,?,?)",
            store, amount, LocalDate.of(2026, 10, day));
    }

    @Test
    void calculatesMeanAcrossSeriesAndAppliesAllFilters() throws Exception {
        mvc.perform(get("/api/reports").param("group","category").param("category","Напитки"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].label").value("Напитки"))
            .andExpect(jsonPath("$[0].observationSeries").value(2))
            .andExpect(jsonPath("$[0].averageChange").value(10))
            .andExpect(jsonPath("$[0].averagePercent").value(0));
        mvc.perform(get("/api/reports").param("group","product").param("store","А")
            .param("from","2026-10-01").param("to","2026-10-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].label").value("Кофе"))
            .andExpect(jsonPath("$[0].averagePercent").value(-20));
        mvc.perform(get("/api/reports").param("group","store").param("from","2026-10-09"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/reports")).andExpect(status().isBadRequest());
    }

    @Test
    void exportsEquivalentExcelAndCyrillicPdf() throws Exception {
        byte[] sheet = mvc.perform(get("/api/reports.xlsx").param("group","category").param("productId","701"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=price-report.xlsx"))
            .andReturn().getResponse().getContentAsByteArray();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(sheet))) {
            var summary = book.getSheet("Сводка");
            var row = summary.getRow(14);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("Напитки");
            assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(10);
            assertThat(summary.getRow(5).getCell(2).getNumericCellValue()).isEqualTo(273.33);
            assertThat(summary.getRow(5).getCell(3).getNumericCellValue()).isEqualTo(3);
            var raw = book.getSheet("Исходные данные");
            assertThat(raw.getLastRowNum()).isEqualTo(5);
            assertThat(raw.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Кофе");
            assertThat(DateUtil.isCellDateFormatted(raw.getRow(1).getCell(4))).isTrue();
            var charts = summary.getDrawingPatriarch().getCharts();
            assertThat(charts).hasSize(2);
            assertThat(charts.getFirst().getCTChart().getPlotArea().getLineChartArray(0).sizeOfSerArray()).isEqualTo(3);
            assertThat(charts.getFirst().getCTChart().getPlotArea().sizeOfDateAxArray()).isEqualTo(1);
            assertThat(charts.getLast().getCTChart().getPlotArea().sizeOfBarChartArray()).isEqualTo(1);
        }
        byte[] pdf = mvc.perform(get("/api/reports.pdf").param("group","category").param("productId","701")
                .param("from", "2026-10-01").param("to", "2026-10-10"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
            .andReturn().getResponse().getContentAsByteArray();
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document)).contains("Price Analyzer — Анализ цен", "Кофе",
                "01.10.2026", "10.10.2026", "Динамика цен", "Распределение ценовых наблюдений",
                "Напитки", "10,00 ₽", "80,00 ₽", "273,33 ₽").doesNotContain("\uFFFD", "????");
        }
    }

    @Test
    void pdfRepeatsTableHeadingsOnLaterPages() throws Exception {
        for (int store = 0; store < 44; store++) {
            price("Магазин " + store + " " + "Длинное название ".repeat(5), 100, 5);
        }
        byte[] pdf = mvc.perform(get("/api/reports.pdf").param("group", "product").param("productId", "701"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            assertThat(new PDFTextStripper().getText(document)).contains("Последние цены · продолжение", "Магазин 43");
        }
    }

    @Test
    void returnsAndLogsCsvExportFailure(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/export/prices.csv").param("productId","999999"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Товар не найден"));
        assertThat(output).contains("CSV price export failed, productId=999999");
    }

    @Test
    void comparisonUsesLatestValueAcrossCapitalizationAndRespectsPeriod() throws Exception {
        mvc.perform(get("/api/analysis/701").param("from","2026-10-10").param("to","2026-10-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.latest.length()").value(3))
            .andExpect(jsonPath("$.latest[0].price").value(80));
        mvc.perform(get("/api/analysis/701"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.latest.length()").value(3))
            .andExpect(jsonPath("$.stats.min").value(80));
    }
}
