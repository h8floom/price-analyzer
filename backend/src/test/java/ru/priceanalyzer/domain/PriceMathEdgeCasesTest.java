package ru.priceanalyzer.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceMathEdgeCasesTest {
    private PriceEntry entry(long id, String store, String amount, String date) {
        return new PriceEntry(id, 1, store, new BigDecimal(amount), LocalDate.parse(date));
    }

    @Test void latestDateWinsOverCheaperOlderPrice() {
        var values=PriceMath.latestByStore(List.of(
            entry(1,"А","80.00","2026-10-01"),
            entry(2,"А","110.00","2026-10-09"),
            entry(3,"Б","100.00","2026-10-07")));
        assertEquals(List.of("Б", "А"),values.stream().map(PriceEntry::store).toList());
        assertEquals(new BigDecimal("110.00"), values.get(1).price());
    }

    @Test void newestIdBreaksDateTies() {
        var values=PriceMath.latestByStore(List.of(
            entry(1,"А","100.00","2026-10-01"),
            entry(5,"А","90.00","2026-10-01")));
        assertEquals(5L, values.get(0).id());
    }

    @Test void averageRoundsHalfUp() {
        var stats=PriceMath.summarize(List.of(
            entry(1,"А","10.00","2026-10-01"),
            entry(2,"Б","10.01","2026-10-01")));
        assertEquals(new BigDecimal("10.01"),stats.average());
        assertEquals(2, stats.count());
    }
}
