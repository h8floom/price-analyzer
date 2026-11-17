package ru.priceanalyzer.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12) {
            @Override
            public boolean matches(CharSequence raw, String encoded) {
                return raw != null && raw.toString().getBytes(StandardCharsets.UTF_8).length <= 72
                    && super.matches(raw, encoded);
            }
        };
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    HttpSessionEventPublisher sessionEvents() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionRegistry sessions) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/health", "/api/auth/csrf", "/api/auth/register",
                    "/api/auth/login", "/api/auth/password/request", "/api/auth/password/reset").permitAll()
                .requestMatchers("/api/**").hasRole("USER")
                .anyRequest().permitAll())
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, resp, error) -> unauthorized(resp))
                .accessDeniedHandler((req, resp, error) -> respond(resp, 403, "Действие запрещено")))
            .formLogin(form -> form
                .loginPage("/")
                .loginProcessingUrl("/api/auth/login")
                .usernameParameter("email")
                .successHandler((req, resp, authentication) -> resp.setStatus(204))
                .failureHandler((req, resp, error) -> respond(resp, 401, "Неверная почта или пароль")))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, resp, authentication) -> resp.setStatus(204)))
            .sessionManagement(session -> session.maximumSessions(-1)
                .sessionRegistry(sessions)
                .expiredSessionStrategy(event -> unauthorized(event.getResponse())));
        return http.build();
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        respond(response, 401, "Войдите в аккаунт");
    }

    private static void respond(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
