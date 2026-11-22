package ru.priceanalyzer.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.priceanalyzer.service.PriceNotifications;

@RestController
@RequestMapping("/api/notifications")
public class NotificationsController {
    private final PriceNotifications notifications;

    public NotificationsController(PriceNotifications notifications) {
        this.notifications = notifications;
    }

    public record Settings(@NotNull PriceNotifications.Mode mode) {}

    @GetMapping
    public PriceNotifications.Inbox inbox(Principal user, @RequestParam(required = false) Long before) {
        return notifications.inbox(user.getName(), before);
    }

    @GetMapping("/unread")
    public Map<String, Long> unread(Principal user) {
        return Map.of("count", notifications.unread(user.getName()));
    }

    @PutMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(Principal user, @PathVariable long id) {
        notifications.markRead(user.getName(), id);
    }

    @PutMapping("/settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void configure(Principal user, @Valid @RequestBody Settings settings) {
        notifications.setMode(user.getName(), settings.mode());
    }
}
