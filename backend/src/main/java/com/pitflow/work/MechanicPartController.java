package com.pitflow.work;

import com.pitflow.mechanic.MechanicIdentityService;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mechanic/parts")
public class MechanicPartController {
  private final MechanicIdentityService identities;
  private final WorkService work;

  public MechanicPartController(MechanicIdentityService identities, WorkService work) {
    this.identities = identities;
    this.work = work;
  }

  @GetMapping
  public Object list(Principal principal) {
    identities.requireActive(principal.getName());
    return work.mechanicParts();
  }
}
