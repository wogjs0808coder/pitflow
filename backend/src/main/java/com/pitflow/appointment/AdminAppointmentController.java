package com.pitflow.appointment;

import static com.pitflow.appointment.AppointmentModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminAppointmentController {
  private final AppointmentService service;

  public AdminAppointmentController(AppointmentService service) {
    this.service = service;
  }

  @GetMapping("/work-bays")
  public List<Bay> bays() {
    return service.bays();
  }

  @GetMapping("/appointments")
  public List<View> list(@RequestParam LocalDate from, @RequestParam LocalDate to) {
    return service.adminList(from, to);
  }

  @PatchMapping("/appointments/{id}/status")
  public View change(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
    return service.change(id, request.status());
  }
}
