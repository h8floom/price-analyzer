package ru.priceanalyzer.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FavoritesApiTest {
    private static final String FIRST = "favorite-first@example.test";
    private static final String SECOND = "favorite-second@example.test";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void fixture() {
        jdbc.update("INSERT INTO accounts(email,password_hash) VALUES (?,'fixture'), (?,'fixture')", FIRST, SECOND);
        jdbc.update("""
            INSERT INTO products(id,name,category,unit) VALUES
            (620,'Кофе для избранного','Напитки','кг'), (621,'Чай для избранного','Напитки','кг')
            """);
    }

    @Test
    void favoritesAreIdempotentAndScopedToTheAuthenticatedAccount() throws Exception {
        mvc.perform(put("/api/favorites/620").with(user(FIRST)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(put("/api/favorites/620").with(user(FIRST)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(put("/api/favorites/621").with(user(SECOND)).with(csrf()).param("email", FIRST))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/favorites").with(user(FIRST)))
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0]").value(620));
        mvc.perform(get("/api/favorites").with(user(SECOND)).param("userId", "1").param("email", FIRST))
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0]").value(621));
        mvc.perform(delete("/api/favorites/620").with(user(SECOND)).with(csrf()).param("email", FIRST))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/favorites").with(user(FIRST))).andExpect(jsonPath("$[0]").value(620));
        mvc.perform(delete("/api/favorites/620").with(user(FIRST)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/favorites").with(user(FIRST))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deniesGuestsAndHandlesMissingOrDeletedProducts() throws Exception {
        mvc.perform(get("/api/favorites")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/favorites/620")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/favorites/999").with(user(FIRST)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(put("/api/favorites/620").with(user(FIRST))).andExpect(status().isForbidden());
        mvc.perform(put("/api/favorites/620").with(user(FIRST)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/products/620").with(user(SECOND).roles("ADMIN")).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/favorites").with(user(FIRST))).andExpect(jsonPath("$.length()").value(0));
    }
}
