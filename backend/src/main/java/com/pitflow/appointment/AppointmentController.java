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
  private final QuotedAppointmentService quoted;

  public AppointmentController(AppointmentService service, QuotedAppointmentService quoted) {
    this.service = service;
    this.quoted = quoted;
  }

  @GetMapping("/policy")
  public Policy policy() {
    return service.policy();
  }

  @PostMapping("/quote")
  public Quote quote(@Valid @RequestBody QuoteRequest request) {
    return service.quote(request);
  }

  @PostMapping("/availability/quoted")
  public Availability quotedAvailability(
      Principal p, @Valid @RequestBody QuoteAvailabilityRequest request) {
    return quoted.availability(p.getName(), request);
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
    if (request.items() != null && !request.items().isEmpty()) {
      return quoted.create(p.getName(), request);
    }
    return service.create(p.getName(), request);
  }

  @PostMapping("/{id}/cancel")
  public View cancel(Principal p, @PathVariable UUID id) {
    return service.cancel(p.getName(), id);
  }
}
