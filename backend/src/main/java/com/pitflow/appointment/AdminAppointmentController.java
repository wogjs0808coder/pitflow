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
  private final BookingPolicy policy;

  public AdminAppointmentController(AppointmentService service, BookingPolicy policy) {
    this.service = service;
    this.policy = policy;
  }

  @GetMapping("/booking-calendar")
  public Object calendar() {
    return policy.calendar();
  }

  @PutMapping("/booking-calendar")
  public Object calendar(@Valid @RequestBody BookingPolicy.CalendarRequest request) {
    return policy.saveCalendar(request);
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
