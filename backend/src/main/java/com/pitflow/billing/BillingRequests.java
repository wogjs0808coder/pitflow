package com.pitflow.billing;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public final class BillingRequests {
  private BillingRequests() {}

  public enum Method {
    CASH,
    CARD,
    TRANSFER
  }

  public record Issue(
      @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedFingerprint,
      boolean confirmZeroPrices) {}

  public record Payment(
      @NotNull Method method,
      @Size(max = 100) String reference,
      @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 0) BigDecimal expectedTotal) {}

  public record Reason(@NotBlank @Size(max = 500) String reason) {}

  public record TossConfirm(
      @NotBlank @Size(max = 200) String paymentKey,
      @NotBlank @Size(max = 64) String orderId,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 14, fraction = 0)
          BigDecimal amount,
      @NotNull UUID invoiceId) {}
}
