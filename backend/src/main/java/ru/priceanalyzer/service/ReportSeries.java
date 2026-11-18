package ru.priceanalyzer.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import ru.priceanalyzer.domain.PriceEntry;
import ru.priceanalyzer.domain.PriceMath;

record ReportSeries(PriceReports.Snapshot snapshot, List<PriceEntry> offers, PriceMath.Summary summary,
                    List<Map.Entry<String, Integer>> counts, Map<String, NavigableMap<LocalDate, BigDecimal>> trends) {
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    static final int PLOT_STORES = 8;

    static ReportSeries from(PriceReports.Snapshot snapshot) {
        var counts = new TreeMap<String, Integer>(String.CASE_INSENSITIVE_ORDER);
        var trends = new TreeMap<String, NavigableMap<LocalDate, BigDecimal>>(String.CASE_INSENSITIVE_ORDER);
        for (var price : snapshot.prices()) {
            counts.merge(price.store(), 1, Integer::sum);
            if (snapshot.selected() != null) {
                trends.computeIfAbsent(price.store(), name -> new TreeMap<>()).put(price.date(), price.price());
            }
        }
        var byProduct = snapshot.prices().stream().collect(Collectors.groupingBy(PriceEntry::productId, TreeMap::new,
            Collectors.toList()));
        var offers = byProduct.values().stream().flatMap(rows -> PriceMath.latestByStore(rows).stream()).toList();
        var orderedCounts = counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())).toList();
        return new ReportSeries(snapshot, offers, PriceMath.summarize(offers), orderedCounts, trends);
    }

    String title() {
        return snapshot.selected() == null ? "Все товары" : snapshot.selected().name();
    }

    String period() {
        var from = snapshot.filter().from();
        var to = snapshot.filter().to();
        if (!snapshot.prices().isEmpty()) {
            if (from == null) from = snapshot.prices().getFirst().date();
            if (to == null) to = snapshot.prices().getLast().date();
        }
        if (from == null && to == null) return "Все даты";
        if (from == null) return "по " + DAY.format(to);
        if (to == null) return "с " + DAY.format(from);
        return DAY.format(from) + " — " + DAY.format(to);
    }

    String priceBasis() {
        return snapshot.selected() == null ? "Последние цены по каждой паре товар / магазин" : "Последние цены по магазинам";
    }

    List<String> chartStores() {
        return counts.stream().limit(PLOT_STORES).map(Map.Entry::getKey).toList();
    }

    List<Map.Entry<String, Integer>> plotCounts() {
        if (counts.size() <= PLOT_STORES) return counts;
        var entries = new ArrayList<>(counts.subList(0, PLOT_STORES));
        int others = counts.subList(PLOT_STORES, counts.size()).stream().mapToInt(Map.Entry::getValue).sum();
        entries.add(Map.entry("Прочие магазины (сумма)", others));
        return entries;
    }

    String coverage() {
        return counts.size() > PLOT_STORES ? " · " + PLOT_STORES + " из " + counts.size() + " магазинов" : "";
    }
}
