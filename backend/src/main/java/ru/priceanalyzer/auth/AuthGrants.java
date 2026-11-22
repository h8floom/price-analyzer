package ru.priceanalyzer.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.priceanalyzer.api.ApiProblem;

@Service
class AuthGrants {
    private static final Duration REFRESH_LIFETIME = Duration.ofDays(7);
    private final Accounts accounts;
    private final AuthenticationManager passwords;
    private final JdbcTemplate jdbc;
    private final JwtTokens tokens;
    private final JwtEncoder encoder;

    AuthGrants(Accounts accounts, AuthenticationManager passwords, JdbcTemplate jdbc, JwtTokens tokens, JwtEncoder encoder) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.encoder = encoder;
    }

    record Grant(String accessToken, String refreshToken, Instant expiresAt, Accounts.Account account) {
        @Override public String toString() { return "Grant[redacted]"; }
    }
    private record Refresh(long accountId, String familyId, long version, long expiresAt, boolean rotated) {}

    @Transactional
    public Grant login(String email, String password) {
        var account = accounts.lock(email);
        checkPassword(email, password);
        return issue(account.orElseThrow(AuthGrants::invalidCredentials), UUID.randomUUID().toString(),
            Instant.now().plus(REFRESH_LIFETIME));
    }

    @Transactional
    public Optional<Grant> refresh(String raw) {
        if (!OpaqueTokens.valid(raw)) return Optional.empty();
        String hash = OpaqueTokens.hash(raw);
        var found = lookup(hash).stream().findFirst();
        if (found.isEmpty()) return Optional.empty();
        // Password changes and rotations take the account lock before touching refresh rows.
        var account = accounts.lock(found.get().accountId());
        var current = lookup(hash).stream().findFirst();
        if (account.isEmpty() || current.isEmpty()) return Optional.empty();

        var owner = account.get();
        var token = current.get();
        if (token.rotated() || token.expiresAt() <= System.currentTimeMillis()
                || !owner.enabled() || token.version() != owner.version()) {
            revokeFamily(token);
            return Optional.empty();
        }
        jdbc.update("UPDATE auth_refresh_tokens SET rotated=TRUE WHERE token_hash=?", hash);
        return Optional.of(issue(owner, token.familyId(), Instant.ofEpochMilli(token.expiresAt())));
    }

    @Transactional
    public void logout(String raw) {
        if (!OpaqueTokens.valid(raw)) return;
        String hash = OpaqueTokens.hash(raw);
        lookup(hash).stream().findFirst().ifPresent(token -> {
            accounts.lock(token.accountId());
            lookup(hash).stream().findFirst().ifPresent(this::revokeFamily);
        });
    }

    @Transactional
    public void changePassword(String email, String current, String replacement) {
        var account = accounts.lock(email).orElseThrow(AuthGrants::invalidCredentials);
        checkPassword(email, current);
        accounts.replacePassword(account.id(), replacement);
    }

    @Scheduled(cron = "0 20 3 * * *")
    public void removeExpired() {
        jdbc.update("DELETE FROM auth_refresh_tokens WHERE expires_at<=?", System.currentTimeMillis());
    }

    private Grant issue(Accounts.Account owner, String family, Instant expiration) {
        String raw = OpaqueTokens.generate();
        jdbc.update("""
            INSERT INTO auth_refresh_tokens(token_hash,account_id,family_id,auth_version,expires_at)
            VALUES (?,?,?,?,?)
            """, OpaqueTokens.hash(raw), owner.id(), family, owner.version(), expiration.toEpochMilli());
        return new Grant(tokens.issue(encoder, owner), raw, expiration, owner);
    }

    private List<Refresh> lookup(String hash) {
        return jdbc.query("""
            SELECT account_id,family_id,auth_version,expires_at,rotated FROM auth_refresh_tokens WHERE token_hash=?
            """, (rs, row) -> new Refresh(rs.getLong("account_id"), rs.getString("family_id"),
                rs.getLong("auth_version"), rs.getLong("expires_at"), rs.getBoolean("rotated")), hash);
    }

    private void revokeFamily(Refresh token) {
        jdbc.update("DELETE FROM auth_refresh_tokens WHERE family_id=?", token.familyId());
    }

    private void checkPassword(String email, String password) {
        try {
            passwords.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        } catch (AuthenticationException invalid) {
            throw invalidCredentials();
        }
    }

    private static ApiProblem invalidCredentials() {
        return new ApiProblem(HttpStatus.UNAUTHORIZED, "Неверная почта или пароль");
    }
}
