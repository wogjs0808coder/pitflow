package com.pitflow.finance;

import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/finance/treasury")
public class TreasuryController {
  private final TreasuryService treasury;

  public TreasuryController(TreasuryService treasury) {
    this.treasury = treasury;
  }

  @GetMapping
  public Object treasury(Principal principal) {
    return treasury.treasury(principal.getName());
  }

  @PostMapping("/rebalance")
  public Object rebalance(
      Principal principal, @RequestHeader("Idempotency-Key") UUID key) {
    return treasury.rebalance(principal.getName(), key);
  }
}
