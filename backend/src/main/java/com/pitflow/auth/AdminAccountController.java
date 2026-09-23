package com.pitflow.auth;

import com.pitflow.user.UserView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {
  private final AccountService accounts;

  public AdminAccountController(AccountService accounts) { this.accounts = accounts; }

  @GetMapping
  public List<AccountService.AdminAccountView> list(Principal principal) {
    return accounts.admins(principal);
  }

  @GetMapping("/audit")
  public List<AccountService.AdminAuditView> audit(Principal principal) {
    return accounts.audit(principal);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public UserView create(Principal principal, @Valid @RequestBody CreateRequest request) {
    return accounts.createAdminByMain(principal, request.email(), request.password(),
        request.phoneNumber(), request.birthDate());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deactivate(Principal principal, @PathVariable UUID id) {
    accounts.deactivateAdmin(principal, id);
  }

  public record CreateRequest(@NotBlank @Email String email, @NotBlank String password,
      @NotBlank String phoneNumber, @NotNull LocalDate birthDate) {}
}
