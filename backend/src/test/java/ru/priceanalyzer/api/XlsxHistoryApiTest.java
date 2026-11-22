package ru.priceanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class XlsxHistoryApiTest {
    private static final long PRODUCT = 9_007_199_254_740_993L;
    private static final LocalDate DAY = LocalDate.of(2026, 11, 18);
    private static final String MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void catalog() {
        jdbc.update("DELETE FROM price_records");
        jdbc.update("DELETE FROM products");
        jdbc.update("INSERT INTO products(id,name,category,unit) VALUES(?,?,?,?)", PRODUCT, "Кофе, 250 г", "Напитки", "шт.");
    }

    @Test void importsAndReimportsHistoryWithExactIdAndTypedData() throws Exception {
        var source = workbook(Long.toString(PRODUCT), false);
        mvc.perform(multipart("/api/import/prices.xlsx").file(file(source)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(2));
        mvc.perform(get("/api/analysis/{productId}", PRODUCT))
            .andExpect(jsonPath("$.latest.length()").value(1))
            .andExpect(jsonPath("$.latest[0].store").value("МАГАЗИН «ЁЛКА»"))
            .andExpect(jsonPath("$.latest[0].price").value(126.90));
        byte[] history = mvc.perform(get("/api/export/prices.xlsx")
                .param("productId", Long.toString(PRODUCT)).param("from", DAY.toString()).param("to", DAY.toString())
                .with(user("reader").roles("USER")))
            .andExpect(status().isOk()).andExpect(content().contentType(MIME))
            .andExpect(header().string("Content-Disposition", "attachment; filename=price-history.xlsx"))
            .andReturn().getResponse().getContentAsByteArray();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(history))) {
            var sheet = book.getSheet("История цен");
            assertThat(book.getNumberOfSheets()).isEqualTo(1);
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Название товара");
            var row = sheet.getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo(Long.toString(PRODUCT));
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("Кофе, 250 г");
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("Магазин «Ёлка»");
            assertThat(row.getCell(3).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row.getCell(3).getNumericCellValue()).isEqualTo(125.40);
            assertThat(DateUtil.isCellDateFormatted(row.getCell(4))).isTrue();
            assertThat(row.getCell(4).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(DAY);
        }
        mvc.perform(multipart("/api/import/prices.xlsx").file(file(history)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(0))
            .andExpect(jsonPath("$.duplicatesSkipped").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_records", Integer.class)).isEqualTo(2);
    }

    @Test void rejectsRowsAtomicallyAndPreservesPermissions() throws Exception {
        var source = workbook(Long.toString(PRODUCT + 1), false);
        mvc.perform(multipart("/api/import/prices.xlsx").file(file(source)).with(user("reader").roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/import/prices-template.xlsx").with(user("reader").roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/import/prices.xlsx").file(file(source)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Строка 3: товар с ID " + (PRODUCT + 1) + " не найден"));
        mvc.perform(multipart("/api/import/prices.xlsx").file(file(workbook(Long.toString(PRODUCT), true))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Некорректные значения в строке 3"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_records", Integer.class)).isZero();
    }

    @Test void downloadsTemplateAndRejectsAnalyticalReportAsImport() throws Exception {
        byte[] template = mvc.perform(get("/api/admin/import/prices-template.xlsx")).andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", "attachment; filename=price-import-template.xlsx"))
            .andReturn().getResponse().getContentAsByteArray();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            var sheet = book.getSheetAt(0);
            var header = sheet.getRow(0);
            assertThat(sheet.getLastRowNum()).isZero();
            assertThat(IntStream.range(0, header.getLastCellNum()).mapToObj(i -> header.getCell(i).getStringCellValue()).toList())
                .containsExactly("productId", "store", "price", "date");
        }
        var report = mvc.perform(get("/api/reports.xlsx").param("group", "product")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        mvc.perform(multipart("/api/import/prices.xlsx").file(file(report))).andExpect(status().isBadRequest());
    }

    private static MockMultipartFile file(byte[] workbook) {
        return new MockMultipartFile("file", "history.xlsx", MIME, workbook);
    }

    private static byte[] workbook(String secondId, boolean invalidDate) throws Exception {
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("Цены");
            var header = sheet.createRow(0);
            var columns = new String[]{"productId", "store", "price", "date"};
            for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
            var first = sheet.createRow(1);
            first.createCell(0).setCellValue(Long.toString(PRODUCT));
            first.createCell(1).setCellValue("Магазин «Ёлка»");
            first.createCell(2).setCellValue(125.40);
            first.createCell(3).setCellValue(DAY.toString());
            var second = sheet.createRow(2);
            second.createCell(0).setCellValue(secondId);
            second.createCell(1).setCellValue("МАГАЗИН «ЁЛКА»");
            second.createCell(2).setCellValue(126.90);
            var day = second.createCell(3);
            if (invalidDate) {
                day.setCellValue("19.13.2026");
            } else {
                day.setCellValue(DAY);
                var format = book.createCellStyle();
                format.setDataFormat(book.createDataFormat().getFormat("dd.mm.yyyy"));
                day.setCellStyle(format);
            }
            book.write(output);
            return output.toByteArray();
        }
    }
}
