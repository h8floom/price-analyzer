package ru.priceanalyzer.api;

import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.priceanalyzer.service.Favorites;

@RestController
@RequestMapping("/api/favorites")
public class FavoritesController {
    private final Favorites favorites;

    public FavoritesController(Favorites favorites) {
        this.favorites = favorites;
    }

    @GetMapping
    public List<Long> list(Principal user) {
        return favorites.productIds(user.getName());
    }

    @PutMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(Principal user, @PathVariable long productId) {
        favorites.add(user.getName(), productId);
    }

    @DeleteMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Principal user, @PathVariable long productId) {
        favorites.remove(user.getName(), productId);
    }
}
