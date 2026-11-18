package ru.priceanalyzer.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import ru.priceanalyzer.service.PriceRetention;

@RestController
@RequestMapping("/api/admin/settings/retention")
public class AdminSettingsController {
    private final PriceRetention retention;

    public AdminSettingsController(PriceRetention retention) {
        this.retention = retention;
    }

    public record Settings(@NotNull @Min(0) Integer days) {}

    @GetMapping
    public Settings settings() {
        return new Settings(retention.configuredDays());
    }

    @PutMapping
    public Settings update(@Valid @RequestBody Settings settings) {
        retention.setDays(settings.days());
        return settings;
    }
}
