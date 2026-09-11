package com.pitflow.appointment;

import static com.pitflow.appointment.AppointmentModels.*;

import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {
  private final AppointmentService service;

  public AppointmentController(AppointmentService service) {
    this.service = service;
  }

  @GetMapping("/policy")
  public Policy policy() {
    return service.policy();
  }

  @GetMapping("/availability")
  public Availability availability(
      Principal p,
      @RequestParam UUID vehicleId,
      @RequestParam LocalDate date,
      @RequestParam List<UUID> serviceIds) {
    return service.availability(p.getName(), vehicleId, date, serviceIds);
  }

  @GetMapping
  public List<View> list(Principal p, @RequestParam LocalDate from, @RequestParam LocalDate to) {
    return service.list(p.getName(), from, to);
  }

  @GetMapping("/{id}")
  public View detail(Principal p, @PathVariable UUID id) {
    return service.detail(p.getName(), id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public View create(Principal p, @Valid @RequestBody CreateRequest request) {
    return service.create(p.getName(), request);
  }

  @PostMapping("/{id}/cancel")
  public View cancel(Principal p, @PathVariable UUID id) {
    return service.cancel(p.getName(), id);
  }
}
