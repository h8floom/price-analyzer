package ru.priceanalyzer.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.Row;
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
class PriceImportApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void setUp() {
        jdbc.update("DELETE FROM price_records");
        jdbc.update("DELETE FROM products");
        jdbc.update("INSERT INTO products(id,name,category,unit) VALUES(801,'Чай','Напитки','шт.')");
    }

    @Test
    void acceptsExcelDateAndSkipsExistingPrice() throws Exception {
        var xlsx = prices(false);
        mvc.perform(multipart("/api/import/prices.xlsx").file(xlsx))
            .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(1));
        mvc.perform(multipart("/api/import/prices.xlsx").file(xlsx))
            .andExpect(status().isOk()).andExpect(jsonPath("$.duplicatesSkipped").value(1));
        mvc.perform(get("/api/prices").param("productId","801"))
            .andExpect(jsonPath("$[0].date").value("2026-10-15"))
            .andExpect(jsonPath("$[0].price").value(132.75));
    }

    @Test
    void rejectsInvalidXlsxRowAndRollsBackEarlierRows() throws Exception {
        mvc.perform(multipart("/api/import/prices.xlsx").file(prices(true)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
        mvc.perform(get("/api/prices").param("productId","801"))
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void acceptsCsvBomAndRejectsUnparseableWorkbook() throws Exception {
        mvc.perform(post("/api/import/prices").contentType("text/csv;charset=UTF-8")
            .content("\uFEFFproductId,store,price,date\n801,Маркет,90.00,2026-10-15\n"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(1));
        var malformed = new MockMultipartFile("file", "broken.xlsx", "application/octet-stream", new byte[]{1, 2, 3});
        mvc.perform(multipart("/api/import/prices.xlsx").file(malformed))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
    }

    private MockMultipartFile prices(boolean invalidSecondRow) throws Exception {
        try (var wb = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Цены");
            Row hdr = sheet.createRow(0);
            String[] headers = {"productId", "store", "price", "date"};
            for (int i = 0; i < headers.length; i++) hdr.createCell(i).setCellValue(headers[i]);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(801);
            row.createCell(1).setCellValue("Маркет");
            row.createCell(2).setCellValue(132.75);
            var dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
            var date = row.createCell(3);
            date.setCellValue(LocalDate.of(2026, 10, 15));
            date.setCellStyle(dateStyle);
            if (invalidSecondRow) {
                Row invalid = sheet.createRow(2);
                invalid.createCell(0).setCellValue(801);
                invalid.createCell(1).setCellValue("Другой магазин");
                invalid.createCell(2).setCellValue(-1);
                invalid.createCell(3).setCellValue("2026-10-16");
            }
            wb.write(output);
            return new MockMultipartFile("file", "prices.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }
}
