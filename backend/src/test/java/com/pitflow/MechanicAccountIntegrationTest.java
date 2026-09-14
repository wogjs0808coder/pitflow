package com.pitflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.pitflow.user.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MechanicAccountIntegrationTest {
  private static final String PASSWORD = "Test-password-2026!";
  private static final String ADMIN = "phase2a-admin@example.com";
  private static final String CUSTOMER = "phase2a-customer@example.com";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder passwords;

  @BeforeEach
  void setup() {
    cleanup();
    users.saveAndFlush(
        new AppUser(ADMIN, passwords.encode(PASSWORD), "관리자", AppUser.Role.ADMIN));
    users.saveAndFlush(
        new AppUser(CUSTOMER, passwords.encode(PASSWORD), "고객", AppUser.Role.CUSTOMER));
  }

  @AfterEach
  void cleanup() {
    db.update("DELETE FROM mechanics WHERE code LIKE 'P2A-%'");
    users.findByEmail("phase2a-mechanic@example.com").ifPresent(users::delete);
    users.findByEmail(ADMIN).ifPresent(users::delete);
    users.findByEmail(CUSTOMER).ifPresent(users::delete);
    users.flush();
  }

  @Test
  void adminCreatesListsAndChangesMechanicAccountActiveState() throws Exception {
    var created =
        mvc.perform(
                post("/api/admin/mechanic-accounts")
                    .with(user(ADMIN).roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("P2A-01"))
            .andExpect(jsonPath("$.email").value("phase2a-mechanic@example.com"))
            .andExpect(jsonPath("$.active").value(true))
            .andReturn();
    UUID mechanic =
        UUID.fromString(
            json.readTree(created.getResponse().getContentAsString()).get("id").asText());

    var account = users.findByEmail("phase2a-mechanic@example.com").orElseThrow();
    assertThat(account.getRole()).isEqualTo(AppUser.Role.MECHANIC);
    assertThat(account.getPasswordHash()).isNotEqualTo(PASSWORD);
    assertThat(passwords.matches(PASSWORD, account.getPasswordHash())).isTrue();

    mvc.perform(get("/api/admin/mechanic-accounts").with(user(ADMIN).roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + mechanic + "')].accountId").exists());

    mvc.perform(
            patch("/api/admin/mechanic-accounts/" + mechanic + "/active")
                .with(user(ADMIN).roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));

    mvc.perform(realLogin("phase2a-mechanic@example.com", PASSWORD))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void customerCannotUseMechanicAccountManagement() throws Exception {
    mvc.perform(
            post("/api/admin/mechanic-accounts")
                .with(user(CUSTOMER).roles("CUSTOMER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/mechanic-accounts").with(user(CUSTOMER).roles("CUSTOMER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void activeMechanicLogsInButCannotUseAdminManagement() throws Exception {
    mvc.perform(
            post("/api/admin/mechanic-accounts")
                .with(user(ADMIN).roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()))
        .andExpect(status().isCreated());

    var login =
        mvc.perform(realLogin("phase2a-mechanic@example.com", PASSWORD))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("MECHANIC"))
            .andReturn();
    var session = (MockHttpSession) login.getRequest().getSession(false);
    mvc.perform(get("/api/admin/mechanic-accounts").session(session))
        .andExpect(status().isForbidden());
  }

  @Test
  void existingAdminAndCustomerLoginStillWork() throws Exception {
    mvc.perform(realLogin(ADMIN, PASSWORD))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ADMIN"));
    mvc.perform(realLogin(CUSTOMER, PASSWORD))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("CUSTOMER"));
  }

  private MockHttpServletRequestBuilder realLogin(String email, String password) {
    return post("/api/auth/login").with(csrf()).param("email", email).param("password", password);
  }

  private String createBody() {
    return """
        {"code":"P2A-01","name":"정비사","email":"phase2a-mechanic@example.com",
         "password":"Test-password-2026!","active":true}
        """;
  }
}
