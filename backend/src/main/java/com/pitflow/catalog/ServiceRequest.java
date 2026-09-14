package com.pitflow.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

public record ServiceRequest(
    @NotBlank @Size(max = 80) String name,
    @NotBlank @Size(max = 500) String description,
    @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 0) BigDecimal laborPrice,
    @NotNull @Min(30) @Max(480) Integer durationMinutes,
    @NotNull Boolean active,
    @Size(max = 30) List<@Valid PartRequirement> parts) {

  public ServiceRequest(
      String name,
      String description,
      BigDecimal laborPrice,
      Integer durationMinutes,
      Boolean active) {
    this(name, description, laborPrice, durationMinutes, active, null);
  }

  public record PartRequirement(
      @NotNull UUID partId,
      @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3)
          BigDecimal quantity) {}

  @AssertTrue(message = "작업 시간은 30분 단위로 입력해 주세요.")
  public boolean isDurationValid() {
    return durationMinutes == null || durationMinutes % 30 == 0;
  }

  @AssertTrue(message = "같은 부품은 한 정비 항목에 한 번만 연결해 주세요.")
  public boolean isPartsUnique() {
    if (parts == null) return true;
    var ids = parts.stream().map(PartRequirement::partId).filter(Objects::nonNull).toList();
    return new HashSet<>(ids).size() == ids.size();
  }
}
