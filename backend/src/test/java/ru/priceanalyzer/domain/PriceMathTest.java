package ru.priceanalyzer.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceMathTest {
    private PriceEntry row(long id, String store, String price, String date) {
        return new PriceEntry(id, 1, store, new BigDecimal(price), LocalDate.parse(date));
    }

    @Test void emptyStatistics() {
        assertEquals(0, PriceMath.summarize(List.of()).count());
        assertNull(PriceMath.summarize(List.of()).min());
    }

    @Test void statistics() {
        var stats=PriceMath.summarize(List.of(
            row(1,"А","100.00","2026-10-01"),
            row(2,"Б","120.00","2026-10-01"),
            row(3,"В","110.00","2026-10-01")));
        assertEquals(new BigDecimal("100.00"),stats.min());
        assertEquals(new BigDecimal("120.00"),stats.max());
        assertEquals(new BigDecimal("110.00"),stats.average());
    }

    @Test void latestByStore() {
        var latest=PriceMath.latestByStore(List.of(
            row(1,"А","110.00","2026-10-01"),
            row(2,"А","100.00","2026-10-05"),
            row(3,"Б","90.00","2026-10-04")));
        assertEquals(List.of("Б","А"),latest.stream().map(PriceEntry::store).toList());
        assertEquals(2,latest.size());
    }
}
