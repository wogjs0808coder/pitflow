package com.pitflow.work;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

public final class WorkRequests {
  private WorkRequests() {}

  public enum Status {
    RECEIVED,
    IN_PROGRESS,
    WAITING_PARTS,
    COMPLETED,
    CANCELLED
  }

  public enum Unit {
    EA,
    L,
    KG,
    M
  }

  public record Mechanic(
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 80) String name,
      boolean active) {}

  public record Part(
      @NotBlank @Size(max = 60) String sku,
      @NotBlank @Size(max = 120) String name,
      @Size(max = 600) String description,
      @NotNull Unit unit,
      @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal minimumQuantity,
      @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 0) BigDecimal unitPrice,
      boolean active) {}

  public record Receive(
      @NotNull UUID appointmentId,
      @NotNull @Min(0) @Max(9999999) Integer receivedMileage,
      UUID mechanicId,
      @Size(max = 1000) String notes) {}

  public record State(@NotNull Status status, @Size(max = 900) String reason) {}

  public record Assignment(UUID mechanicId) {}

  public record ItemState(@NotNull Boolean done) {}

  public record Line(
      @NotNull UUID partId,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3)
          BigDecimal quantity) {}

  public record Use(
      @NotEmpty @Size(max = 30) List<@NotNull @Valid Line> lines,
      @NotBlank @Size(max = 500) String reason) {}

  public record Quantity(
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3)
          BigDecimal quantity,
      @NotBlank @Size(max = 500) String reason) {}

  public record Return(
      @NotNull UUID originalUseId,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3)
          BigDecimal quantity,
      @NotBlank @Size(max = 500) String reason) {}

  public record Adjustment(
      @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal quantity,
      @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal expectedQuantity,
      @NotBlank @Size(max = 500) String reason) {}

  public record Reason(@NotBlank @Size(max = 500) String reason) {}
}
