package ru.priceanalyzer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"app.auth.reset-base-url=https://prices.example",
    "app.auth.from-address=access@prices.example"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiTest {
    private static final String EMAIL = "student@example.test";
    private static final String PASSWORD = "price-analysis-fixture";
    private static final String NEW_PASSWORD = "updated-analysis-fixture";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender mail;

    @BeforeEach
    void clearAccounts() {
        jdbc.update("DELETE FROM password_resets");
        jdbc.update("DELETE FROM accounts");
    }

    @Test
    void registersLogsInRotatesSessionAndLogsOut() throws Exception {
        mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(credentials(PASSWORD))).andExpect(status().isForbidden());
        register();
        String hash = jdbc.queryForObject("SELECT password_hash FROM accounts WHERE email=?", String.class, EMAIL);
        assertThat(hash).startsWith("$2a$").doesNotContain(PASSWORD);

        mvc.perform(post("/api/auth/login").with(csrf()).param("email", EMAIL)
            .param("password", "incorrect")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").isString());

        MockHttpSession existing = new MockHttpSession();
        String previousId = existing.getId();
        MockHttpSession session = login(PASSWORD, existing);
        assertThat(session.getId()).isNotEqualTo(previousId);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(EMAIL)).andExpect(jsonPath("$.role").value("USER"));
        mvc.perform(get("/api/products").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
    }

    @Test
    void recoveryTokenIsHashedSingleUseAndRevokesSessions() throws Exception {
        register();
        MockHttpSession session = login(PASSWORD, new MockHttpSession());
        String token = requestToken();
        assertThat(jdbc.queryForObject("SELECT token_hash FROM password_resets", String.class))
            .hasSize(64).doesNotContain(token);

        resetPassword(token, NEW_PASSWORD, 204);
        mvc.perform(get("/api/products").session(session)).andExpect(status().isUnauthorized());
        resetPassword(token, PASSWORD, 400);
        mvc.perform(post("/api/auth/login").with(csrf()).param("email", EMAIL)
            .param("password", PASSWORD)).andExpect(status().isUnauthorized());
        login(NEW_PASSWORD, new MockHttpSession());
    }

    @Test
    void expiredTokenCannotChangePassword() throws Exception {
        register();
        String token = requestToken();
        jdbc.update("UPDATE password_resets SET expires_at=0");
        resetPassword(token, NEW_PASSWORD, 400);
        login(PASSWORD, new MockHttpSession());
    }

    @Test
    void resetRequestDoesNotDiscloseMissingAccounts() throws Exception {
        mvc.perform(post("/api/auth/password/request").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"missing@example.test\"}")).andExpect(status().isAccepted());
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void publicRegistrationCannotSelectAdminRoleAndAdminWritesAreProtected() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(credentials(PASSWORD).replace("}", ",\"role\":\"ADMIN\",\"roles\":[\"ADMIN\"]}")))
            .andExpect(status().isCreated());
        MockHttpSession user = login(PASSWORD, new MockHttpSession());
        mvc.perform(get("/api/auth/me").session(user)).andExpect(jsonPath("$.role").value("USER"));
        mvc.perform(get("/api/prices").session(user)).andExpect(status().isOk());
        mvc.perform(get("/api/reports").param("group", "product").session(user)).andExpect(status().isOk());
        for (var action : java.util.List.of(post("/api/products"), put("/api/products/1"), delete("/api/products/1"),
                post("/api/prices"), put("/api/prices/1"), delete("/api/prices/1"),
                post("/api/import/prices"), multipart("/api/import/prices.xlsx"), get("/api/admin/settings/retention"),
                put("/api/admin/settings/retention"))) {
            mvc.perform(action.session(user).with(csrf())).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/products")).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE accounts SET role='ADMIN' WHERE email=?", EMAIL);
        MockHttpSession admin = login(PASSWORD, new MockHttpSession());
        mvc.perform(get("/api/auth/me").session(admin)).andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(get("/api/admin/settings/retention").session(admin)).andExpect(status().isOk());
        mvc.perform(put("/api/admin/settings/retention").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"days\":42}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.days").value(42));
        mvc.perform(get("/api/admin/settings/retention").session(admin)).andExpect(jsonPath("$.days").value(42));
        mvc.perform(put("/api/admin/settings/retention").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"days\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/products").session(admin).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden());
    }

    @Test
    void passwordRecoveryRevokesAdminSessionsWithoutDowngradingRole() throws Exception {
        register();
        jdbc.update("UPDATE accounts SET role='ADMIN' WHERE email=?", EMAIL);
        MockHttpSession admin = login(PASSWORD, new MockHttpSession());
        resetPassword(requestToken(), NEW_PASSWORD, 204);
        mvc.perform(get("/api/admin/settings/retention").session(admin)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(login(NEW_PASSWORD, new MockHttpSession())))
            .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    private void register() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(credentials(PASSWORD))).andExpect(status().isCreated());
    }

    private MockHttpSession login(String password, MockHttpSession session) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").session(session).with(csrf())
            .param("email", EMAIL).param("password", password)).andExpect(status().isNoContent())
            .andReturn().getRequest().getSession(false);
    }

    private String requestToken() throws Exception {
        mvc.perform(post("/api/auth/password/request").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + EMAIL + "\"}")).andExpect(status().isAccepted());
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        return sent.getValue().getText().split("#reset=", 2)[1];
    }

    private void resetPassword(String token, String password, int statusCode) throws Exception {
        mvc.perform(post("/api/auth/password/reset").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().is(statusCode));
    }

    private String credentials(String password) {
        return "{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}";
    }
}
