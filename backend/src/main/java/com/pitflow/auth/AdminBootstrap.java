package com.pitflow.auth;

import com.pitflow.user.*;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap implements ApplicationRunner {
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final Validator validator;
  private final JdbcTemplate db;
  private final String email, password;

  public AdminBootstrap(
      UserRepository users,
      PasswordEncoder encoder,
      Validator validator,
      JdbcTemplate db,
      @Value("${pitflow.bootstrap.admin-email}") String email,
      @Value("${pitflow.bootstrap.admin-password}") String password) {
    this.users = users;
    this.encoder = encoder;
    this.validator = validator;
    this.db = db;
    this.email = email;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (email.isBlank() && password.isBlank()) return;
    // Serialize startup seeds, including instances using different bootstrap emails.
    db.queryForObject("SELECT next_number FROM admin_account_sequence WHERE id=1 FOR UPDATE", Long.class);
    var mainAdmins = users.findAllByRole(AppUser.Role.ADMIN).stream()
        .filter(AppUser::isMainAdmin).toList();
    if (mainAdmins.size() > 1)
      throw new IllegalStateException("Multiple main administrators are configured.");
    if (!mainAdmins.isEmpty()) return;
    String normalized = email.strip().toLowerCase(Locale.ROOT);
    var existing = users.findByEmail(normalized);
    if (existing.isPresent()) {
      if (existing.get().getRole() != AppUser.Role.ADMIN || !existing.get().isAdminActive())
        throw new IllegalStateException("Admin bootstrap email is not an active administrator.");
      existing.get().designateMainAdmin();
      return;
    }
    var request = new BootstrapRequest(email.strip(), password);
    if (!validator.validate(request).isEmpty()
        || password.codePointCount(0, password.length()) < 7
        || password.codePointCount(0, password.length()) > 20
        || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new IllegalStateException(
          "Set a valid admin email and a 7–20 character password (max 72 UTF-8 bytes).");
    var admin = new AppUser(normalized, encoder.encode(password), "메인 관리자", AppUser.Role.ADMIN);
    admin.designateMainAdmin();
    users.saveAndFlush(admin);
  }

  private record BootstrapRequest(@NotBlank @Email String email, @NotBlank String password) {}
}
