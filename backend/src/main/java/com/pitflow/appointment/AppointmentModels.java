package com.pitflow.appointment;

import jakarta.validation.Valid;
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
      @Size(max = 16) List<@NotNull UUID> serviceIds,
      @Size(max = 16) List<@Valid QuoteSelection> items,
      @Pattern(regexp = "[0-9a-f]{64}") String quoteFingerprint,
      @NotNull OffsetDateTime startsAt,
      @Size(max = 500) String notes) {}

  public record QuoteSelection(@NotNull UUID serviceId, @Min(1) @Max(16) int quantity) {}

  public record QuoteRequest(@NotEmpty @Size(max = 16) List<@Valid QuoteSelection> items) {}

  public record QuotePart(
      UUID partId,
      String name,
      String unit,
      BigDecimal requiredQuantityPerService,
      BigDecimal totalQuantity,
      BigDecimal unitPrice,
      BigDecimal amount,
      String chargePolicy) {}

  public record QuoteItem(
      UUID serviceId,
      String name,
      int quantity,
      BigDecimal laborUnitPrice,
      BigDecimal laborAmount,
      int durationMinutesPerService,
      int durationMinutes,
      List<QuotePart> parts,
      BigDecimal partsAmount,
      BigDecimal totalAmount) {}

  public record Quote(
      List<QuoteItem> items,
      BigDecimal totalLaborPrice,
      BigDecimal totalPartsPrice,
      BigDecimal totalPrice,
      int durationMinutes,
      String fingerprint) {}

  public record StatusRequest(@NotNull Status status) {}

  public record Bay(UUID id, String name) {}

  public record Item(
      UUID serviceId, String name, BigDecimal laborPrice, int durationMinutes, int quantity) {}

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

  record QuoteServiceRow(
      UUID id,
      String name,
      BigDecimal laborPrice,
      int durationMinutes,
      boolean requirementsConfirmed) {}

  record QuoteRequirementRow(
      UUID serviceId,
      UUID partId,
      String partName,
      String unit,
      BigDecimal requiredQuantity,
      BigDecimal unitPrice,
      boolean active,
      boolean archived,
      boolean quantityConfirmed) {}

  record QuoteConflictRow(UUID serviceIdA, UUID serviceIdB, String reason) {}

  record Occupied(UUID bayId, UUID vehicleId, Instant startsAt) {}

  record Car(UUID id, String plateNumber, String label) {}
}
