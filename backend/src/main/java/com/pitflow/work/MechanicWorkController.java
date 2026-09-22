package com.pitflow.work;

import com.pitflow.mechanic.MechanicIdentityService;
import com.pitflow.work.WorkRequests.*;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mechanic/work-orders")
public class MechanicWorkController {
  private final MechanicIdentityService identities;
  private final WorkService work;

  public MechanicWorkController(MechanicIdentityService identities, WorkService work) {
    this.identities = identities;
    this.work = work;
  }

  @GetMapping
  public Object list(Principal principal) {
    return work.mechanicList(identities.requireActiveProfile(principal.getName()));
  }

  @GetMapping("/{id}")
  public Object detail(Principal principal, @PathVariable UUID id) {
    return work.mechanicDetail(identities.requireActiveProfile(principal.getName()), id);
  }

  @PatchMapping("/{id}/status")
  public Object state(
      Principal principal,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody State request) {
    var identity = identities.requireActive(principal.getName());
    return work.mechanicState(identity.userId(), identity.mechanicId(), key, id, request);
  }

  @PatchMapping("/{id}/items/{itemId}")
  public Object item(
      Principal principal,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @PathVariable UUID itemId,
      @Valid @RequestBody ItemState request) {
    var identity = identities.requireActive(principal.getName());
    return work.mechanicItem(identity.userId(), identity.mechanicId(), key, id, itemId, request);
  }

  @PostMapping("/{id}/items/complete-all")
  public Object completeAllItems(
      Principal principal,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id) {
    var identity = identities.requireActive(principal.getName());
    return work.mechanicCompleteAllItems(identity.userId(), identity.mechanicId(), key, id);
  }

  @PostMapping("/{id}/parts/use")
  public Object use(
      Principal principal,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody Use request) {
    var identity = identities.requireActive(principal.getName());
    return work.mechanicUse(identity.userId(), identity.mechanicId(), key, id, request);
  }

  @PostMapping("/{id}/parts/return")
  public Object giveBack(
      Principal principal,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody Return request) {
    var identity = identities.requireActive(principal.getName());
    return work.mechanicGiveBack(identity.userId(), identity.mechanicId(), key, id, request);
  }

  @PostMapping("/{id}/shortages")
  public Object shortage(
      Principal principal,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody Shortage request) {
    var identity = identities.requireActive(principal.getName());
    return work.shortage(identity.userId(), identity.mechanicId(), key, id, request);
  }
}
