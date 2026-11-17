package ru.priceanalyzer.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void cleanDatabase() {
        jdbc.update("DELETE FROM price_records");
        jdbc.update("DELETE FROM products");
    }

    @Test void healthReturnsOk() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test void createAndReadProduct() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Кофе\",\"category\":\"Напитки\",\"unit\":\"шт.\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Кофе"));
        mvc.perform(get("/api/products")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Кофе"));
    }

    @Test void duplicateProductReturnsConflict() throws Exception {
        String data="{\"name\":\"Кофе\",\"category\":\"Напитки\",\"unit\":\"шт.\"}";
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(data))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(data))
            .andExpect(status().isConflict());
    }

    @Test void fullPriceScenario() throws Exception {
        jdbc.update("INSERT INTO products(id,name,category,unit) VALUES(201,'Кофе','Напитки','шт.')");
        String data="{\"productId\":201,\"store\":\"Магнит\",\"price\":349.90,\"date\":\"2026-10-03\"}";
        mvc.perform(post("/api/prices").contentType(MediaType.APPLICATION_JSON).content(data))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/prices").contentType(MediaType.APPLICATION_JSON).content(data))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/analysis/201")).andExpect(status().isOk())
            .andExpect(jsonPath("$.stats.count").value(1))
            .andExpect(jsonPath("$.latest[0].store").value("Магнит"));
        mvc.perform(get("/api/export/prices.csv").param("productId","201"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("349.90")));
    }

    @Test void invalidPriceReturnsBadRequest() throws Exception {
        jdbc.update("INSERT INTO products(id,name,category,unit) VALUES(202,'Чай','Напитки','шт.')");
        mvc.perform(post("/api/prices").contentType(MediaType.APPLICATION_JSON)
            .content("{\"productId\":202,\"store\":\"Лента\",\"price\":0,\"date\":\"2026-10-03\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void csvImportAcceptsQuotedStore() throws Exception {
        jdbc.update("INSERT INTO products(id,name,category,unit) VALUES(203,'Молоко','Продукты','л')");
        String csv="productId,store,price,date\n203,\"Магазин, рядом\",85.40,2026-10-03\n";
        mvc.perform(post("/api/import/prices").contentType("text/csv").content(csv))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imported").value(1));
        mvc.perform(get("/api/prices").param("productId","203"))
            .andExpect(jsonPath("$[0].store").value("Магазин, рядом"));
    }
}
