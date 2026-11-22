package ru.priceanalyzer.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration(proxyBeanMethods = false)
class JwtTokens {
    static final Duration ACCESS_LIFETIME = Duration.ofMinutes(12);
    private final SecretKeySpec key;
    private final String issuer;
    private final List<String> audience;

    JwtTokens(@Value("${app.auth.jwt-secret}") String secret, @Value("${app.auth.jwt-issuer}") String issuer,
              @Value("${app.auth.jwt-audience}") String audience) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("APP_JWT_SECRET must be Base64 encoding of at least 32 random bytes");
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("APP_JWT_SECRET must be Base64 encoding of at least 32 random bytes");
        }
        if (issuer.isBlank() || audience.isBlank()) {
            throw new IllegalStateException("APP_JWT_ISSUER and APP_JWT_AUDIENCE must not be blank");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
        this.issuer = issuer;
        this.audience = List.of(audience);
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            new JwtIssuerValidator(issuer), new JwtTimestampValidator(Duration.ZERO), this::validateClaims));
        return decoder;
    }

    String issue(JwtEncoder encoder, Accounts.Account account) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
            .issuer(issuer).audience(audience).subject(Long.toString(account.id()))
            .issuedAt(now).notBefore(now).expiresAt(now.plus(ACCESS_LIFETIME)).id(UUID.randomUUID().toString())
            .claim("role", account.role()).claim("ver", account.version()).build();
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
        var issued = jwt.getIssuedAt();
        var begins = jwt.getNotBefore();
        var expires = jwt.getExpiresAt();
        var now = Instant.now();
        var role = jwt.getClaims().get("role");
        var version = jwt.getClaims().get("ver");
        if (audience.equals(jwt.getAudience()) && "JWT".equals(jwt.getHeaders().get("typ"))
                && jwt.getSubject() != null && jwt.getSubject().matches("[1-9][0-9]{0,18}")
                && jwt.getId() != null && !jwt.getId().isBlank()
                && ("USER".equals(role) || "ADMIN".equals(role))
                && (version instanceof Integer || version instanceof Long)
                && issued != null && begins != null && expires != null && !issued.isAfter(now)
                && !begins.isBefore(issued) && expires.isAfter(begins) && expires.isAfter(now)
                && Duration.between(issued, expires).compareTo(ACCESS_LIFETIME) <= 0) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }
}
