package com.pitflow.catalog;

import com.pitflow.catalog.ServiceRequest.PartRequirement;
import com.pitflow.catalog.ServiceView.PartRequirementView;
import com.pitflow.common.ApiException;
import java.math.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogService {
  private static final UUID WASHER_SERVICE =
      UUID.fromString("f6b2e966-cf84-3576-9a3f-a64ebf1de473");

  private final ServiceItemRepository items;
  private final JdbcTemplate db;

  public CatalogService(ServiceItemRepository items, JdbcTemplate db) {
    this.items = items;
    this.db = db;
  }

  public List<ServiceView> list(boolean all) {
    return (all ? items.findAllByOrderByNameAsc() : items.findAllByActiveTrueOrderByNameAsc())
        .stream().map(this::view).toList();
  }

  @Transactional
  public ServiceView create(ServiceRequest r) {
    var item = items.saveAndFlush(new ServiceItem(r));
    if (r.parts() != null) replaceRequirements(item.getId(), r.parts());
    return view(item);
  }

  @Transactional
  public ServiceView update(UUID id, ServiceRequest r) {
    var item =
        items
            .findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "정비 항목을 찾을 수 없습니다."));
    item.update(r);
    items.flush();
    if (r.parts() != null) replaceRequirements(id, r.parts());
    return view(item);
  }

  private ServiceView view(ServiceItem item) {
    boolean confirmed =
        Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT requirements_confirmed FROM service_items WHERE id=?",
                Boolean.class,
                item.getId()));
    var parts =
        db.query(
            """
SELECT p.id,p.name,p.unit,p.unit_price,p.active,p.archived,r.required_quantity,r.quantity_confirmed
FROM service_part_requirements r
JOIN parts p ON p.id=r.part_id
WHERE r.service_id=?
ORDER BY p.name,p.id
""",
            (rs, n) -> {
              BigDecimal quantity = rs.getBigDecimal("required_quantity");
              BigDecimal price = rs.getBigDecimal("unit_price");
              BigDecimal amount =
                  quantity == null || WASHER_SERVICE.equals(item.getId())
                      ? BigDecimal.ZERO
                      : money(quantity.multiply(price));
              return new PartRequirementView(
                  rs.getObject("id", UUID.class),
                  rs.getString("name"),
                  rs.getString("unit"),
                  quantity,
                  price,
                  amount,
                  rs.getBoolean("active"),
                  rs.getBoolean("archived"));
            },
            item.getId());
    BigDecimal partsTotal =
        parts.stream().map(PartRequirementView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    return ServiceView.from(item, confirmed, parts, money(partsTotal));
  }

  private void replaceRequirements(UUID serviceId, List<PartRequirement> requirements) {
    db.queryForObject(
        "SELECT id FROM service_items WHERE id=? FOR UPDATE", UUID.class, serviceId);

    boolean variableWasher = WASHER_SERVICE.equals(serviceId);
    List<Map<String, Object>> available = List.of();

    if (!requirements.isEmpty()) {
      var ids = requirements.stream().map(PartRequirement::partId).toList();
      String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
      available =
          db.queryForList(
              "SELECT id,sku,active,archived FROM parts WHERE id IN ("
                  + placeholders
                  + ") FOR UPDATE",
              ids.toArray());
      if (available.size() != ids.size()) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "연결할 부품을 찾을 수 없습니다.");
      }
      for (var row : available) {
        if (!Boolean.TRUE.equals(row.get("active")) || Boolean.TRUE.equals(row.get("archived"))) {
          throw new ApiException(HttpStatus.CONFLICT, "비활성 또는 삭제된 부품은 정비 항목에 연결할 수 없습니다.");
        }
      }
    }

    if (variableWasher) {
      if (requirements.size() != 1) {
        throw new ApiException(
            HttpStatus.BAD_REQUEST, "워셔액 보충 서비스에는 워셔액 부품 하나만 연결해 주세요.");
      }
      PartRequirement requirement = requirements.get(0);
      Map<String, Object> selected =
          available.stream()
              .filter(row -> requirement.partId().toString().equals(row.get("id").toString()))
              .findFirst()
              .orElseThrow(
                  () -> new ApiException(HttpStatus.BAD_REQUEST, "워셔액 부품을 찾을 수 없습니다."));
      if (!"PF-WASHER".equals(selected.get("sku"))) {
        throw new ApiException(
            HttpStatus.BAD_REQUEST, "워셔액 보충 서비스에는 PF-WASHER 부품만 연결할 수 있습니다.");
      }
      if (requirement.quantity() != null) {
        throw new ApiException(
            HttpStatus.BAD_REQUEST, "워셔액 제공량은 예약 시 확정하지 않고 실제 작업 시 입력합니다.");
      }
    } else if (requirements.stream().anyMatch(requirement -> requirement.quantity() == null)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "일반 정비 항목의 예상 부품 수량을 입력해 주세요.");
    }

    db.update("DELETE FROM service_part_requirements WHERE service_id=?", serviceId);
    for (var requirement : requirements) {
      BigDecimal quantity =
          requirement.quantity() == null ? null : requirement.quantity().stripTrailingZeros();
      db.update(
          """
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed)
VALUES (?,?,?,?)
""",
          serviceId,
          requirement.partId(),
          quantity,
          !variableWasher);
    }
    db.update("UPDATE service_items SET requirements_confirmed=TRUE WHERE id=?", serviceId);
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(0, RoundingMode.HALF_UP);
  }
}
