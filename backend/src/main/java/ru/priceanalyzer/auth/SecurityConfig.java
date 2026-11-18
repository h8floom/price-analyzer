package ru.priceanalyzer.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
public class SecurityConfig {
    private static final List<String> PUBLIC_API = List.of("/api/health", "/api/auth/csrf",
        "/api/auth/register", "/api/auth/login", "/api/auth/password/request", "/api/auth/password/reset");
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
                .requestMatchers(PUBLIC_API.toArray(String[]::new)).permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/products", "/api/prices", "/api/import/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/products/**", "/api/prices/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/products/**", "/api/prices/**").hasRole("ADMIN")
                .requestMatchers("/api/**").hasAnyRole("USER", "ADMIN")
                .anyRequest().permitAll())
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, resp, error) -> unauthorized(resp))
                .accessDeniedHandler((req, resp, error) -> {
                    var authentication = SecurityContextHolder.getContext().getAuthentication();
                    String path = req.getRequestURI().substring(req.getContextPath().length());
                    if ((authentication == null || authentication instanceof AnonymousAuthenticationToken)
                            && path.startsWith("/api/") && !PUBLIC_API.contains(path)) {
                        unauthorized(resp);
                    } else {
                        respond(resp, 403, "Действие запрещено");
                    }
                }))
            .formLogin(form -> form
                .loginPage("/")
                .loginProcessingUrl("/api/auth/login")
                .usernameParameter("email")
                .successHandler((req, resp, authentication) -> resp.setStatus(204))
                .failureHandler((req, resp, error) -> respond(resp, 401, "Неверная почта или пароль")))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, resp, authentication) -> {
                    if (authentication == null) unauthorized(resp);
                    else resp.setStatus(204);
                }))
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
