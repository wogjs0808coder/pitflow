package com.pitflow.billing;

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
}
