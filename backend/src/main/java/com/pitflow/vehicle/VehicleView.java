package com.pitflow.vehicle;

import java.util.UUID;

public record VehicleView(
    UUID id, String plateNumber, String manufacturer, String model, int modelYear, int mileage) {
  public static VehicleView from(Vehicle v) {
    return new VehicleView(
        v.getId(),
        v.getPlateNumber(),
        v.getManufacturer(),
        v.getModel(),
        v.getModelYear(),
        v.getMileage());
  }
}
