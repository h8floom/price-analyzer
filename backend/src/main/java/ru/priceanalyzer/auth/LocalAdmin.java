package ru.priceanalyzer.auth;

import java.io.Console;
import java.util.Arrays;
import jakarta.validation.Validator;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.priceanalyzer.PriceAnalyzerApplication;
import ru.priceanalyzer.api.ApiProblem;

/** Console-only administrator provisioning using the application's PostgreSQL configuration. */
public final class LocalAdmin {
    private LocalAdmin() {}

    public static int run(String action) {
        boolean status = "status".equals(action);
        if (!status && !"create".equals(action) && !"reset".equals(action)) {
            return fail("Доступные действия: status, create, reset.");
        }
        Console console = System.console();
        if (!status && console == null) return fail("Для защищённого ввода пароля нужен терминал.");

        try (var context = new SpringApplicationBuilder(PriceAnalyzerApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.profiles.active=postgres", "--spring.main.banner-mode=off", "--logging.level.root=OFF")) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String database = jdbc.queryForObject("SELECT current_database() || ' / PostgreSQL ' || current_setting('server_version')",
                String.class);
            int admins = jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE role='ADMIN' AND enabled", Integer.class);
            System.out.println("База: " + database + ". Активных ADMIN: " + admins + ".");
            if (status) return 0;

            String email = console.readLine("Email администратора: ");
            char[] password = console.readPassword("Пароль: ");
            char[] confirmation = console.readPassword("Повторите пароль: ");
            try {
                if (email == null || password == null || confirmation == null || !Arrays.equals(password, confirmation)) {
                    return fail("Пароли не совпали или ввод прерван.");
                }
                var input = new AuthController.Registration(email.trim(), new String(password));
                if (!context.getBean(Validator.class).validate(input).isEmpty()) {
                    return fail("Введите корректный email и пароль от 12 символов.");
                }
                var accounts = context.getBean(Accounts.class);
                context.getBean(TransactionTemplate.class).executeWithoutResult(transaction -> {
                    if ("create".equals(action)) {
                        accounts.createLocalAdmin(input.email(), input.password());
                    } else {
                        var account = accounts.lock(input.email())
                            .filter(existing -> existing.enabled() && "ADMIN".equals(existing.role()))
                            .orElseThrow(() -> new IllegalArgumentException("Активный ADMIN с таким email не найден."));
                        accounts.replacePassword(account.id(), input.password());
                    }
                });
                System.out.println("Войдите на сайт с указанным email и паролем.");
                return 0;
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (confirmation != null) Arrays.fill(confirmation, '\0');
            }
        } catch (ApiProblem | IllegalArgumentException error) {
            return fail(error.getMessage());
        } catch (DataAccessException error) {
            return fail("Запись отклонена. Проверьте доступ к БД и убедитесь, что email свободен при создании.");
        } catch (RuntimeException error) {
            return fail("Проверьте настройки PostgreSQL и JWT командой ./scripts/dev-up.sh.");
        }
    }

    private static int fail(String message) {
        System.err.println(message);
        return 1;
    }
}
