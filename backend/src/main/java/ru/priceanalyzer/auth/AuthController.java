package ru.priceanalyzer.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.priceanalyzer.api.ApiProblem;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    static final String REFRESH_COOKIE = "pa_refresh";
    static final String AUTH_HEADER = "X-Price-Auth";
    private final Accounts accounts;
    private final AuthGrants auth;
    private final String origin;
    private final boolean secure;

    AuthController(Accounts accounts, AuthGrants auth, @Value("${app.auth.origin}") String origin) {
        this.accounts = accounts;
        this.auth = auth;
        URI url = URI.create(origin);
        this.secure = "https".equals(url.getScheme());
        boolean local = url.getHost() != null && List.of("localhost", "127.0.0.1", "[::1]").contains(url.getHost());
        if (url.getHost() == null || url.getUserInfo() != null || !url.getRawPath().isEmpty()
                || url.getQuery() != null || url.getFragment() != null
                || !(secure || local && "http".equals(url.getScheme()))) {
            throw new IllegalStateException("APP_AUTH_ORIGIN must be an HTTPS origin or an HTTP loopback origin");
        }
        this.origin = origin;
    }

    public record Registration(@NotBlank @Email @Size(max=254) String email,
                               @NotBlank @Size(min=12,max=72) String password) {}
    public record Login(@NotBlank @Email @Size(max=254) String email, @NotBlank @Size(max=72) String password) {}
    public record ResetRequest(@NotBlank @Email @Size(max=254) String email) {}
    public record ResetPassword(@NotBlank @Pattern(regexp=OpaqueTokens.FORMAT) String token,
                                @NotBlank @Size(min=12,max=72) String password) {}
    public record ChangePassword(@NotBlank @Size(max=72) String currentPassword,
                                 @NotBlank @Size(min=12,max=72) String newPassword) {}
    public record SessionUser(String email, String role) {}
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, SessionUser user) {
        @Override public String toString() { return "TokenResponse[redacted]"; }
    }

    @GetMapping("/me")
    public SessionUser me(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        return new SessionUser(authentication.getName(), admin ? "ADMIN" : "USER");
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody Registration registration) {
        accounts.register(registration.email(), registration.password());
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(HttpServletRequest request, @Valid @RequestBody Login input) {
        verifyBrowser(request);
        return tokenResponse(auth.login(input.email(), input.password()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request,
            @CookieValue(name=REFRESH_COOKIE, required=false) String refresh) {
        verifyBrowser(request);
        var grant = auth.refresh(refresh);
        if (grant.isPresent()) return tokenResponse(grant.get());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO))
            .body(Map.of("error", "Войдите в аккаунт"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request,
            @CookieValue(name=REFRESH_COOKIE, required=false) String refresh) {
        verifyBrowser(request);
        auth.logout(refresh);
        return clearCookie();
    }

    @PostMapping("/password/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestReset(@Valid @RequestBody ResetRequest request) {
        accounts.requestReset(request.email());
    }

    @PostMapping("/password/reset")
    public ResponseEntity<Void> reset(HttpServletRequest request, @Valid @RequestBody ResetPassword input,
            @CookieValue(name=REFRESH_COOKIE, required=false) String refresh) {
        verifyBrowser(request);
        accounts.reset(input.token(), input.password());
        auth.logout(refresh);
        return clearCookie();
    }

    @PostMapping("/password/change")
    public ResponseEntity<Void> change(HttpServletRequest request, Authentication authentication,
            @Valid @RequestBody ChangePassword input) {
        verifyBrowser(request);
        auth.changePassword(authentication.getName(), input.currentPassword(), input.newPassword());
        return clearCookie();
    }

    private void verifyBrowser(HttpServletRequest request) {
        if (!origin.equals(request.getHeader(HttpHeaders.ORIGIN)) || !"1".equals(request.getHeader(AUTH_HEADER))) {
            throw new ApiProblem(HttpStatus.FORBIDDEN, "Источник запроса не разрешён");
        }
    }

    private ResponseEntity<TokenResponse> tokenResponse(AuthGrants.Grant grant) {
        var user = new SessionUser(grant.account().email(), grant.account().role());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.SET_COOKIE, cookie(grant.refreshToken(), Duration.between(Instant.now(), grant.expiresAt())))
            .body(new TokenResponse(grant.accessToken(), "Bearer", JwtTokens.ACCESS_LIFETIME.toSeconds(), user));
    }

    private ResponseEntity<Void> clearCookie() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
    }

    private String cookie(String value, Duration lifetime) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
            .httpOnly(true).secure(secure).sameSite("Strict").path("/api/auth").maxAge(lifetime).build().toString();
    }
}
