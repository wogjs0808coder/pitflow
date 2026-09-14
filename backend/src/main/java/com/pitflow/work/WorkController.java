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
  private final WorkQuantitySnapshotService quantities;

  public WorkController(WorkService s, WorkQuantitySnapshotService quantities) {
    this.s = s;
    this.quantities = quantities;
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
  public Object parts(@RequestParam(defaultValue = "false") boolean includeArchived) {
    return s.parts(includeArchived);
  }

  @DeleteMapping("/parts/{id}")
  public Object archive(
      Principal p,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody Reason r) {
    return s.archive(p.getName(), key, id, r, false);
  }

  @PostMapping("/parts/{id}/restore")
  public Object restore(
      Principal p,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody Reason r) {
    return s.archive(p.getName(), key, id, r, true);
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

  @PostMapping("/parts/{id}/adjustments")
  public Object adjustment(
      Principal p,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody Adjustment r) {
    return s.adjust(p.getName(), key, id, r);
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
    @SuppressWarnings("unchecked")
    var received = (Map<String, Object>) s.receive(p.getName(), key, r);
    UUID workId = quantities.sync(received);
    return s.detail(p.getName(), workId, true);
  }

  @PatchMapping("/work-orders/{id}/status")
  public Object state(
      Principal p,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody State r) {
    return s.state(p.getName(), key, id, r);
  }

  @PostMapping("/work-orders/{id}/release")
  public Object release(
      Principal p, @RequestHeader("Idempotency-Key") UUID key, @PathVariable UUID id) {
    return s.release(p.getName(), key, id);
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
