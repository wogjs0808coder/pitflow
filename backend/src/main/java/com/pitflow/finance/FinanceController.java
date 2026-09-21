package com.pitflow.finance;

import com.pitflow.finance.FinanceRequests.*;
import jakarta.validation.Valid;
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
  public Object summary(
      Principal principal,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to) {
    return finance.summary(principal.getName(), from, to);
  }

  @GetMapping("/work-orders")
  public Object workOrders(
      Principal principal,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to) {
    return finance.list(principal.getName(), from, to);
  }

  @GetMapping("/work-orders/{id}")
  public Object workOrder(Principal principal, @PathVariable UUID id) {
    return finance.detail(principal.getName(), id);
  }

  @PostMapping("/work-orders/{id}/cost-resolution")
  public Object resolveCost(
      Principal principal,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody CostResolution request) {
    return finance.resolveCost(principal.getName(), key, id, request);
  }

  @GetMapping("/settings")
  public Object settings(Principal principal) {
    return finance.settings(principal.getName());
  }

  @PatchMapping("/settings")
  public Object settings(
      Principal principal,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Settings request) {
    return finance.updateSettings(principal.getName(), key, request);
  }

  @GetMapping("/entries")
  public Object entries(
      Principal principal,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to) {
    return finance.entries(principal.getName(), from, to);
  }

  @PostMapping("/entries")
  public Object entry(
      Principal principal,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Entry request) {
    return finance.createEntry(principal.getName(), key, request);
  }

  @PostMapping("/entries/{id}/reversal")
  public Object reverseEntry(
      Principal principal,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody Reversal request) {
    return finance.reverseEntry(principal.getName(), key, id, request);
  }
}
