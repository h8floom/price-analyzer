package ru.priceanalyzer.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.priceanalyzer.api.ApiProblem;
import ru.priceanalyzer.domain.PriceEntry;
import ru.priceanalyzer.domain.Product;

@Service
public class PriceNotifications {
    private static final int PAGE_SIZE = 30;
    private final JdbcTemplate jdbc;

    public PriceNotifications(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum Mode { ALL, DECREASE_ONLY, OFF }
    public record Notice(long id, Long productId, String productName, String store, BigDecimal oldPrice,
        BigDecimal newPrice, LocalDate observedOn, OffsetDateTime createdAt, boolean read) {}
    public record Inbox(List<Notice> items, Long nextBefore, Mode mode) {}

    void recordChange(Product product, PriceEntry previous, PriceEntry current) {
        if (previous == null || current.date().isBefore(previous.date())) return;

        int direction = current.price().compareTo(previous.price());
        if (direction == 0) return;
        jdbc.update("""
            INSERT INTO price_notifications
                (account_id, price_record_id, product_id, product_name, store, old_price, new_price, observed_on)
            SELECT f.account_id, ?, ?, ?, ?, ?, ?, ?
            FROM favorites f JOIN accounts a ON a.id=f.account_id
            WHERE f.product_id=? AND (a.notification_mode='ALL' OR (a.notification_mode='DECREASE_ONLY' AND ?))
            """, current.id(), product.id(), product.name(), current.store(), previous.price(), current.price(),
            current.date(), product.id(), direction < 0);
    }

    public Inbox inbox(String email, Long before) {
        var rows = jdbc.query("""
            SELECT n.id, n.product_id, n.product_name, n.store, n.old_price, n.new_price, n.observed_on,
                   n.created_at, n.read_at
            FROM price_notifications n JOIN accounts a ON a.id=n.account_id
            WHERE a.email=? AND n.id<? ORDER BY n.id DESC LIMIT ?
            """, (rs, row) -> new Notice(rs.getLong("id"), rs.getObject("product_id", Long.class),
                rs.getString("product_name"), rs.getString("store"), rs.getBigDecimal("old_price"),
                rs.getBigDecimal("new_price"), rs.getObject("observed_on", LocalDate.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("read_at") != null),
                email, before == null ? Long.MAX_VALUE : before, PAGE_SIZE + 1);
        boolean more = rows.size() > PAGE_SIZE;
        var items = more ? rows.subList(0, PAGE_SIZE) : rows;
        var mode = Mode.valueOf(jdbc.queryForObject("SELECT notification_mode FROM accounts WHERE email=?",
            String.class, email));
        return new Inbox(items, more ? items.getLast().id() : null, mode);
    }

    public long unread(String email) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM price_notifications
            WHERE account_id=(SELECT id FROM accounts WHERE email=?) AND read_at IS NULL
            """, Long.class, email);
    }

    public void markRead(String email, long id) {
        int updated = jdbc.update("""
            UPDATE price_notifications SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP)
            WHERE id=? AND account_id=(SELECT id FROM accounts WHERE email=?)
            """, id, email);
        if (updated == 0) throw new ApiProblem(HttpStatus.NOT_FOUND, "Уведомление не найдено");
    }

    public void setMode(String email, Mode mode) {
        jdbc.update("UPDATE accounts SET notification_mode=? WHERE email=?", mode.name(), email);
    }
}
