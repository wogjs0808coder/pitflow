package com.pitflow.vehicle;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicles")
public class Vehicle {
  @Id private UUID id;

  @Column(nullable = false)
  private UUID ownerId;

  @Column(nullable = false, unique = true, length = 20)
  private String plateNumber;

  @Column(nullable = false, length = 40)
  private String manufacturer;

  @Column(nullable = false, length = 60)
  private String model;

  @Column(nullable = false)
  private int modelYear;

  @Column(nullable = false)
  private int mileage;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected Vehicle() {}

  public Vehicle(UUID ownerId, VehicleRequest r) {
    this.id = UUID.randomUUID();
    this.ownerId = ownerId;
    this.createdAt = Instant.now();
    update(r);
  }

  public void update(VehicleRequest r) {
    plateNumber = r.plateNumber().replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
    manufacturer = r.manufacturer().strip();
    model = r.model().strip();
    modelYear = r.modelYear();
    mileage = r.mileage();
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getPlateNumber() {
    return plateNumber;
  }

  public String getManufacturer() {
    return manufacturer;
  }

  public String getModel() {
    return model;
  }

  public int getModelYear() {
    return modelYear;
  }

  public int getMileage() {
    return mileage;
  }
}
