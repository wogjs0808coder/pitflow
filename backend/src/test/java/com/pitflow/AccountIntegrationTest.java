package com.pitflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitflow.auth.AdminBootstrap;
import com.pitflow.auth.AccountService;
import com.pitflow.user.*;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "pitflow.bootstrap.admin-invite-code=test-only-invite")
class AccountIntegrationTest {
  private static final String PASSWORD = "Account-pass-2026!";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder encoder;
  @Autowired JdbcTemplate db;
  @Autowired Validator validator;
  @Autowired TransactionTemplate transactions;

  @AfterEach
  void cleanup() {
    var owned = users.findAll().stream().filter(u -> u.getEmail().startsWith("p6-")).toList();
    for (var user : owned) {
      db.update("DELETE FROM admin_account_audit WHERE actor_id=? OR target_id=?", user.getId(), user.getId());
    }
    owned.forEach(users::delete);
    users.flush();
  }

  private AppUser create(String suffix, AppUser.Role role, String phone) {
    var user = new AppUser("p6-" + suffix + "@example.com", encoder.encode(PASSWORD),
        role == AppUser.Role.CUSTOMER ? "고객" : "관리자", role);
    if (phone != null) user.updateProfile(user.getName(), phone, LocalDate.of(1990, 1, 2));
    return users.saveAndFlush(user);
  }

  private String body(Map<String, ?> values) throws Exception { return json.writeValueAsString(values); }

  @Test
  void legacyLoginAndOneTimeCompletionPreserveUser() throws Exception {
    var legacy = create("legacy", AppUser.Role.CUSTOMER, null);
    mvc.perform(post("/api/auth/login").with(csrf()).param("email", legacy.getEmail())
        .param("password", PASSWORD)).andExpect(status().isOk())
        .andExpect(jsonPath("$.profileComplete").value(false));
    mvc.perform(get("/api/auth/me").with(user(legacy.getEmail())))
        .andExpect(status().isOk()).andExpect(jsonPath("$.phoneNumber").isEmpty())
        .andExpect(jsonPath("$.passwordHash").doesNotExist());
    String profile = body(Map.of("name", "새 고객", "phoneNumber", "010-1111-2222",
        "birthDate", "1990-01-02"));
    mvc.perform(post("/api/auth/profile/complete").with(user(legacy.getEmail())).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(profile))
        .andExpect(status().isOk()).andExpect(jsonPath("$.profileComplete").value(true));
    mvc.perform(post("/api/auth/profile/complete").with(user(legacy.getEmail())).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(profile)).andExpect(status().isConflict());
    assertThat(users.findById(legacy.getId()).orElseThrow().getName()).isEqualTo("새 고객");
  }

  @Test
  void registrationRequiresProfileAndNormalizesUniquePhone() throws Exception {
    mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name", "고객", "email", "p6-new@example.com", "password", PASSWORD))))
        .andExpect(status().isBadRequest());
    var registration = Map.of("name", "고객", "email", "p6-new@example.com",
        "password", PASSWORD, "phoneNumber", "010-1234-5678", "birthDate", "1990-01-02");
    mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(body(registration))).andExpect(status().isCreated())
        .andExpect(jsonPath("$.role").value("CUSTOMER"))
        .andExpect(jsonPath("$.profileComplete").value(true));
    assertThat(users.findByEmail("p6-new@example.com").orElseThrow().getPhoneNumber()).isEqualTo("01012345678");
    mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(body(registration))).andExpect(status().isConflict());
    mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name", "다른 고객", "email", "p6-another@example.com",
            "password", PASSWORD, "phoneNumber", "010 1234 5678", "birthDate", "1991-01-02"))))
        .andExpect(status().isConflict());
  }

  @Test
  void newPasswordBoundariesAndExistingLongPasswordLogin() throws Exception {
    String[] passwords = {"Ab123!", "Abc123!", "Abcdefghij123456789!", "Abcdefghij123456789!0"};
    int[] expected = {400, 201, 201, 400};
    for (int i = 0; i < passwords.length; i++) {
      mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
          .content(body(Map.of("name", "고객", "email", "p6-boundary" + i + "@example.com",
              "password", passwords[i], "phoneNumber", "0109000000" + i,
              "birthDate", "1990-01-02"))))
          .andExpect(status().is(expected[i]));
    }
    mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name", "고객", "email", "p6-byte-limit@example.com",
            "password", "😀".repeat(19), "phoneNumber", "01090000009",
            "birthDate", "1990-01-02")))).andExpect(status().isBadRequest());
    String oldPassword = "Legacy-password-over-20";
    var legacy = new AppUser("p6-old-password@example.com", encoder.encode(oldPassword),
        "기존 고객", AppUser.Role.CUSTOMER);
    users.saveAndFlush(legacy);
    mvc.perform(post("/api/auth/login").with(csrf()).param("email", legacy.getEmail())
        .param("password", oldPassword)).andExpect(status().isOk());
  }

  @Test
  void recoveryChecksAllFactorsAndReplacesBcryptPassword() throws Exception {
    var target = create("recovery", AppUser.Role.CUSTOMER, "01022223333");
    String identity = body(Map.of("name", "고객", "phoneNumber", "010-2222-3333", "birthDate", "1990-01-02"));
    mvc.perform(post("/api/auth/find-id").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(identity)).andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(target.getEmail()))
        .andExpect(jsonPath("$.passwordHash").doesNotExist());
    mvc.perform(post("/api/auth/find-id").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name", "다른 이름", "phoneNumber", "01022223333",
            "birthDate", "1990-01-02")))).andExpect(status().isNotFound());
    String next = "New-password-2026!";
    mvc.perform(post("/api/auth/reset-password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name", "고객", "phoneNumber", "01022223333",
            "birthDate", "1990-01-03", "newPassword", next)))).andExpect(status().isNotFound());
    mvc.perform(post("/api/auth/reset-password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name", "고객", "phoneNumber", "01022223333",
            "birthDate", "1990-01-02", "newPassword", "😀".repeat(19)))))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/auth/reset-password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name", "고객", "phoneNumber", "01022223333",
            "birthDate", "1990-01-02", "newPassword", next))))
        .andExpect(status().isNoContent());
    assertThat(encoder.matches(next, users.findById(target.getId()).orElseThrow().getPasswordHash())).isTrue();
    mvc.perform(post("/api/auth/login").with(csrf()).param("email", target.getEmail())
        .param("password", PASSWORD)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/login").with(csrf()).param("email", target.getEmail())
        .param("password", next)).andExpect(status().isOk());
  }

  @Test
  void myInfoRequiresCurrentPasswordAndCsrf() throws Exception {
    var target = create("mine", AppUser.Role.CUSTOMER, "01033334444");
    create("other", AppUser.Role.CUSTOMER, "01044445555");
    String update = body(Map.of("name", "수정된 고객", "phoneNumber", "010-3333-4444",
        "birthDate", "1992-03-04", "currentPassword", PASSWORD));
    mvc.perform(put("/api/auth/profile").with(user(target.getEmail()))
        .contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isForbidden());
    mvc.perform(put("/api/auth/profile").with(user(target.getEmail())).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(update.replace(PASSWORD, "wrong")))
        .andExpect(status().isForbidden());
    mvc.perform(put("/api/auth/profile").with(user(target.getEmail())).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(update.replace("010-3333-4444", "01044445555")))
        .andExpect(status().isConflict());
    mvc.perform(put("/api/auth/profile").with(user(target.getEmail())).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("수정된 고객"))
        .andExpect(jsonPath("$.email").value(target.getEmail()));
    mvc.perform(put("/api/auth/password").with(user(target.getEmail())).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of(
            "currentPassword", PASSWORD, "newPassword", "Next-password-2026!"))))
        .andExpect(status().isNoContent());
    assertThat(encoder.matches("Next-password-2026!",
        users.findById(target.getId()).orElseThrow().getPasswordHash())).isTrue();
  }

  @Test
  void bootstrapPromotesExistingAdminWithoutReplacingId() {
    var existing = create("bootstrap", AppUser.Role.ADMIN, null);
    var bootstrap = new AdminBootstrap(users, encoder, validator, existing.getEmail(),
        "Legacy-password-over-20");
    transactions.executeWithoutResult(status -> bootstrap.run(null));
    var promoted = users.findByEmail(existing.getEmail()).orElseThrow();
    assertThat(promoted.getId()).isEqualTo(existing.getId());
    assertThat(promoted.isMainAdmin()).isTrue();
    assertThat(promoted.getName()).isEqualTo("메인 관리자");
    assertThat(encoder.matches(PASSWORD, promoted.getPasswordHash())).isTrue();
  }

  @Test
  void adminSelfRegistrationFailsClosedWhenCodeIsUnconfigured() {
    var service = new AccountService(users, encoder, db, "");
    assertThatThrownBy(() -> service.selfRegisterAdmin("p6-closed@example.com", PASSWORD,
        "01088889999", LocalDate.of(1990, 1, 2), "any-code"))
        .isInstanceOf(com.pitflow.common.ApiException.class);
    assertThat(users.findByEmail("p6-closed@example.com")).isEmpty();
  }

  @Test
  void adminNumberingAuthorizationDeactivationAndAudit() throws Exception {
    var main = create("main", AppUser.Role.ADMIN, null);
    main.designateMainAdmin(); users.saveAndFlush(main);
    var customer = create("customer", AppUser.Role.CUSTOMER, null);
    String first = body(Map.of("email", "p6-admin1@example.com", "password", PASSWORD,
        "phoneNumber", "01055556666", "birthDate", "1990-01-02", "inviteCode", "test-only-invite"));
    mvc.perform(post("/api/auth/admin-register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(first.replace("test-only-invite", "wrong"))).andExpect(status().isForbidden());
    mvc.perform(post("/api/auth/admin-register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(first)).andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("관리자1"));
    var admin1 = users.findByEmail("p6-admin1@example.com").orElseThrow();
    mvc.perform(post("/api/auth/admin-register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(first)).andExpect(status().isConflict());
    mvc.perform(post("/api/auth/admin-register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(first.replace("p6-admin1@example.com", "p6-duplicate-phone@example.com")))
        .andExpect(status().isConflict());
    mvc.perform(get("/api/admin/accounts").with(user(admin1.getEmail()).roles("ADMIN")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/accounts/audit").with(user(admin1.getEmail()).roles("ADMIN")))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/accounts").with(user(admin1.getEmail()).roles("ADMIN")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("email", "p6-denied@example.com",
            "password", PASSWORD, "phoneNumber", "01099990000", "birthDate", "1990-01-02"))))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/accounts").with(user(customer.getEmail()).roles("CUSTOMER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/accounts").with(user("p6-mechanic@example.com").roles("MECHANIC")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/accounts").with(user(main.getEmail()).roles("ADMIN")))
        .andExpect(status().isOk());
    mvc.perform(post("/api/admin/accounts").with(user(main.getEmail()).roles("ADMIN")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("email", "p6-admin2@example.com",
            "password", PASSWORD, "phoneNumber", "01066667777", "birthDate", "1990-01-02"))))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("관리자2"));
    mvc.perform(put("/api/auth/profile").with(user(admin1.getEmail()).roles("ADMIN")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("name", "위조된 이름",
            "phoneNumber", "01055556666", "birthDate", "1990-01-02", "currentPassword", PASSWORD))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("관리자1"));
    mvc.perform(put("/api/auth/password").with(user(admin1.getEmail()).roles("ADMIN")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("currentPassword", PASSWORD,
            "newPassword", "Admin-new-password!")))).andExpect(status().isNoContent());
    mvc.perform(delete("/api/admin/accounts/" + main.getId()).with(user(main.getEmail()).roles("ADMIN"))
        .with(csrf())).andExpect(status().isForbidden());
    mvc.perform(delete("/api/admin/accounts/" + admin1.getId()).with(user(main.getEmail()).roles("ADMIN"))
        .with(csrf())).andExpect(status().isNoContent());
    assertThat(users.findById(admin1.getId()).orElseThrow().isAdminActive()).isFalse();
    mvc.perform(post("/api/auth/login").with(csrf()).param("email", admin1.getEmail())
        .param("password", "Admin-new-password!")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/admin/accounts").with(user(main.getEmail()).roles("ADMIN")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("email", "p6-admin3@example.com",
            "password", PASSWORD, "phoneNumber", "01077778888", "birthDate", "1990-01-02"))))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("관리자3"));
    mvc.perform(get("/api/admin/accounts/audit").with(user(main.getEmail()).roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.action=='SELF_REGISTERED')]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.action=='CREATED')]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.action=='DEACTIVATED')]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.action=='PROFILE_UPDATED')]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.action=='PASSWORD_CHANGED')]").isNotEmpty());
  }
}
