package com.pitflow.catalog;

import java.math.BigDecimal;
import java.util.*;

public record ServiceView(
    UUID id,
    String name,
    String description,
    BigDecimal laborPrice,
    int durationMinutes,
    boolean active,
    boolean requirementsConfirmed,
    List<PartRequirementView> parts,
    BigDecimal estimatedPartsPrice,
    BigDecimal estimatedTotalPrice) {

  public record PartRequirementView(
      UUID partId,
      String name,
      String unit,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal amount,
      boolean active,
      boolean archived) {}

  public static ServiceView from(ServiceItem s) {
    return new ServiceView(
        s.getId(),
        s.getName(),
        s.getDescription(),
        s.getLaborPrice(),
        s.getDurationMinutes(),
        s.isActive(),
        false,
        List.of(),
        BigDecimal.ZERO,
        s.getLaborPrice());
  }

  public static ServiceView from(
      ServiceItem s,
      boolean requirementsConfirmed,
      List<PartRequirementView> parts,
      BigDecimal estimatedPartsPrice) {
    return new ServiceView(
        s.getId(),
        s.getName(),
        s.getDescription(),
        s.getLaborPrice(),
        s.getDurationMinutes(),
        s.isActive(),
        requirementsConfirmed,
        List.copyOf(parts),
        estimatedPartsPrice,
        s.getLaborPrice().add(estimatedPartsPrice));
  }
}
