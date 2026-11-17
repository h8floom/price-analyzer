package ru.priceanalyzer.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.priceanalyzer.api.ApiProblem;
import ru.priceanalyzer.domain.PriceEntry;
import ru.priceanalyzer.domain.Product;

@Service
public class PriceReports {
    private final CatalogService catalog;

    public PriceReports(CatalogService catalog) {
        this.catalog = catalog;
    }

    public record Filter(String group, LocalDate from, LocalDate to, String category, String store) {}
    public record Row(String label, int observationSeries, BigDecimal averageChange, BigDecimal averagePercent) {}
    private record Key(long productId, String store) {}
    private record Series(PriceEntry first, PriceEntry last) {}

    @Transactional(readOnly = true)
    public List<Row> calculate(Filter filter) {
        if (filter.group() == null || !List.of("product", "category", "store").contains(filter.group())) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "Неизвестный тип отчёта");
        }
        Map<Long, Product> products = new HashMap<>();
        catalog.products().forEach(product -> products.put(product.id(), product));
        Map<Key, Series> series = new HashMap<>();
        for (PriceEntry entry : catalog.prices(null, filter.from(), filter.to())) {
            Product product = products.get(entry.productId());
            if (product == null || hasValue(filter.category()) && !product.category().equals(filter.category())
                || hasValue(filter.store()) && !entry.store().equalsIgnoreCase(filter.store())) continue;
            var key = new Key(entry.productId(), entry.store().toLowerCase(Locale.ROOT));
            series.compute(key, (unused, previous) -> new Series(previous == null ? entry : previous.first(), entry));
        }
        Map<String, Totals> grouped = new TreeMap<>(
            filter.group().equals("store") ? String.CASE_INSENSITIVE_ORDER : java.util.Comparator.naturalOrder());
        for (Series sample : series.values()) {
            if (sample.first().date().equals(sample.last().date())) continue;
            Product product = products.get(sample.last().productId());
            String label = switch (filter.group()) {
                case "category" -> product.category();
                case "store" -> sample.last().store();
                default -> product.name();
            };
            grouped.computeIfAbsent(label, unused -> new Totals()).add(sample);
        }
        return grouped.entrySet().stream().map(entry -> entry.getValue().row(entry.getKey())).toList();
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private static final class Totals {
        private BigDecimal change = BigDecimal.ZERO;
        private BigDecimal percent = BigDecimal.ZERO;
        private int series;

        void add(Series sample) {
            BigDecimal delta = sample.last().price().subtract(sample.first().price());
            change = change.add(delta);
            percent = percent.add(delta.movePointRight(2).divide(sample.first().price(), 12, RoundingMode.HALF_UP));
            series++;
        }

        Row row(String label) {
            BigDecimal count = BigDecimal.valueOf(series);
            return new Row(label, series, change.divide(count, 2, RoundingMode.HALF_UP),
                percent.divide(count, 2, RoundingMode.HALF_UP));
        }
    }
}
