package ru.priceanalyzer.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.priceanalyzer.api.ApiProblem;

@Service
public class Accounts implements UserDetailsService {
    private static final Logger log = LoggerFactory.getLogger(Accounts.class);
    private static final long RESET_LIFETIME = Duration.ofMinutes(30).toMillis();
    private static final long RESET_INTERVAL = Duration.ofMinutes(1).toMillis();
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final ObjectProvider<JavaMailSender> mail;
    private final String resetBaseUrl;
    private final String fromAddress;
    private static final String ACCOUNT_COLUMNS = "SELECT id,email,role,enabled,auth_version FROM accounts";
    private static final RowMapper<Account> ACCOUNT = (rs, row) -> new Account(rs.getLong("id"),
        rs.getString("email"), rs.getString("role"), rs.getBoolean("enabled"), rs.getLong("auth_version"));

    record Account(long id, String email, String role, boolean enabled, long version) {}

    Accounts(JdbcTemplate jdbc, PasswordEncoder passwords, ObjectProvider<JavaMailSender> mail,
             @Value("${app.auth.reset-base-url:}") String resetBaseUrl,
             @Value("${app.auth.from-address:}") String fromAddress) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.mail = mail;
        this.resetBaseUrl = resetBaseUrl;
        this.fromAddress = fromAddress;
    }

    public void register(String email, String password) {
        jdbc.update("INSERT INTO accounts(email,password_hash,role) VALUES (?,?,'USER')", normalized(email), hashPassword(password));
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return jdbc.query("SELECT email,password_hash,role,enabled FROM accounts WHERE email=?", (rs, row) ->
                User.withUsername(rs.getString("email")).password(rs.getString("password_hash"))
                    .roles(rs.getString("role")).disabled(!rs.getBoolean("enabled")).build(),
                normalized(email)).stream().findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
    }

    Optional<Account> find(long id) {
        return jdbc.query(ACCOUNT_COLUMNS + " WHERE id=?", ACCOUNT, id).stream().findFirst();
    }

    Optional<Account> lock(long id) {
        return jdbc.query(ACCOUNT_COLUMNS + " WHERE id=? FOR UPDATE", ACCOUNT, id).stream().findFirst();
    }

    Optional<Account> lock(String email) {
        return jdbc.query(ACCOUNT_COLUMNS + " WHERE email=? FOR UPDATE", ACCOUNT, normalized(email)).stream().findFirst();
    }

    @Transactional
    public void requestReset(String email) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null || fromAddress.isBlank() || !validResetBase()) {
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "Отправка писем восстановления пока недоступна");
        }
        Optional<Account> account = lock(email).filter(Account::enabled);
        if (account.isEmpty()) return;

        long now = System.currentTimeMillis();
        long id = account.get().id();
        boolean recentlyRequested = jdbc.query("SELECT issued_at FROM password_resets WHERE account_id=?",
            (rs, row) -> rs.getLong("issued_at"), id).stream().anyMatch(issued -> now - issued < RESET_INTERVAL);
        if (recentlyRequested) return;

        String token = OpaqueTokens.generate();
        jdbc.update("DELETE FROM password_resets WHERE account_id=?", id);
        jdbc.update("INSERT INTO password_resets(account_id,token_hash,issued_at,expires_at) VALUES (?,?,?,?)",
            id, OpaqueTokens.hash(token), now, now + RESET_LIFETIME);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(normalized(email));
        message.setSubject("Восстановление пароля Price Analyzer");
        message.setText("Чтобы установить новый пароль, откройте ссылку в течение 30 минут:\n"
            + resetBaseUrl + "#reset=" + token);
        try {
            sender.send(message);
        } catch (MailException failure) {
            jdbc.update("DELETE FROM password_resets WHERE account_id=?", id);
            log.warn("Password reset mail could not be delivered ({})", failure.getClass().getSimpleName());
        }
    }

    @Transactional
    public void reset(String token, String password) {
        String hash = OpaqueTokens.hash(token);
        long id = jdbc.query("SELECT account_id FROM password_resets WHERE token_hash=?",
                (rs, row) -> rs.getLong(1), hash).stream().findFirst().orElseThrow(Accounts::invalidToken);
        lock(id).filter(Account::enabled).orElseThrow(Accounts::invalidToken);
        int claimed = jdbc.update("DELETE FROM password_resets WHERE account_id=? AND token_hash=? AND expires_at>?",
            id, hash, System.currentTimeMillis());
        if (claimed != 1) throw invalidToken();
        replacePassword(id, password);
    }

    void replacePassword(long id, String password) {
        jdbc.update("UPDATE accounts SET password_hash=?, auth_version=auth_version+1 WHERE id=?", hashPassword(password), id);
        jdbc.update("DELETE FROM auth_refresh_tokens WHERE account_id=?", id);
        jdbc.update("DELETE FROM password_resets WHERE account_id=?", id);
    }

    private boolean validResetBase() {
        try {
            URI url = URI.create(resetBaseUrl);
            return url.getHost() != null && url.getUserInfo() == null && url.getFragment() == null
                && ("https".equals(url.getScheme()) || ("http".equals(url.getScheme())
                    && ("localhost".equals(url.getHost()) || "127.0.0.1".equals(url.getHost()))));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private String hashPassword(String password) {
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "Пароль должен содержать от 12 символов и помещаться в 72 байта");
        }
        return passwords.encode(password);
    }

    private static String normalized(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiProblem invalidToken() {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "Ссылка недействительна или срок её действия истёк");
    }

}
