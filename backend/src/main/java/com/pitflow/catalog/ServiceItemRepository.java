package com.pitflow.catalog;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceItemRepository extends JpaRepository<ServiceItem, UUID> {
  List<ServiceItem> findAllByActiveTrueOrderByNameAsc();

  List<ServiceItem> findAllByOrderByNameAsc();
}
