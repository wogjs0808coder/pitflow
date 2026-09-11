package com.pitflow.catalog;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ServiceRequest(
    @NotBlank @Size(max = 80) String name,
    @NotBlank @Size(max = 500) String description,
    @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 0) BigDecimal laborPrice,
    @NotNull @Min(30) @Max(480) Integer durationMinutes,
    @NotNull Boolean active) {
  @AssertTrue(message = "작업 시간은 30분 단위로 입력해 주세요.")
  public boolean isDurationValid() {
    return durationMinutes == null || durationMinutes % 30 == 0;
  }
}
