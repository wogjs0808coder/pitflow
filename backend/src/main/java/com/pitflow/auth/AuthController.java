package com.pitflow.auth;

import com.pitflow.user.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AccountService accounts;

  public AuthController(AccountService accounts) {
    this.accounts = accounts;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  public UserView register(@Valid @RequestBody RegisterRequest request) {
    return accounts.registerCustomer(request.email(), request.password(), request.name(),
        request.phoneNumber(), request.birthDate());
  }

  @GetMapping("/me")
  public UserView me(Principal principal) {
    return UserView.from(accounts.current(principal));
  }

  @PostMapping("/find-id")
  public Map<String, String> findId(@Valid @RequestBody IdentityRequest request) {
    return Map.of("email", accounts.findId(request.name(), request.phoneNumber(), request.birthDate()));
  }

  @PostMapping("/reset-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
    accounts.resetPassword(request.name(), request.phoneNumber(), request.birthDate(), request.newPassword());
  }

  @PostMapping("/admin-register")
  @ResponseStatus(HttpStatus.CREATED)
  public UserView adminRegister(@Valid @RequestBody AdminRegisterRequest request) {
    return accounts.selfRegisterAdmin(request.email(), request.password(), request.phoneNumber(),
        request.birthDate(), request.inviteCode());
  }

  @PostMapping("/profile/complete")
  public UserView completeProfile(Principal principal, @Valid @RequestBody ProfileRequest request) {
    return accounts.completeProfile(principal, request.name(), request.phoneNumber(), request.birthDate());
  }

  @PutMapping("/profile")
  public UserView updateProfile(Principal principal, HttpServletRequest servletRequest,
      @Valid @RequestBody UpdateProfileRequest request) {
    var updated = accounts.updateProfile(principal, request.currentPassword(), request.name(),
        request.phoneNumber(), request.birthDate(), request.email());
    return finishEmailChange(principal, servletRequest, updated);
  }

  @PutMapping("/profile/email")
  public UserView changeMechanicEmail(Principal principal, HttpServletRequest servletRequest,
      @Valid @RequestBody EmailChangeRequest request) {
    var updated = accounts.changeMechanicEmail(principal, request.currentPassword(), request.email());
    return finishEmailChange(principal, servletRequest, updated);
  }

  private UserView finishEmailChange(Principal principal, HttpServletRequest request, UserView updated) {
    if (!principal.getName().equals(updated.email())) {
      HttpSession session = request.getSession(false);
      if (session != null) session.invalidate();
      SecurityContextHolder.clearContext();
    }
    return updated;
  }

  @PutMapping("/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void changePassword(Principal principal, @Valid @RequestBody ChangePasswordRequest request) {
    accounts.changePassword(principal, request.currentPassword(), request.newPassword());
  }

  public record RegisterRequest(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank String password,
      @NotBlank @Size(max = 50) String name,
      @NotBlank String phoneNumber,
      @NotNull LocalDate birthDate) {}

  public record IdentityRequest(@NotBlank String name, @NotNull LocalDate birthDate,
      @NotBlank String phoneNumber) {}

  public record ResetPasswordRequest(@NotBlank String name, @NotNull LocalDate birthDate,
      @NotBlank String phoneNumber, @NotBlank String newPassword) {}

  public record AdminRegisterRequest(@NotBlank @Email String email, @NotBlank String password,
      @NotBlank String phoneNumber, @NotNull LocalDate birthDate, @NotBlank String inviteCode) {}

  public record ProfileRequest(@NotBlank String name, @NotBlank String phoneNumber,
      @NotNull LocalDate birthDate) {}

  public record UpdateProfileRequest(@NotBlank String currentPassword, @NotBlank String name,
      @NotBlank String phoneNumber, @NotNull LocalDate birthDate, String email) {}

  public record EmailChangeRequest(@NotBlank String currentPassword, String email) {}

  public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
}
