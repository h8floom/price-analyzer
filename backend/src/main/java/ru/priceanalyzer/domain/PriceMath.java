package ru.priceanalyzer.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Чистая бизнес-логика без Spring и базы данных. */
public final class PriceMath {
    private PriceMath() {}

    public record Summary(BigDecimal min, BigDecimal max, BigDecimal average, int count) {}

    /** Последняя по дате цена для каждого магазина, в порядке роста стоимости. */
    public static List<PriceEntry> latestByStore(List<PriceEntry> entries) {
        Map<String, PriceEntry> latest = new HashMap<>();
        for (PriceEntry entry : entries) {
            PriceEntry old = latest.get(entry.store());
            if (old == null || entry.date().isAfter(old.date())
                    || (entry.date().isEqual(old.date()) && entry.id() > old.id())) {
                latest.put(entry.store(), entry);
            }
        }
        List<PriceEntry> result = new ArrayList<>(latest.values());
        result.sort(Comparator.comparing(PriceEntry::price).thenComparing(PriceEntry::store));
        return result;
    }

    /** Статистика только по переданному набору цен. */
    public static Summary summarize(List<PriceEntry> entries) {
        if (entries.isEmpty()) return new Summary(null, null, null, 0);
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min = entries.get(0).price();
        BigDecimal max = min;
        for (PriceEntry entry : entries) {
            BigDecimal price = entry.price();
            sum = sum.add(price);
            if (price.compareTo(min) < 0) min = price;
            if (price.compareTo(max) > 0) max = price;
        }
        return new Summary(min, max, sum.divide(
                BigDecimal.valueOf(entries.size()), 2, RoundingMode.HALF_UP), entries.size());
    }
}
