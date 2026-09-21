package com.pitflow.finance;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class TreasuryRequests {
  private TreasuryRequests() {}

  public record PayrollPayment(
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 18, fraction = 0)
          BigDecimal amount,
      @NotNull LocalDate paymentDate,
      @NotBlank @Size(max = 500) String reason) {}
}
