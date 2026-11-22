package ru.priceanalyzer.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PriceEntry(long id, long productId, String store, BigDecimal price, LocalDate date) {}
