package com.pitflow.catalog;

import java.math.BigDecimal;
import java.util.UUID;

public record ServiceView(
    UUID id,
    String name,
    String description,
    BigDecimal laborPrice,
    int durationMinutes,
    boolean active) {
  public static ServiceView from(ServiceItem s) {
    return new ServiceView(
        s.getId(),
        s.getName(),
        s.getDescription(),
        s.getLaborPrice(),
        s.getDurationMinutes(),
        s.isActive());
  }
}
