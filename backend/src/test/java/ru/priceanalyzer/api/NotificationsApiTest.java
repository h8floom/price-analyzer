package ru.priceanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:notifications;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationsApiTest {
    private static final String ALL = "all@example.test";
    private static final String LOWER = "decrease@example.test";
    private static final String OFF = "off@example.test";
    private static final String STRANGER = "stranger@example.test";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void fixture() throws Exception {
        jdbc.update("DELETE FROM accounts");
        jdbc.update("DELETE FROM products");
        jdbc.update("INSERT INTO products(id,name,category,unit) VALUES(710,'Чай с бергамотом','Напитки','уп.')");
        for (String email : new String[] { ALL, LOWER, OFF, STRANGER }) {
            jdbc.update("INSERT INTO accounts(email,password_hash) VALUES (?,'fixture')", email);
        }
        for (String email : new String[] {ALL, LOWER, OFF}) {
            mvc.perform(put("/api/favorites/710").with(user(email)).with(csrf())).andExpect(status().isNoContent());
        }
        mode(LOWER, "DECREASE_ONLY");
        mode(OFF, "OFF");
    }

    @Test
    void manualChangesKeepHistoryAndDoNotDuplicateNotificationsOnRetry() throws Exception {
        long first = addPrice(100, "2026-11-17");
        count(ALL, 0);
        long changed = editPrice(first, 120);
        assertThat(editPrice(first, 120)).isEqualTo(changed);
        assertThat(editPrice(changed, 120)).isEqualTo(changed);
        count(ALL, 1);
        count(LOWER, 0);
        count(OFF, 0);
        count(STRANGER, 0);

        mvc.perform(put("/api/prices/{id}", first).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"price\":200}"))
            .andExpect(status().isConflict());
        editPrice(changed, 100);
        mvc.perform(get("/api/prices").with(user(ALL)).param("productId", "710"))
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].price").value(100.0)).andExpect(jsonPath("$[1].price").value(120.0))
            .andExpect(jsonPath("$[2].price").value(100.0));
        count(ALL, 2);
        count(LOWER, 1);
        count(OFF, 0);
        mvc.perform(get("/api/notifications").with(user(ALL))).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].productId").value(710))
            .andExpect(jsonPath("$.items[0].productName").value("Чай с бергамотом"))
            .andExpect(jsonPath("$.items[0].oldPrice").value(120.0))
            .andExpect(jsonPath("$.items[0].newPrice").value(100.0))
            .andExpect(jsonPath("$.items[0].store").value("Market"))
            .andExpect(jsonPath("$.items[0].observedOn").value("2026-11-17"))
            .andExpect(jsonPath("$.items[0].createdAt").isString());
    }

    @Test
    void csvAndXlsxChangesRespectOptionsAndRepeatedImportsDoNotDuplicate() throws Exception {
        addPrice(150, "2026-11-17");
        String csv = header() + "710,market,160.00,2026-11-17\n710,Market,140,2026-11-16\n";
        importCsv(csv, 2, 0);
        importCsv(csv, 0, 2);
        count(ALL, 1);
        count(LOWER, 0);
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(multipart("/api/import/prices.xlsx").file(workbook(135)).with(admin()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(1 - attempt))
                .andExpect(jsonPath("$.duplicatesSkipped").value(attempt));
        }
        importCsv(header() + "710,Market,135.00,2026-11-19\n", 1, 0);
        count(ALL, 2);
        count(LOWER, 1);
        count(OFF, 0);
        count(STRANGER, 0);
        mvc.perform(get("/api/notifications").with(user(LOWER))).andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("DECREASE_ONLY"))
            .andExpect(jsonPath("$.items[0].oldPrice").value(160.0))
            .andExpect(jsonPath("$.items[0].newPrice").value(135.0));
    }

    @Test
    void onlyOwnerMayReadNotificationEvenWhenRequestClaimsAnotherIdentity() throws Exception {
        addPrice(160, "2026-11-16");
        addPrice(130, "2026-11-17");
        var response = mvc.perform(get("/api/notifications").with(user(ALL))).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].accountId").doesNotExist())
            .andExpect(jsonPath("$.items[0].email").doesNotExist()).andReturn().getResponse();
        long id = json.readTree(response.getContentAsByteArray()).path("items").get(0).path("id").asLong();

        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/notifications/{id}/read", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications").with(user(STRANGER)).param("email", ALL).param("accountId", "1"))
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(put("/api/notifications/{id}/read", id).with(user(LOWER)).with(csrf()).param("email", ALL))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api/notifications/{id}/read", id).with(user(ALL))).andExpect(status().isForbidden());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(put("/api/notifications/{id}/read", id).with(user(ALL)).with(csrf()))
                .andExpect(status().isNoContent());
        }
        count(ALL, 0);
        count(LOWER, 1);
        mvc.perform(get("/api/notifications").with(user(ALL))).andExpect(jsonPath("$.items[0].read").value(true));
        mvc.perform(put("/api/notifications/settings").with(user(STRANGER)).with(csrf()).param("email", ALL)
            .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"OFF\",\"email\":\"" + ALL + "\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications").with(user(ALL))).andExpect(jsonPath("$.mode").value("ALL"));
    }

    @Test
    void rolledBackImportDoesNotLeavePricesOrNotificationsAndUnfollowingStopsAlerts() throws Exception {
        addPrice(100, "2026-11-16");
        mvc.perform(post("/api/import/prices").with(admin()).with(csrf()).contentType("text/csv")
            .content(header() + "710,Market,150,2026-11-17\n999999,Market,90,2026-11-17\n"))
            .andExpect(status().isNotFound());
        count(ALL, 0);
        mvc.perform(get("/api/prices").with(user(ALL)).param("productId", "710"))
            .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/api/favorites/710").with(user(ALL)).with(csrf())).andExpect(status().isNoContent());
        addPrice(90, "2026-11-17");
        count(ALL, 0);
        count(LOWER, 1);
    }

    @Test
    void olderPagesAndCountsRemainScopedAndInvalidSettingsAreRejected() throws Exception {
        long accountId = jdbc.queryForObject("SELECT id FROM accounts WHERE email=?", Long.class, ALL);
        for (int record = 1; record <= 31; record++) {
            jdbc.update("""
                INSERT INTO price_notifications
                    (account_id,price_record_id,product_id,product_name,store,old_price,new_price,observed_on)
                VALUES (?, ?, 710, 'Чай', 'Market', 100, 90, DATE '2026-11-17')
                """, accountId, record);
        }
        var response = mvc.perform(get("/api/notifications").with(user(ALL)))
            .andExpect(jsonPath("$.items.length()").value(30)).andReturn().getResponse();
        String cursor = json.readTree(response.getContentAsByteArray()).path("nextBefore").asText();
        mvc.perform(get("/api/notifications").with(user(ALL)).param("before", cursor))
            .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/notifications").with(user(STRANGER)).param("before", cursor).param("email", ALL))
            .andExpect(jsonPath("$.items.length()").value(0));
        count(ALL, 31);
        count(STRANGER, 0);
        mvc.perform(put("/api/notifications/settings").with(user(ALL)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"UNKNOWN\"}"))
            .andExpect(status().isBadRequest());
    }

    private UserRequestPostProcessor admin() { return user("admin@example.test").roles("ADMIN"); }

    private void count(String email, int expected) throws Exception {
        mvc.perform(get("/api/notifications/unread").with(user(email)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(expected));
    }

    private void mode(String email, String value) throws Exception {
        mvc.perform(put("/api/notifications/settings").with(user(email)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"" + value + "\"}"))
            .andExpect(status().isNoContent());
    }

    private long addPrice(int amount, String date) throws Exception {
        var result = mvc.perform(post("/api/prices").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"productId\":710,\"store\":\"Market\",\"price\":" + amount + ",\"date\":\"" + date + "\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(result.getContentAsByteArray()).path("id").asLong();
    }

    private long editPrice(long id, int amount) throws Exception {
        var result = mvc.perform(put("/api/prices/{id}", id).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"price\":" + amount + "}"))
            .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(result.getContentAsByteArray()).path("id").asLong();
    }

    private String header() { return "productId,store,price,date\n"; }

    private void importCsv(String csv, int imported, int duplicates) throws Exception {
        mvc.perform(post("/api/import/prices").with(admin()).with(csrf()).contentType("text/csv").content(csv))
            .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(imported))
            .andExpect(jsonPath("$.duplicatesSkipped").value(duplicates));
    }

    private MockMultipartFile workbook(double amount) throws Exception {
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var header = sheet.createRow(0);
            String[] names = {"productId", "store", "price", "date"};
            for (int index = 0; index < names.length; index++) header.createCell(index).setCellValue(names[index]);
            var entry = sheet.createRow(1);
            entry.createCell(0).setCellValue(710);
            entry.createCell(1).setCellValue("Market");
            entry.createCell(2).setCellValue(amount);
            entry.createCell(3).setCellValue(LocalDate.of(2026, 11, 18).toString());
            workbook.write(bytes);
            return new MockMultipartFile("file", "changes.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray());
        }
    }
}
