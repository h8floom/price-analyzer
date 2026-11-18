package ru.priceanalyzer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {"app.auth.reset-base-url=https://prices.example", "app.auth.from-address=access@prices.example"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AuthApiTest {
    private static final String EMAIL = "student@example.test";
    private static final String PASSWORD = "price-analysis-fixture";
    private static final String NEW_PASSWORD = "updated-analysis-fixture";
    private static final String ORIGIN = "http://localhost:5173";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @MockitoBean JavaMailSender mail;

    private record Credentials(String access, String refresh) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }

    @BeforeEach
    void clearAccounts() {
        jdbc.update("DELETE FROM accounts WHERE email=?", EMAIL);
    }

    @Test
    void registersAsUserAndLogsInWithoutHttpSession() throws Exception {
        register(Map.of("email", EMAIL, "password", PASSWORD, "role", "ADMIN", "roles", List.of("ADMIN")));
        String hash = jdbc.queryForObject("SELECT password_hash FROM accounts WHERE email=?", String.class, EMAIL);
        assertThat(hash.startsWith("$2a$") && !hash.contains(PASSWORD)).isTrue();
        mvc.perform(browser(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", EMAIL, "password", "wrong"))))
            .andExpect(status().isUnauthorized());

        var token = login(PASSWORD);
        Jwt jwt = decoder.decode(token.access());
        assertThat(jwt.getSubject()).isEqualTo(String.valueOf(jdbc.queryForObject("SELECT id FROM accounts WHERE email=?", Long.class, EMAIL)));
        assertThat(jwt.getAudience()).containsExactly("price-analyzer-api");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(12));
        assertThat(jwt.getNotBefore()).isEqualTo(jwt.getIssuedAt());
        var authenticated = mvc.perform(bearer(get("/api/auth/me"), token.access())).andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(EMAIL)).andExpect(jsonPath("$.role").value("USER")).andReturn();
        assertThat(authenticated.getRequest().getSession(false)).isNull();
        mvc.perform(get("/api/products").cookie(refreshCookie(token.refresh()))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/products")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredForgedAndMalformedAccessTokens() throws Exception {
        register();
        var token = login(PASSWORD);
        var claims = decoder.decode(token.access()).getClaims();
        Instant old = Instant.now().minusSeconds(900);
        rejects(signed(claims, Map.of("iat", old, "nbf", old, "exp", old.plusSeconds(720))));
        Instant future = Instant.now().plusSeconds(30);
        rejects(signed(claims, Map.of("iat", future, "nbf", future, "exp", future.plusSeconds(720))));
        rejects(signed(claims, Map.of("aud", List.of("other-service"))));
        rejects(signed(claims, Map.of("iss", "https://other.example")));
        rejects(signed(claims, Map.of("sub", "9999999999999999999")));
        var missing = new java.util.HashMap<>(claims);
        missing.remove("exp");
        rejects(signed(missing, Map.of()));
        rejects("not-a-jwt");
        int position = token.access().lastIndexOf('.') + 1;
        char replacement = token.access().charAt(position) == 'A' ? 'B' : 'A';
        rejects(token.access().substring(0, position) + replacement + token.access().substring(position + 1));

        var alternate = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(new byte[64]));
        rejects(alternate.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS384).type("JWT").build(),
            JwtClaimsSet.builder().claims(values -> values.putAll(claims)).build())).getTokenValue());
    }

    @Test
    void keepsAdministrativeAuthorizationOnServerAndTracksRoleChanges() throws Exception {
        register();
        var user = login(PASSWORD);
        mvc.perform(bearer(get("/api/products"), user.access())).andExpect(status().isOk());
        mvc.perform(bearer(get("/api/reports").param("group", "product"), user.access())).andExpect(status().isOk());
        for (var action : List.of(post("/api/products"), put("/api/products/1"), delete("/api/products/1"),
                post("/api/prices"), put("/api/prices/1"), delete("/api/prices/1"),
                post("/api/import/prices"), multipart("/api/import/prices.xlsx"), get("/api/admin/settings/retention"),
                put("/api/admin/settings/retention"))) {
            mvc.perform(bearer(action, user.access())).andExpect(status().isForbidden());
        }
        rejects(signed(decoder.decode(user.access()).getClaims(), Map.of("role", "ADMIN")));
        setRole("ADMIN");
        rejects(user.access());
        var admin = rotate(user.refresh());
        mvc.perform(bearer(get("/api/auth/me"), admin.access())).andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(bearer(get("/api/admin/settings/retention"), admin.access())).andExpect(status().isOk());
        mvc.perform(bearer(put("/api/admin/settings/retention"), admin.access()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"days\":42}")).andExpect(status().isOk()).andExpect(jsonPath("$.days").value(42));
        mvc.perform(bearer(get("/api/admin/settings/retention"), admin.access())).andExpect(jsonPath("$.days").value(42));
        mvc.perform(bearer(put("/api/admin/settings/retention"), admin.access()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"days\":-1}")).andExpect(status().isBadRequest());
        setRole("USER");
        rejects(admin.access());
        var downgraded = rotate(admin.refresh());
        mvc.perform(bearer(get("/api/admin/settings/retention"), downgraded.access())).andExpect(status().isForbidden());
    }

    @Test
    void disabledAccountCannotUseExistingAccessOrRefreshTokens() throws Exception {
        register();
        var token = login(PASSWORD);
        jdbc.update("UPDATE accounts SET enabled=FALSE,auth_version=auth_version+1 WHERE email=?", EMAIL);
        rejects(token.access());
        mvc.perform(refresh(token.refresh())).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE accounts SET enabled=TRUE WHERE email=?", EMAIL);
        rejects(token.access());
        mvc.perform(refresh(token.refresh())).andExpect(status().isUnauthorized());
    }

    @Test
    void rotationStoresOnlyHashesAndReplayRevokesTheFamily() throws Exception {
        register();
        var original = login(PASSWORD);
        var otherDevice = login(PASSWORD);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_tokens WHERE token_hash=?", Integer.class,
            OpaqueTokens.hash(original.refresh()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_tokens WHERE token_hash=?", Integer.class,
            original.refresh())).isZero();
        var renewed = rotate(original.refresh());
        assertThat(!original.access().equals(renewed.access()) && !original.refresh().equals(renewed.refresh())).isTrue();
        mvc.perform(bearer(get("/api/products"), renewed.access())).andExpect(status().isOk());
        mvc.perform(refresh(original.refresh())).andExpect(status().isUnauthorized());
        mvc.perform(refresh(renewed.refresh())).andExpect(status().isUnauthorized());
        assertThat(rotate(otherDevice.refresh()) != null).isTrue();
    }

    @Test
    void logoutRevokesRefreshAndClearsCookieWhileAccessExpiresNormally() throws Exception {
        register();
        var token = login(PASSWORD);
        var result = mvc.perform(browser(post("/api/auth/logout")).cookie(refreshCookie(token.refresh())))
            .andExpect(status().isNoContent()).andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie").contains("Max-Age=0")).isTrue();
        mvc.perform(refresh(token.refresh())).andExpect(status().isUnauthorized());
        mvc.perform(bearer(get("/api/products"), token.access())).andExpect(status().isOk());
    }

    @Test
    void rejectsForeignOrMissingOriginAndMissingHeaderOnCookieEndpoints() throws Exception {
        register();
        var token = login(PASSWORD);
        for (String operation : List.of("refresh", "logout")) {
            mvc.perform(post("/api/auth/" + operation).header("Origin", "https://other.example")
                .header(AuthController.AUTH_HEADER, "1").cookie(refreshCookie(token.refresh()))).andExpect(status().isForbidden());
            mvc.perform(post("/api/auth/" + operation).header("Origin", ORIGIN)
                .cookie(refreshCookie(token.refresh()))).andExpect(status().isForbidden());
            mvc.perform(post("/api/auth/" + operation).header(AuthController.AUTH_HEADER, "1")
                .cookie(refreshCookie(token.refresh()))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD)))).andExpect(status().isForbidden());
        assertThat(rotate(token.refresh()) != null).isTrue();
    }

    @Test
    void expiredRefreshTokenCannotBeRotated() throws Exception {
        register();
        var token = login(PASSWORD);
        jdbc.update("UPDATE auth_refresh_tokens SET expires_at=0 WHERE token_hash=?", OpaqueTokens.hash(token.refresh()));
        mvc.perform(refresh(token.refresh())).andExpect(status().isUnauthorized());
    }

    @Test
    void recoveryTokenIsHashedSingleUseAndRevokesAdminTokens() throws Exception {
        register();
        setRole("ADMIN");
        var credentials = login(PASSWORD);
        String token = requestToken();
        String stored = jdbc.queryForObject("SELECT token_hash FROM password_resets WHERE token_hash=?", String.class,
            OpaqueTokens.hash(token));
        assertThat(stored.length() == 64 && !stored.equals(token)).isTrue();
        resetPassword(token, NEW_PASSWORD, 204);
        rejects(credentials.access());
        mvc.perform(refresh(credentials.refresh())).andExpect(status().isUnauthorized());
        resetPassword(token, PASSWORD, 400);
        mvc.perform(browser(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
            .andExpect(status().isUnauthorized());
        mvc.perform(bearer(get("/api/auth/me"), login(NEW_PASSWORD).access())).andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void authenticatedPasswordChangeRevokesAllPreviousTokens() throws Exception {
        register();
        var token = login(PASSWORD);
        var other = login(PASSWORD);
        mvc.perform(bearer(browser(post("/api/auth/password/change")), token.access()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("currentPassword", "wrong", "newPassword", NEW_PASSWORD))))
            .andExpect(status().isUnauthorized());
        mvc.perform(bearer(browser(post("/api/auth/password/change")), token.access()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))))
            .andExpect(status().isNoContent());
        rejects(token.access());
        rejects(other.access());
        mvc.perform(refresh(other.refresh())).andExpect(status().isUnauthorized());
        assertThat(login(NEW_PASSWORD) != null).isTrue();
    }

    @Test
    void expiredRecoveryTokenCannotChangePassword() throws Exception {
        register();
        String token = requestToken();
        jdbc.update("UPDATE password_resets SET expires_at=0 WHERE token_hash=?", OpaqueTokens.hash(token));
        resetPassword(token, NEW_PASSWORD, 400);
        assertThat(login(PASSWORD) != null).isTrue();
    }

    @Test
    void resetRequestDoesNotDiscloseMissingAccounts() throws Exception {
        mvc.perform(post("/api/auth/password/request").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"missing@example.test\"}")).andExpect(status().isAccepted());
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }

    private void setRole(String role) {
        jdbc.update("UPDATE accounts SET role=? WHERE email=?", role, EMAIL);
    }

    private void register() throws Exception {
        register(Map.of("email", EMAIL, "password", PASSWORD));
    }

    private void register(Map<String, Object> body) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isCreated());
    }

    private Credentials login(String password) throws Exception {
        return credentials(mvc.perform(browser(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", EMAIL, "password", password))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(header().string("Cache-Control", "no-store")).andReturn());
    }

    private Credentials rotate(String refresh) throws Exception {
        return credentials(mvc.perform(refresh(refresh)).andExpect(status().isOk()).andReturn());
    }

    private Credentials credentials(MvcResult result) throws Exception {
        assertThat(result.getRequest().getSession(false)).isNull();
        String header = result.getResponse().getHeader("Set-Cookie");
        assertThat(header.contains("HttpOnly") && header.contains("SameSite=Strict")
            && header.contains("Path=/api/auth") && !header.contains("Domain=") && !header.contains("; Secure")).isTrue();
        assertThat(result.getResponse().getHeaders("Set-Cookie").size()).isEqualTo(1);
        String cookie = header.substring((AuthController.REFRESH_COOKIE + "=").length(), header.indexOf(';'));
        String access = json.readTree(result.getResponse().getContentAsByteArray()).path("accessToken").asText();
        assertThat(OpaqueTokens.valid(cookie) && !access.isBlank()).isTrue();
        return new Credentials(access, cookie);
    }

    private MockHttpServletRequestBuilder refresh(String raw) {
        return browser(post("/api/auth/refresh")).cookie(refreshCookie(raw));
    }

    private Cookie refreshCookie(String value) {
        return new Cookie(AuthController.REFRESH_COOKIE, value);
    }

    private MockHttpServletRequestBuilder browser(MockHttpServletRequestBuilder request) {
        return request.header("Origin", ORIGIN).header(AuthController.AUTH_HEADER, "1");
    }

    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private void rejects(String access) throws Exception {
        mvc.perform(bearer(get("/api/products"), access)).andExpect(status().isUnauthorized());
    }

    private String signed(Map<String, Object> claims, Map<String, Object> changes) {
        var payload = JwtClaimsSet.builder().claims(values -> { values.putAll(claims); values.putAll(changes); }).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), payload))
            .getTokenValue();
    }

    private String requestToken() throws Exception {
        mvc.perform(post("/api/auth/password/request").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", EMAIL)))).andExpect(status().isAccepted());
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        return sent.getValue().getText().split("#reset=", 2)[1];
    }

    private void resetPassword(String token, String password, int statusCode) throws Exception {
        mvc.perform(browser(post("/api/auth/password/reset")).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("token", token, "password", password))))
            .andExpect(status().is(statusCode));
    }
}
