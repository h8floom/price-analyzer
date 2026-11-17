package ru.priceanalyzer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:retention_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@Transactional
class PriceRetentionTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void optInRetentionKeepsBoundaryAndDoesNotChangeDefaultHistory() {
        jdbc.update("INSERT INTO products(id,name,category,unit) VALUES (901,'Зёрна','Продукты','кг')");
        LocalDate today = LocalDate.of(2026, 11, 18);
        for (int age : new int[]{30,31}) {
            jdbc.update("INSERT INTO price_records(product_id,store,price,observed_on) VALUES (901,'Магазин',10,?)",
                today.minusDays(age));
        }
        assertThat(new PriceRetention(jdbc, 0).purge(today)).isZero();
        assertThat(new PriceRetention(jdbc, 30).purge(today)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT observed_on FROM price_records WHERE product_id=901", LocalDate.class))
            .isEqualTo(today.minusDays(30));
    }
}
