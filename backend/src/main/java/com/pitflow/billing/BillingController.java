package com.pitflow.billing;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/billing")
public class BillingController {
  private final BillingService s;

  public BillingController(BillingService s) {
    this.s = s;
  }

  @GetMapping("/history")
  public Object history(Principal p, @RequestParam(required = false) UUID vehicleId) {
    return s.history(p.getName(), vehicleId);
  }

  @GetMapping("/invoices/{id}")
  public Object detail(Principal p, @PathVariable UUID id) {
    return s.detail(p.getName(), id, false);
  }

  @PostMapping("/invoices/{id}/toss/orders")
  public Object prepareToss(
      Principal p, @PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key) {
    return s.prepareToss(p.getName(), key, id);
  }

  @PostMapping("/toss/confirm")
  public Object confirmToss(
      Principal p,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody BillingRequests.TossConfirm request) {
    return s.confirmToss(p.getName(), key, request);
  }
}
