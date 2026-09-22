package com.pitflow.catalog;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class CatalogController {
  private final CatalogService catalog;

  public CatalogController(CatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/api/services")
  public List<ServiceView> list() {
    return catalog.list(false);
  }

  @GetMapping("/api/parts-guide")
  public Object partsGuide(Principal principal) {
    return catalog.partsGuide(principal.getName());
  }

  @GetMapping("/api/admin/services")
  public List<ServiceView> adminList() {
    return catalog.list(true);
  }

  @PostMapping("/api/admin/services")
  @ResponseStatus(HttpStatus.CREATED)
  public ServiceView create(@Valid @RequestBody ServiceRequest r) {
    return catalog.create(r);
  }

  @PutMapping("/api/admin/services/{id}")
  public ServiceView update(@PathVariable UUID id, @Valid @RequestBody ServiceRequest r) {
    return catalog.update(id, r);
  }
}
