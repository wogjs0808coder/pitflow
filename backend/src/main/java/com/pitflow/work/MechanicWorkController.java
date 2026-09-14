package com.pitflow.work;

import com.pitflow.mechanic.MechanicIdentityService;
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
}
