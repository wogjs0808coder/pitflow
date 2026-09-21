package com.pitflow.mechanic;

import java.math.BigDecimal;
import java.util.UUID;

public record MechanicAccountView(
    UUID id,
    UUID accountId,
    String code,
    String name,
    String email,
    boolean active,
    BigDecimal hourlyCost,
    BigDecimal monthlyBaseSalary,
    BigDecimal monthlyStandardHours,
    BigDecimal derivedHourlyCost) {}
