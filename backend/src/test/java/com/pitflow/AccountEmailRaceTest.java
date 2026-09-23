package com.pitflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pitflow.auth.AccountService;
import com.pitflow.common.ApiException;
import com.pitflow.user.AppUser;
import com.pitflow.user.UserRepository;
import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountEmailRaceTest {
  @Test
  void databaseUniqueConflictAfterAvailabilityCheckBecomesConflictResponse() {
    var users = mock(UserRepository.class);
    var passwords = mock(PasswordEncoder.class);
    var user = new AppUser("old@example.com", "unchanged-hash", "고객", AppUser.Role.CUSTOMER);
    user.updateProfile(user.getName(), "01012345678", LocalDate.of(1990, 1, 2));
    when(users.findByEmail("old@example.com")).thenReturn(Optional.of(user));
    when(passwords.matches("current", "unchanged-hash")).thenReturn(true);
    when(users.findByPhoneNumber("01012345678")).thenReturn(Optional.of(user));
    when(users.existsByEmail("new@example.com")).thenReturn(false);
    when(users.saveAndFlush(user)).thenThrow(new DataIntegrityViolationException("unique email"));
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var service = new AccountService(users, passwords, mock(JdbcTemplate.class), factory.getValidator(), "");
      assertThatThrownBy(() -> service.updateProfile(() -> "old@example.com", "current", "고객",
          "01012345678", LocalDate.of(1990, 1, 2), "new@example.com"))
          .isInstanceOfSatisfying(ApiException.class,
              exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));
      assertThat(user.getPasswordHash()).isEqualTo("unchanged-hash");
    }
  }
}
