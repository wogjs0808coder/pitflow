package com.pitflow.vehicle;

import jakarta.validation.constraints.*;

public record VehicleRequest(
    @NotBlank @Size(max = 20) @Pattern(regexp = "[가-힣A-Za-z0-9 -]+", message = "차량번호 형식을 확인해 주세요.")
        String plateNumber,
    @NotBlank @Size(max = 40) String manufacturer,
    @NotBlank @Size(max = 60) String model,
    @NotNull @Min(1900) @Max(2100) Integer modelYear,
    @NotNull @Min(0) @Max(9999999) Integer mileage) {}
