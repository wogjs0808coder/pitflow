package com.pitflow.auth;

import com.pitflow.user.*;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final Validator validator;
  private final String email, password;

  public AdminBootstrap(
      UserRepository users,
      PasswordEncoder encoder,
      Validator validator,
      @Value("${pitflow.bootstrap.admin-email}") String email,
      @Value("${pitflow.bootstrap.admin-password}") String password) {
    this.users = users;
    this.encoder = encoder;
    this.validator = validator;
    this.email = email;
    this.password = password;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (email.isBlank() && password.isBlank()) return;
    var request = new AuthController.RegisterRequest(email.strip(), password, "관리자");
    if (!validator.validate(request).isEmpty()
        || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new IllegalStateException(
          "Set a valid admin email and a 12–64 character password (max 72 UTF-8 bytes).");
    String normalized = email.strip().toLowerCase(Locale.ROOT);
    var existing = users.findByEmail(normalized);
    if (existing.isPresent()) {
      if (existing.get().getRole() != AppUser.Role.ADMIN)
        throw new IllegalStateException("Admin bootstrap email is already assigned to a customer.");
      return;
    }
    users.saveAndFlush(
        new AppUser(normalized, encoder.encode(password), "관리자", AppUser.Role.ADMIN));
  }
}
