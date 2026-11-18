package ru.priceanalyzer.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    private static final String[] PUBLIC_API = {"/api/health", "/api/auth/register", "/api/auth/login",
        "/api/auth/refresh", "/api/auth/logout", "/api/auth/password/request", "/api/auth/password/reset"};

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
    AuthenticationManager authenticationManager(Accounts accounts, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Accounts accounts) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_API).permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/products", "/api/prices", "/api/import/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/products/**", "/api/prices/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/products/**", "/api/prices/**").hasRole("ADMIN")
                .requestMatchers("/api/**").hasAnyRole("USER", "ADMIN")
                .anyRequest().permitAll())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            // Data APIs use only Authorization. Cookie endpoints verify Origin and an application header.
            .csrf(AbstractHttpConfigurer::disable)
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, resp, error) -> unauthorized(resp))
                .accessDeniedHandler((req, resp, error) -> respond(resp, 403, "Действие запрещено")))
            .oauth2ResourceServer(oauth -> oauth
                .authenticationEntryPoint((req, resp, error) -> unauthorized(resp))
                .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                    long id;
                    try {
                        id = Long.parseLong(token.getSubject());
                    } catch (NumberFormatException invalid) {
                        throw invalidToken();
                    }
                    Number version = token.getClaim("ver");
                    var account = accounts.find(id).filter(owner -> owner.enabled()
                        && owner.version() == version.longValue() && owner.role().equals(token.getClaimAsString("role")))
                        .orElseThrow(SecurityConfig::invalidToken);
                    return new JwtAuthenticationToken(token, List.of(new SimpleGrantedAuthority("ROLE_" + account.role())),
                        account.email());
                })));
        return http.build();
    }

    private static InvalidBearerTokenException invalidToken() {
        return new InvalidBearerTokenException("Invalid access token");
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        respond(response, 401, "Войдите в аккаунт");
    }

    private static void respond(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
