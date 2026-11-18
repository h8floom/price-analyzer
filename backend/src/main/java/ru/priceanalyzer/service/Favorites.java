package ru.priceanalyzer.service;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.priceanalyzer.api.ApiProblem;

@Service
public class Favorites {
    private final JdbcTemplate jdbc;

    public Favorites(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> productIds(String email) {
        return jdbc.queryForList("""
            SELECT f.product_id FROM favorites f JOIN accounts a ON f.account_id=a.id
            WHERE a.email=? ORDER BY f.product_id
            """, Long.class, email);
    }

    @Transactional
    public void add(String email, long productId) {
        if (jdbc.queryForList("SELECT id FROM products WHERE id=? FOR UPDATE", Long.class, productId).isEmpty()) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "Товар не найден");
        }
        long accountId = jdbc.queryForObject("SELECT id FROM accounts WHERE email=? FOR UPDATE", Long.class, email);
        jdbc.update("""
            INSERT INTO favorites (account_id, product_id)
            SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM favorites WHERE account_id=? AND product_id=?)
            """, accountId, productId, accountId, productId);
    }

    public void remove(String email, long productId) {
        jdbc.update("""
            DELETE FROM favorites WHERE product_id=? AND account_id=(SELECT id FROM accounts WHERE email=?)
            """, productId, email);
    }
}
