package ru.priceanalyzer.service;

import java.io.InputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.priceanalyzer.api.ApiProblem;
import ru.priceanalyzer.domain.PriceEntry;
import ru.priceanalyzer.domain.PriceMath;
import ru.priceanalyzer.domain.Product;

@Service
public class CatalogService {
    private final JdbcTemplate jdbc;
    private final PriceNotifications notifications;

    private static final RowMapper<Product> PRODUCT_ROW = (rs, row) ->
        new Product(rs.getLong("id"), rs.getString("name"), rs.getString("category"), rs.getString("unit"), rs.getString("description"));
    private static final RowMapper<PriceEntry> PRICE_ROW = (rs, row) ->
        new PriceEntry(rs.getLong("id"), rs.getLong("product_id"), rs.getString("store"),
                rs.getBigDecimal("price"), rs.getDate("observed_on").toLocalDate());

    public CatalogService(JdbcTemplate jdbc, PriceNotifications notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    private String required(String value, String title, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, title + ": обязательное поле (до " + maxLength + " символов)");
        }
        return value.trim();
    }

    public List<Product> products() {
        return jdbc.query("SELECT id, name, category, unit, description FROM products ORDER BY id", PRODUCT_ROW);
    }

    public Product product(long id) {
        return jdbc.query("SELECT id, name, category, unit, description FROM products WHERE id=?", PRODUCT_ROW, id)
                .stream().findFirst()
                .orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "Товар не найден"));
    }

    @Transactional
    public Product createProduct(String name, String category, String unit, String description) {
        name = required(name, "Название", 240);
        category = required(category, "Категория", 120);
        unit = required(unit, "Единица", 64);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE LOWER(name)=LOWER(?)",
                Integer.class, name) > 0) {
            throw new ApiProblem(HttpStatus.CONFLICT, "Товар с таким названием уже существует");
        }
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        final String n=name, c=category, u=unit, details=validatedDescription(description);
        jdbc.update(con -> {
            PreparedStatement ps=con.prepareStatement(
                "INSERT INTO products(name,category,unit,description) VALUES(?,?,?,?)", new String[]{"id"});
            ps.setString(1,n); ps.setString(2,c); ps.setString(3,u); ps.setString(4,details);
            return ps;
        }, key);
        return product(Objects.requireNonNull(key.getKey()).longValue());
    }

    @Transactional
    public Product updateProduct(long id, String name, String category, String unit, String description) {
        Product current = product(id);
        name = required(name, "Название", 240);
        category = required(category, "Категория", 120);
        unit = required(unit, "Единица", 64);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE LOWER(name)=LOWER(?) AND id<>?",
                Integer.class, name, id) > 0) {
            throw new ApiProblem(HttpStatus.CONFLICT, "Товар с таким названием уже существует");
        }
        jdbc.update("UPDATE products SET name=?, category=?, unit=?, description=? WHERE id=?",
            name, category, unit, description == null ? current.description() : validatedDescription(description), id);
        return product(id);
    }

    private String validatedDescription(String value) {
        String description = value == null ? "" : value.strip();
        if (description.length() > 2000) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "Описание товара должно содержать не более 2000 символов");
        }
        return description;
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
        store = required(store, "Магазин", 160);
        validatePrice(price, date);
        Product product = lockProduct(productId);
        if (priceExists(productId, store, date, null)) {
            throw new ApiProblem(HttpStatus.CONFLICT, "Цена для товара, магазина и даты уже существует");
        }
        return recordPrice(product, store, price, date, latestPrice(productId, store));
    }

    @Transactional
    public PriceEntry updatePrice(long id, BigDecimal next) {
        PriceEntry reference = price(id);
        validatePrice(next, reference.date());
        Product product = lockProduct(reference.productId());
        PriceEntry latest = latestPrice(product.id(), reference.store());
        if (latest != null && latest.date().equals(reference.date()) && latest.price().compareTo(next) == 0) {
            return latest;
        }
        if (latest == null || latest.id() != id) {
            throw new ApiProblem(HttpStatus.CONFLICT, "Цена уже изменилась. Обновите список перед редактированием");
        }
        return recordPrice(product, latest.store(), next, latest.date(), latest);
    }

    private void validatePrice(BigDecimal price, LocalDate date) {
        if (price == null || price.signum() <= 0 || price.scale() > 2 || price.precision() - price.scale() > 10) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "Цена должна быть положительным числом с максимум 2 знаками после запятой");
        }
        if (date == null) throw new ApiProblem(HttpStatus.BAD_REQUEST, "Нужно указать дату");
    }

    private Product lockProduct(long id) {
        return jdbc.query("SELECT id,name,category,unit,description FROM products WHERE id=? FOR UPDATE", PRODUCT_ROW, id)
            .stream().findFirst().orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "Товар не найден"));
    }

    private boolean priceExists(long productId, String store, LocalDate date, BigDecimal price) {
        String sql = "SELECT COUNT(*) FROM price_records WHERE product_id=? AND LOWER(store)=LOWER(?) AND observed_on=?";
        return (price == null
            ? jdbc.queryForObject(sql, Integer.class, productId, store, date)
            : jdbc.queryForObject(sql + " AND price=?", Integer.class, productId, store, date, price)) > 0;
    }

    private PriceEntry latestPrice(long productId, String store) {
        return jdbc.query("""
            SELECT id,product_id,store,price,observed_on FROM price_records
            WHERE product_id=? AND LOWER(store)=LOWER(?) ORDER BY observed_on DESC,id DESC LIMIT 1
            """, PRICE_ROW, productId, store).stream().findFirst().orElse(null);
    }

    private PriceEntry price(long id) {
        return jdbc.query("SELECT id,product_id,store,price,observed_on FROM price_records WHERE id=?", PRICE_ROW, id)
            .stream().findFirst().orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "Запись о цене не найдена"));
    }

    private PriceEntry recordPrice(Product product, String store, BigDecimal amount, LocalDate date, PriceEntry previous) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps=con.prepareStatement(
                "INSERT INTO price_records(product_id,store,price,observed_on) VALUES(?,?,?,?)",
                new String[]{"id"});
            ps.setLong(1,product.id()); ps.setString(2,store);
            ps.setBigDecimal(3,amount); ps.setDate(4,Date.valueOf(date));
            return ps;
        }, key);
        PriceEntry current = price(Objects.requireNonNull(key.getKey()).longValue());
        notifications.recordChange(product, previous, current);
        return current;
    }

    @Transactional
    public void deletePrice(long id) {
        lockProduct(price(id).productId());
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

    @Transactional(readOnly = true)
    public String exportCsv(Long productId, boolean excel) {
        List<PriceEntry> records=prices(productId,null,null);
        char delimiter = excel ? ';' : ',';
        Map<Long, String> names = excel
            ? products().stream().collect(Collectors.toMap(Product::id, Product::name)) : Map.of();
        StringBuilder out=new StringBuilder(excel
            ? "\uFEFFproductId;productName;store;price;date\r\n" : "productId,store,price,date\n");
        for (PriceEntry row: records) {
            out.append(row.productId()).append(delimiter);
            if (excel) out.append(csvEscape(excelText(names.getOrDefault(row.productId(), "")))).append(delimiter);
            String price = row.price().toPlainString();
            out.append(csvEscape(excel ? excelText(row.store()) : row.store())).append(delimiter)
               .append(excel ? price.replace('.', ',') : price).append(delimiter)
               .append(row.date()).append(excel ? "\r\n" : "\n");
        }
        return out.toString();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] exportXlsx(Long productId, LocalDate from, LocalDate to) throws IOException {
        Map<Long, String> names = productId == null
            ? products().stream().collect(Collectors.toMap(Product::id, Product::name))
            : Map.of(productId, product(productId).name());
        return XlsxPriceFiles.history(prices(productId, from, to), names);
    }

    private static String csvEscape(String value) {
        return "\"" + value.replace("\"","\"\"") + "\"";
    }

    private static String excelText(String value) {
        // Excel treats a tab inside a quoted field as text, including when the content starts with a formula.
        int first = value.codePoints().filter(ch -> !Character.isWhitespace(ch) && !Character.isSpaceChar(ch)
            && !Character.isISOControl(ch) && Character.getType(ch) != Character.FORMAT).findFirst().orElse(0);
        return "=+-@＝＋－＠".indexOf(first) >= 0 ? "\t" + value : value;
    }

    /** Учебный импорт: заголовок productId,store,price,date; кавычки допускаются. */
    @Transactional
    public Map<String,Integer> importCsv(String input) {
        if (input==null || input.isBlank()) throw new ApiProblem(HttpStatus.BAD_REQUEST, "Файл CSV пуст");
        if (input.charAt(0) == '\uFEFF') input = input.substring(1);
        return importRows(CsvParser.parse(input));
    }

    @Transactional
    public Map<String,Integer> importXlsx(InputStream input) {
        return importRows(XlsxPriceReader.read(input));
    }

    private record ImportRow(int line, long productId, String store, BigDecimal price, LocalDate date) {}

    private Map<String,Integer> importRows(List<List<String>> rows) {
        if (rows.isEmpty() || !rows.get(0).equals(List.of("productId","store","price","date")))
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "Заголовок файла должен быть productId,store,price,date");
        var entries = new ArrayList<ImportRow>();
        for (int i=1;i<rows.size();i++) {
            List<String> row=rows.get(i);
            if (row.size()==1 && row.get(0).isBlank()) continue;
            if (row.size()!=4) throw new ApiProblem(HttpStatus.BAD_REQUEST, "Неверное число полей в строке "+(i+1));
            try {
                long productId = Long.parseLong(row.get(0));
                LocalDate date = LocalDate.parse(row.get(3));
                String store = required(row.get(1), "Магазин", 160);
                BigDecimal amount = new BigDecimal(row.get(2));
                validatePrice(amount, date);
                entries.add(new ImportRow(i + 1, productId, store, amount, date));
            } catch (NumberFormatException | java.time.format.DateTimeParseException ex) {
                throw new ApiProblem(HttpStatus.BAD_REQUEST,"Некорректные значения в строке "+(i+1));
            } catch (ApiProblem problem) {
                throw new ApiProblem(problem.status(), "Строка " + (i + 1) + ": " + problem.getMessage());
            }
        }
        // Lock products in the same order across concurrent imports. Equal-date rows keep file order.
        entries.sort(Comparator.comparingLong(ImportRow::productId).thenComparing(ImportRow::date));
        int imported = 0, skipped = 0;
        Product product = null;
        for (ImportRow entry : entries) {
            if (product == null || product.id() != entry.productId()) {
                try {
                    product = lockProduct(entry.productId());
                } catch (ApiProblem problem) {
                    throw new ApiProblem(problem.status(), "Строка " + entry.line() + ": товар с ID " + entry.productId() + " не найден");
                }
            }
            if (priceExists(product.id(), entry.store(), entry.date(), entry.price())) {
                skipped++;
            } else {
                recordPrice(product, entry.store(), entry.price(), entry.date(), latestPrice(product.id(), entry.store()));
                imported++;
            }
        }
        return Map.of("imported",imported,"duplicatesSkipped",skipped);
    }
}
