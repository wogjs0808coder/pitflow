package com.pitflow.work;

import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/work-orders")
public class CustomerWorkController {
  private final WorkService s;

  public CustomerWorkController(WorkService s) {
    this.s = s;
  }

  @GetMapping
  public Object list(Principal p) {
    return s.list(p.getName(), false);
  }

  @GetMapping("/{id}")
  public Object detail(Principal p, @PathVariable UUID id) {
    return s.detail(p.getName(), id, false);
  }
}
