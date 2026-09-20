package com.pitflow.finance;

import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/finance")
public class FinanceController {
  private final FinanceService finance;

  public FinanceController(FinanceService finance) {
    this.finance = finance;
  }

  @GetMapping("/summary")
  public Object summary(Principal principal, @RequestParam LocalDate from, @RequestParam LocalDate to) {
    return finance.summary(principal.getName(), from, to);
  }

  @GetMapping("/work-orders")
  public Object workOrders(
      Principal principal, @RequestParam LocalDate from, @RequestParam LocalDate to) {
    return finance.list(principal.getName(), from, to);
  }

  @GetMapping("/work-orders/{id}")
  public Object workOrder(Principal principal, @PathVariable UUID id) {
    return finance.detail(principal.getName(), id);
  }
}
