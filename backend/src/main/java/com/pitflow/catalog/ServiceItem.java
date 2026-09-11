package com.pitflow.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "service_items")
public class ServiceItem {
  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 80)
  private String name;

  @Column(nullable = false, length = 500)
  private String description;

  @Column(nullable = false, precision = 12, scale = 0)
  private BigDecimal laborPrice;

  @Column(nullable = false)
  private int durationMinutes;

  @Column(nullable = false)
  private boolean active;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected ServiceItem() {}

  public ServiceItem(ServiceRequest r) {
    id = UUID.randomUUID();
    createdAt = Instant.now();
    update(r);
  }

  public void update(ServiceRequest r) {
    name = r.name().strip();
    description = r.description().strip();
    laborPrice = r.laborPrice();
    durationMinutes = r.durationMinutes();
    active = r.active();
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public BigDecimal getLaborPrice() {
    return laborPrice;
  }

  public int getDurationMinutes() {
    return durationMinutes;
  }

  public boolean isActive() {
    return active;
  }
}
