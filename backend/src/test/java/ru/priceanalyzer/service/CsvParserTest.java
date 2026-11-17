package ru.priceanalyzer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.priceanalyzer.api.ApiProblem;

class CsvParserTest {
    @Test void quotedCommaAndEscapedQuotes() {
        assertEquals(List.of(List.of("1", "Магазин, \"рядом\"", "99.50", "2026-10-01")),
            CsvParser.parse("1,\"Магазин, \"\"рядом\"\"\",99.50,2026-10-01\n"));
    }

    @Test void acceptsWindowsNewlines() {
        assertEquals(List.of(List.of("a","b"),List.of("c","d")),CsvParser.parse("a,b\r\nc,d\r\n"));
    }

    @Test void rejectsUnclosedQuotes() {
        assertThrows(ApiProblem.class, () -> CsvParser.parse("1,\"unfinished\n"));
    }
}
