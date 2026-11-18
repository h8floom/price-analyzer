package ru.priceanalyzer.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class CatalogApiCasesTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static final String COFFEE = "{\"name\":\"Кофе\",\"category\":\"Напитки\",\"unit\":\"шт.\"}";

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM price_records");
        jdbc.update("DELETE FROM products");
    }

    @Test void updateProductAndRejectDuplicateName() throws Exception {
        jdbc.update("INSERT INTO products(id,name,category,unit) VALUES(501,'Кофе','Напитки','шт.')");
        jdbc.update("INSERT INTO products(id,name,category,unit) VALUES(502,'Чай','Напитки','шт.')");
        mvc.perform(put("/api/products/501").contentType(MediaType.APPLICATION_JSON).with(csrf())
                .content("{\"name\":\"Кофе молотый\",\"category\":\"Продукты\",\"unit\":\"уп.\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Кофе молотый"))
            .andExpect(jsonPath("$.category").value("Продукты"));
        mvc.perform(put("/api/products/502").contentType(MediaType.APPLICATION_JSON).with(csrf())
                .content("{\"name\":\"кофе молотый\",\"category\":\"Продукты\",\"unit\":\"уп.\"}"))
            .andExpect(status().isConflict());
    }

    @Test void oldCatalogUpdateDoesNotDiscardDescription() throws Exception {
        jdbc.update("INSERT INTO products(id,name,category,unit,description) VALUES(503,'Товар','Продукты','шт.','Описание')");
        mvc.perform(put("/api/products/503").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Товар\",\"category\":\"Продукты\",\"unit\":\"кг\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("Описание"));
    }

    @Test void rejectBlankProductAndUnknownProduct() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).with(csrf())
                .content("{\"name\":\"  \",\"category\":\"Напитки\",\"unit\":\"шт.\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/products/999999").contentType(MediaType.APPLICATION_JSON).with(csrf()).content(COFFEE))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/products/999999").with(csrf())).andExpect(status().isNotFound());
    }

    @Test void rejectsUnknownProductForPriceAndNonpositivePrice() throws Exception {
        mvc.perform(post("/api/prices").contentType(MediaType.APPLICATION_JSON).with(csrf())
                .content("{\"productId\":999999,\"store\":\"Лента\",\"price\":15,\"date\":\"2026-10-01\"}"))
            .andExpect(status().isNotFound());
        jdbc.update("INSERT INTO products(id,name,category,unit) VALUES(503,'Чай','Напитки','шт.')");
        mvc.perform(post("/api/prices").contentType(MediaType.APPLICATION_JSON).with(csrf())
                .content("{\"productId\":503,\"store\":\"Лента\",\"price\":-4,\"date\":\"2026-10-01\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/prices").contentType(MediaType.APPLICATION_JSON).with(csrf())
                .content("{\"productId\":503,\"store\":\"\",\"price\":10,\"date\":\"2026-10-01\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void filtersPricesByDateAndRejectsReverseDates() throws Exception {
        jdbc.update("INSERT INTO products(id,name,category,unit) VALUES(504,'Чай','Напитки','шт.')");
        jdbc.update("INSERT INTO price_records(product_id,store,price,observed_on) VALUES(504,'Лента',100,'2026-10-01')");
        jdbc.update("INSERT INTO price_records(product_id,store,price,observed_on) VALUES(504,'Лента',120,'2026-10-15')");
        mvc.perform(get("/api/prices").param("productId","504").param("from","2026-10-10").param("to","2026-10-20"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].store").value("Лента"));
        mvc.perform(get("/api/prices").param("from","2026-10-20").param("to","2026-10-10"))
            .andExpect(status().isBadRequest());
    }

    @Test void deletingProductAlsoDeletesItsPrices() throws Exception {
        jdbc.update("INSERT INTO products(id,name,category,unit) VALUES(505,'Чай','Напитки','шт.')");
        jdbc.update("INSERT INTO price_records(product_id,store,price,observed_on) VALUES(505,'Лента',120,'2026-10-15')");
        mvc.perform(delete("/api/products/505").with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/prices").param("productId","505"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test void duplicateCsvImportSkippedAndInvalidCsvRejected() throws Exception {
        jdbc.update("INSERT INTO products(id,name,category,unit) VALUES(506,'Чай','Напитки','шт.')");
        String csv = "productId,store,price,date\n506,Лента,77.00,2026-10-01\n";
        mvc.perform(post("/api/import/prices").contentType("text/csv").with(csrf()).content(csv))
            .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(1));
        mvc.perform(post("/api/import/prices").contentType("text/csv").with(csrf()).content(csv))
            .andExpect(status().isOk()).andExpect(jsonPath("$.duplicatesSkipped").value(1));
        mvc.perform(post("/api/import/prices").contentType("text/csv").with(csrf()).content("bad,header\n1,2\n"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/import/prices").contentType("text/csv").with(csrf()).content("productId,store,price,date\n506,\"Лента,77,2026-10-01\n"))
            .andExpect(status().isBadRequest());
    }
}
