package com.pitflow.appointment;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class AppointmentModels {
  private AppointmentModels() {}

  public enum Status {
    PENDING,
    CONFIRMED,
    CANCELLED,
    VISITED,
    NO_SHOW
  }

  public record CreateRequest(
      @NotNull UUID vehicleId,
      @NotNull UUID workBayId,
      @NotEmpty @Size(max = 16) List<@NotNull UUID> serviceIds,
      @NotNull OffsetDateTime startsAt,
      @Size(max = 500) String notes) {}

  public record StatusRequest(@NotNull Status status) {}

  public record Bay(UUID id, String name) {}

  public record Item(UUID serviceId, String name, BigDecimal laborPrice, int durationMinutes) {}

  public record Slot(OffsetDateTime startsAt, OffsetDateTime endsAt, List<Bay> availableBays) {}

  public record Policy(
      String timezone,
      LocalTime opensAt,
      LocalTime closesAt,
      Set<DayOfWeek> closedDays,
      int slotMinutes,
      LocalDate earliestDate,
      LocalDate latestDate) {}

  public record Availability(
      LocalDate date,
      Policy policy,
      boolean closed,
      int durationMinutes,
      BigDecimal totalLaborPrice,
      List<Slot> slots) {}

  public record View(
      UUID id,
      UUID vehicleId,
      String plateNumber,
      String vehicleLabel,
      UUID workBayId,
      String workBayName,
      String customerName,
      String customerEmail,
      OffsetDateTime startsAt,
      OffsetDateTime endsAt,
      Status status,
      String notes,
      BigDecimal totalLaborPrice,
      int durationMinutes,
      List<Item> items,
      List<Status> allowedStatuses) {}

  record Row(
      UUID id,
      UUID customerId,
      UUID vehicleId,
      String plateNumber,
      String vehicleLabel,
      UUID workBayId,
      String workBayName,
      String customerName,
      String customerEmail,
      OffsetDateTime startsAt,
      OffsetDateTime endsAt,
      Status status,
      String notes,
      BigDecimal totalLaborPrice,
      int durationMinutes) {}

  record Occupied(UUID bayId, UUID vehicleId, Instant startsAt) {}

  record Car(UUID id, String plateNumber, String label) {}
}
