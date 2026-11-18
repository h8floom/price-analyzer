package ru.priceanalyzer.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final Accounts accounts;
    private final SessionRegistry sessions;

    AuthController(Accounts accounts, SessionRegistry sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    public record Registration(@NotBlank @Email @Size(max=254) String email,
                               @NotBlank @Size(min=12,max=72) String password) {}
    public record ResetRequest(@NotBlank @Email @Size(max=254) String email) {}
    public record ResetPassword(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
                                @NotBlank @Size(min=12,max=72) String password) {}

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrf) {
        return Map.of("headerName", csrf.getHeaderName(), "token", csrf.getToken());
    }

    @GetMapping("/me")
    public Map<String, String> me(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        return Map.of("email", authentication.getName(), "role", admin ? "ADMIN" : "USER");
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody Registration registration) {
        accounts.register(registration.email(), registration.password());
    }

    @PostMapping("/password/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestReset(@Valid @RequestBody ResetRequest request) {
        accounts.requestReset(request.email());
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody ResetPassword request) {
        String email = accounts.reset(request.token(), request.password());
        sessions.getAllSessions(accounts.loadUserByUsername(email), false).forEach(SessionInformation::expireNow);
    }
}
