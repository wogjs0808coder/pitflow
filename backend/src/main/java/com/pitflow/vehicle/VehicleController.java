package com.pitflow.vehicle;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {
  private final VehicleService service;

  public VehicleController(VehicleService service) {
    this.service = service;
  }

  @GetMapping
  public List<VehicleView> list(Principal p) {
    return service.list(p.getName());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public VehicleView create(Principal p, @Valid @RequestBody VehicleRequest r) {
    return service.create(p.getName(), r);
  }

  @PutMapping("/{id}")
  public VehicleView update(
      Principal p, @PathVariable UUID id, @Valid @RequestBody VehicleRequest r) {
    return service.update(p.getName(), id, r);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(Principal p, @PathVariable UUID id) {
    service.delete(p.getName(), id);
  }
}
