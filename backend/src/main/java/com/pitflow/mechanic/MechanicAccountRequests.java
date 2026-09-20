package com.pitflow.mechanic;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public final class MechanicAccountRequests {
  private MechanicAccountRequests() {}

  public record Create(
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 50) String name,
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 12, max = 64, message = "비밀번호는 12~64자로 입력해 주세요.")
          String password,
      @NotNull Boolean active,
      @DecimalMin("0") @Digits(integer = 14, fraction = 0) BigDecimal hourlyCost) {}

  public record Active(@NotNull Boolean active) {}

  public record HourlyCost(
      @DecimalMin("0") @Digits(integer = 14, fraction = 0) BigDecimal hourlyCost) {}

  public record Link(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 12, max = 64, message = "비밀번호는 12~64자로 입력해 주세요.")
          String password) {}
}
