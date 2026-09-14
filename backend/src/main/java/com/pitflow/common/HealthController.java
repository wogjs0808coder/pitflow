package com.pitflow.common;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
  private final JdbcTemplate db;

  public HealthController(JdbcTemplate db) {
    this.db = db;
  }

  @GetMapping("/api/health")
  public Map<String, String> health() {
    return Map.of("status", "UP");
  }

  @GetMapping("/api/health/ready")
  public ResponseEntity<Map<String, String>> ready() {
    try {
      db.queryForObject("SELECT 1", Integer.class);
      return ResponseEntity.ok(Map.of("status", "READY", "database", "UP"));
    } catch (RuntimeException error) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("status", "NOT_READY", "database", "DOWN"));
    }
  }
}
