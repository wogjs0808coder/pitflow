package com.pitflow.work;

import com.pitflow.work.WorkRequests.*;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class WorkController {
  private final WorkService s;

  public WorkController(WorkService s) {
    this.s = s;
  }

  @GetMapping("/mechanics")
  public Object mechanics() {
    return s.mechanics();
  }

  @PostMapping("/mechanics")
  public Object mechanic(
      Principal p, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Mechanic r) {
    return s.mechanic(p.getName(), key, null, r);
  }

  @PatchMapping("/mechanics/{id}")
  public Object mechanic(
      Principal p,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody Mechanic r) {
    return s.mechanic(p.getName(), key, id, r);
  }

  @GetMapping("/parts")
  public Object parts() {
    return s.parts();
  }

  @PostMapping("/parts")
  public Object part(
      Principal p, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Part r) {
    return s.part(p.getName(), key, null, r);
  }

  @PatchMapping("/parts/{id}")
  public Object part(
      Principal p,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody Part r) {
    return s.part(p.getName(), key, id, r);
  }

  @PostMapping("/parts/{id}/receipts")
  public Object receipt(
      Principal p,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody Quantity r) {
    return s.receipt(p.getName(), key, id, r);
  }

  @GetMapping("/parts/{id}/movements")
  public Object movements(@PathVariable UUID id) {
    return s.movements(id);
  }

  @GetMapping("/work-orders")
  public Object list(Principal p) {
    return s.list(p.getName(), true);
  }

  @GetMapping("/work-orders/{id}")
  public Object detail(Principal p, @PathVariable UUID id) {
    return s.detail(p.getName(), id, true);
  }

  @PostMapping("/work-orders/from-appointment")
  public Object receive(
      Principal p, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Receive r) {
    return s.receive(p.getName(), key, r);
  }

  @PatchMapping("/work-orders/{id}/status")
  public Object state(
      Principal p,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody State r) {
    return s.state(p.getName(), key, id, r);
  }

  @PatchMapping("/work-orders/{id}/assignment")
  public Object assignment(
      Principal p,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody Assignment r) {
    return s.assignment(p.getName(), key, id, r);
  }

  @PatchMapping("/work-orders/{id}/items/{itemId}")
  public Object item(
      Principal p,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @PathVariable UUID itemId,
      @Valid @RequestBody ItemState r) {
    return s.item(p.getName(), key, id, itemId, r);
  }

  @PostMapping("/work-orders/{id}/parts/use")
  public Object use(
      Principal p,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody Use r) {
    return s.use(p.getName(), key, id, r);
  }

  @PostMapping("/work-orders/{id}/parts/return")
  public Object giveBack(
      Principal p,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody Return r) {
    return s.giveBack(p.getName(), key, id, r);
  }
}
