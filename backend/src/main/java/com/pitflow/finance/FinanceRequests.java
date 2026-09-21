package com.pitflow.finance;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class FinanceRequests {
  private FinanceRequests() {}

  public record CostResolution(
      @DecimalMin("0") @Digits(integer = 14, fraction = 0) BigDecimal unresolvedPartsCost,
      @DecimalMin("0") @Digits(integer = 14, fraction = 0) BigDecimal laborCost,
      @NotBlank @Size(max = 500) String reason) {}

  public enum EntryCategory {
    RENT,
    UTILITIES,
    INSURANCE,
    SOFTWARE,
    SHOP_SUPPLIES,
    EQUIPMENT_MAINTENANCE,
    CARD_FEES,
    DEPRECIATION,
    INTEREST,
    TAX,
    OTHER_OPERATING,
    OTHER_INCOME
  }

  public record Settings(
      @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 0)
          BigDecimal defaultMonthlyBaseSalary,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 2)
          BigDecimal defaultMonthlyStandardHours,
      @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
          BigDecimal targetPayrollRatio) {}

  public record Entry(
      @NotNull LocalDate entryDate,
      @NotNull EntryCategory category,
      @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 0) BigDecimal amount,
      @NotBlank @Size(max = 500) String description,
      Boolean affectsTreasury) {}

  public record Reversal(@NotBlank @Size(max = 500) String reason) {}
}
