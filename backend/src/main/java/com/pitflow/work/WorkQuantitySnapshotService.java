package com.pitflow.work;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WorkQuantitySnapshotService {
  private final JdbcTemplate db;

  public WorkQuantitySnapshotService(JdbcTemplate db) {
    this.db = db;
  }

  public UUID sync(Map<String, Object> work) {
    Object raw = work.get("id");
    if (raw == null) throw new IllegalStateException("입고 결과에 작업 ID가 없습니다.");
    UUID workId = raw instanceof UUID id ? id : UUID.fromString(raw.toString());
    db.update(
        """
UPDATE work_order_items wi
SET quantity = (
  SELECT ai.quantity
  FROM work_orders wo
  JOIN appointment_items ai
    ON ai.appointment_id = wo.appointment_id
   AND ai.service_item_id = wi.service_item_id
  WHERE wo.id = wi.work_order_id
)
WHERE wi.work_order_id = ?
  AND EXISTS (
    SELECT 1
    FROM work_orders wo
    JOIN appointment_items ai
      ON ai.appointment_id = wo.appointment_id
     AND ai.service_item_id = wi.service_item_id
    WHERE wo.id = wi.work_order_id
  )
""",
        workId);
    return workId;
  }
}
