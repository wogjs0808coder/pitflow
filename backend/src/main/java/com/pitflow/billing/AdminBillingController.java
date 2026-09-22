package com.pitflow.billing;

import com.pitflow.billing.BillingRequests.*;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/billing")
public class AdminBillingController {
  private final BillingService s;

  public AdminBillingController(BillingService s) {
    this.s = s;
  }

  @GetMapping("/invoices")
  public Object list(Principal p) {
    return s.list(p.getName());
  }

  @GetMapping("/invoices/{id}")
  public Object detail(Principal p, @PathVariable UUID id) {
    return s.detail(p.getName(), id, true);
  }

  @GetMapping("/work-orders/{id}/preview")
  public Object preview(Principal p, @PathVariable UUID id) {
    return s.preview(p.getName(), id);
  }

  @PostMapping("/work-orders/{id}/invoices")
  public Object issue(
      Principal p,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Issue r) {
    return s.issue(p.getName(), key, id, r);
  }

  @PostMapping("/invoices/{id}/payments")
  public Object collect(
      Principal p,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Payment r) {
    return s.collect(p.getName(), key, id, r);
  }

  @PostMapping("/invoices/{id}/toss/orders")
  public Object prepareToss(
      Principal p, @PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key) {
    return s.prepareTossForAdmin(p.getName(), key, id);
  }

  @PostMapping("/toss/confirm")
  public Object confirmToss(
      Principal p,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody TossConfirm request) {
    return s.confirmTossForAdmin(p.getName(), key, request);
  }

  @DeleteMapping("/invoices/{id}/toss/orders/{orderId}")
  public Object abandonToss(
      Principal p,
      @PathVariable UUID id,
      @PathVariable String orderId,
      @RequestHeader("Idempotency-Key") UUID key) {
    return s.abandonTossForAdmin(p.getName(), key, id, orderId);
  }

  @PostMapping("/invoices/{id}/void")
  public Object cancel(
      Principal p,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Reason r) {
    return s.voidInvoice(p.getName(), key, id, r);
  }

  @PostMapping("/payments/{id}/reverse")
  public Object reverse(
      Principal p,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Reason r) {
    return s.reverse(p.getName(), key, id, r);
  }

  @PostMapping("/payments/{id}/toss-refund")
  public Object refundToss(
      Principal p,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Reason r) {
    return s.refundToss(p.getName(), key, id, r);
  }

  @GetMapping("/summary")
  public Object summary(Principal p, @RequestParam LocalDate from, @RequestParam LocalDate to) {
    return s.summary(p.getName(), from, to);
  }
}
