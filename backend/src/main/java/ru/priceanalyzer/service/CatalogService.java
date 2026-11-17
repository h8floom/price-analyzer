package ru.priceanalyzer.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.priceanalyzer.api.ApiProblem;
import ru.priceanalyzer.domain.PriceEntry;
import ru.priceanalyzer.domain.PriceMath;
import ru.priceanalyzer.domain.Product;

@Service
public class CatalogService {
    private final JdbcTemplate jdbc;

    private static final RowMapper<Product> PRODUCT_ROW = (rs, row) ->
        new Product(rs.getLong("id"), rs.getString("name"), rs.getString("category"), rs.getString("unit"));
    private static final RowMapper<PriceEntry> PRICE_ROW = (rs, row) ->
        new PriceEntry(rs.getLong("id"), rs.getLong("product_id"), rs.getString("store"),
                rs.getBigDecimal("price"), rs.getDate("observed_on").toLocalDate());

    public CatalogService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private String required(String value, String title, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, title + ": обязательное поле (до " + maxLength + " символов)");
        }
        return value.trim();
    }

    public List<Product> products() {
        return jdbc.query("SELECT id, name, category, unit FROM products ORDER BY id", PRODUCT_ROW);
    }

    public Product product(long id) {
        return jdbc.query("SELECT id, name, category, unit FROM products WHERE id=?", PRODUCT_ROW, id)
                .stream().findFirst()
                .orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "Товар не найден"));
    }

    @Transactional
    public Product createProduct(String name, String category, String unit) {
        name = required(name, "Название", 240);
        category = required(category, "Категория", 120);
        unit = required(unit, "Единица", 64);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE LOWER(name)=LOWER(?)",
                Integer.class, name) > 0) {
            throw new ApiProblem(HttpStatus.CONFLICT, "Товар с таким названием уже существует");
        }
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        final String n=name, c=category, u=unit;
        jdbc.update(con -> {
            PreparedStatement ps=con.prepareStatement(
                "INSERT INTO products(name,category,unit) VALUES(?,?,?)", new String[]{"id"});
            ps.setString(1,n); ps.setString(2,c); ps.setString(3,u);
            return ps;
        }, key);
        return product(Objects.requireNonNull(key.getKey()).longValue());
    }

    @Transactional
    public Product updateProduct(long id, String name, String category, String unit) {
        product(id);
        name = required(name, "Название", 240);
        category = required(category, "Категория", 120);
        unit = required(unit, "Единица", 64);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE LOWER(name)=LOWER(?) AND id<>?",
                Integer.class, name, id) > 0) {
            throw new ApiProblem(HttpStatus.CONFLICT, "Товар с таким названием уже существует");
        }
        jdbc.update("UPDATE products SET name=?, category=?, unit=? WHERE id=?", name, category, unit, id);
        return product(id);
    }

    @Transactional
    public void deleteProduct(long id) {
        if (jdbc.update("DELETE FROM products WHERE id=?", id) == 0)
            throw new ApiProblem(HttpStatus.NOT_FOUND, "Товар не найден");
    }

    public List<PriceEntry> prices(Long productId, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "Начальная дата больше конечной");
        }
        StringBuilder sql = new StringBuilder("SELECT id,product_id,store,price,observed_on FROM price_records WHERE 1=1");
        List<Object> args=new ArrayList<>();
        if (productId != null) { sql.append(" AND product_id=?"); args.add(productId); }
        if (from != null) { sql.append(" AND observed_on>=?"); args.add(Date.valueOf(from)); }
        if (to != null) { sql.append(" AND observed_on<=?"); args.add(Date.valueOf(to)); }
        sql.append(" ORDER BY observed_on,id");
        return jdbc.query(sql.toString(), PRICE_ROW, args.toArray());
    }

    @Transactional
    public PriceEntry addPrice(long productId, String store, BigDecimal price, LocalDate date) {
        product(productId);
        store = required(store, "Магазин", 160);
        if (price == null || price.signum() <= 0 || price.scale() > 2 || price.precision() - price.scale() > 10) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "Цена должна быть положительным числом с максимум 2 знаками после запятой");
        }
        if (date == null) throw new ApiProblem(HttpStatus.BAD_REQUEST, "Нужно указать дату");
        if (jdbc.queryForObject(
                "SELECT COUNT(*) FROM price_records WHERE product_id=? AND LOWER(store)=LOWER(?) AND observed_on=?",
                Integer.class, productId, store, Date.valueOf(date)) > 0) {
            throw new ApiProblem(HttpStatus.CONFLICT, "Цена для товара, магазина и даты уже существует");
        }
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        final String finalStore=store;
        jdbc.update(con -> {
            PreparedStatement ps=con.prepareStatement(
                "INSERT INTO price_records(product_id,store,price,observed_on) VALUES(?,?,?,?)",
                new String[]{"id"});
            ps.setLong(1,productId); ps.setString(2,finalStore);
            ps.setBigDecimal(3,price); ps.setDate(4,Date.valueOf(date));
            return ps;
        }, key);
        long id=Objects.requireNonNull(key.getKey()).longValue();
        return jdbc.query("SELECT id,product_id,store,price,observed_on FROM price_records WHERE id=?",
            PRICE_ROW,id).get(0);
    }

    @Transactional
    public void deletePrice(long id) {
        if(jdbc.update("DELETE FROM price_records WHERE id=?", id)==0)
            throw new ApiProblem(HttpStatus.NOT_FOUND, "Запись о цене не найдена");
    }

    public Map<String,Object> analysis(long productId, LocalDate from, LocalDate to) {
        Product selected=product(productId);
        List<PriceEntry> history=prices(productId,from,to);
        List<PriceEntry> latest=PriceMath.latestByStore(history);
        return Map.of("product",selected, "history",history,
            "latest",latest,"stats",PriceMath.summarize(latest));
    }

    public String exportCsv(Long productId) {
        List<PriceEntry> records=prices(productId,null,null);
        StringBuilder out=new StringBuilder("productId,store,price,date\n");
        for (PriceEntry row: records) {
            out.append(row.productId()).append(',')
               .append(csvEscape(row.store())).append(',')
               .append(row.price()).append(',')
               .append(row.date()).append('\n');
        }
        return out.toString();
    }

    public static String csvEscape(String value) {
        return "\"" + value.replace("\"","\"\"") + "\"";
    }

    /** Учебный импорт: заголовок productId,store,price,date; кавычки допускаются. */
    @Transactional
    public Map<String,Integer> importCsv(String input) {
        if (input==null || input.isBlank()) throw new ApiProblem(HttpStatus.BAD_REQUEST, "Файл CSV пуст");
        List<List<String>> rows=CsvParser.parse(input);
        if (rows.isEmpty() || !rows.get(0).equals(List.of("productId","store","price","date")))
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "Заголовок CSV должен быть productId,store,price,date");
        int imported=0, skipped=0;
        for (int i=1;i<rows.size();i++) {
            List<String> row=rows.get(i);
            if (row.size()==1 && row.get(0).isBlank()) continue;
            if (row.size()!=4) throw new ApiProblem(HttpStatus.BAD_REQUEST, "Неверное число полей CSV в строке "+(i+1));
            try {
                long productId = Long.parseLong(row.get(0));
                LocalDate day = LocalDate.parse(row.get(3));
                String store = row.get(1).trim();
                int duplicate = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM price_records WHERE product_id=? AND LOWER(store)=LOWER(?) AND observed_on=?",
                    Integer.class, productId, store, Date.valueOf(day));
                if (duplicate > 0) {
                    skipped++;
                } else {
                    addPrice(productId, store, new BigDecimal(row.get(2)), day);
                    imported++;
                }
            } catch (NumberFormatException | java.time.format.DateTimeParseException ex) {
                throw new ApiProblem(HttpStatus.BAD_REQUEST,"Некорректные значения CSV в строке "+(i+1));
            }
        }
        return Map.of("imported",imported,"duplicatesSkipped",skipped);
    }
}
