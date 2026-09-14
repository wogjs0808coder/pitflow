package com.pitflow.mechanic;

import com.pitflow.mechanic.MechanicAccountRequests.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/mechanic-accounts")
public class MechanicAccountController {
  private final MechanicAccountService accounts;

  public MechanicAccountController(MechanicAccountService accounts) {
    this.accounts = accounts;
  }

  @GetMapping
  public List<MechanicAccountView> list() {
    return accounts.list();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public MechanicAccountView create(@Valid @RequestBody Create request) {
    return accounts.create(request);
  }

  @PatchMapping("/{id}/active")
  public MechanicAccountView setActive(
      @PathVariable UUID id, @Valid @RequestBody Active request) {
    return accounts.setActive(id, request.active());
  }
}
