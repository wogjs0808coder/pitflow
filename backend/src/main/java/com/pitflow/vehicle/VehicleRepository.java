package com.pitflow.vehicle;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
  List<Vehicle> findAllByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

  Optional<Vehicle> findByIdAndOwnerId(UUID id, UUID ownerId);
}
