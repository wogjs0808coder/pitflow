package com.pitflow.mechanic;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public final class MechanicAccountRequests {
  private MechanicAccountRequests() {}

  public record Create(
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 50) String name,
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank
          String password,
      @NotNull Boolean active,
      @DecimalMin("0") @Digits(integer = 14, fraction = 0) BigDecimal hourlyCost) {}

  public record Active(@NotNull Boolean active) {}

  public record HourlyCost(
      @DecimalMin("0") @Digits(integer = 14, fraction = 0) BigDecimal hourlyCost) {}

  public record SalaryCost(
      @DecimalMin("0") @Digits(integer = 14, fraction = 0) BigDecimal monthlyBaseSalary,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 2)
          BigDecimal monthlyStandardHours) {}

  public record Link(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank
          String password) {}
}
