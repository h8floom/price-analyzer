package ru.priceanalyzer.service;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PriceRetention {
    private final JdbcTemplate jdbc;
    private final int defaultDays;

    PriceRetention(JdbcTemplate jdbc, @Value("${app.prices.retention-days:0}") int days) {
        if (days < 0) throw new IllegalArgumentException("app.prices.retention-days must be non-negative");
        this.jdbc = jdbc;
        this.defaultDays = days;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void expirePrices() {
        purge(LocalDate.now(Clock.systemDefaultZone()));
    }

    int purge(LocalDate today) {
        int days = configuredDays();
        if (days == 0) return 0;
        return jdbc.update("DELETE FROM price_records WHERE observed_on < ?", today.minusDays(days));
    }

    public int configuredDays() {
        Integer stored = jdbc.queryForObject("SELECT retention_days FROM price_admin_settings WHERE id=1", Integer.class);
        return stored == null ? defaultDays : stored;
    }

    public void setDays(int days) {
        jdbc.update("UPDATE price_admin_settings SET retention_days=? WHERE id=1", days);
    }
}
