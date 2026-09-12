package db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** V3 is already deployed. Resolve its generated CHECK names without rewriting history. */
public class V4__inventory_adjustments_and_catalog extends BaseJavaMigration {
  @Override
  public Integer getChecksum() {
    return 2026091301;
  }

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    db.execute("ALTER TABLE parts ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE");
    db.execute(
        "CREATE TABLE part_events (id UUID PRIMARY KEY, part_id UUID NOT NULL REFERENCES parts(id)"
            + " ON DELETE CASCADE, event_type VARCHAR(20) NOT NULL, reason VARCHAR(500) NOT NULL,"
            + " created_at TIMESTAMP WITH TIME ZONE NOT NULL)");
    var checks =
        db.queryForList(
            """
            SELECT tc.constraint_name, cc.check_clause
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON tc.constraint_catalog=cc.constraint_catalog
              AND tc.constraint_schema=cc.constraint_schema
              AND tc.constraint_name=cc.constraint_name
            WHERE LOWER(tc.table_name)='stock_movements' AND tc.table_schema=?
              AND tc.constraint_type='CHECK'
            """,
            connection.getSchema());
    var names =
        checks.stream()
            .filter(c -> c.get("check_clause").toString().toLowerCase(Locale.ROOT).contains("kind"))
            .map(c -> c.get("constraint_name").toString())
            .toList();
    if (names.size() != 2)
      throw new IllegalStateException("Expected V3 movement kind and link checks");
    for (String name : names)
      db.execute(
          "ALTER TABLE stock_movements DROP CONSTRAINT \"" + name.replace("\"", "\"\"") + "\"");
    db.execute(
        """
        ALTER TABLE stock_movements ADD CONSTRAINT stock_movement_kind_v4
        CHECK (kind IN ('RECEIPT','USE','RETURN','ADJUST_IN','ADJUST_OUT'))
        """);
    db.execute(
        """
ALTER TABLE stock_movements ADD CONSTRAINT stock_movement_links_v4 CHECK (
  (kind IN ('RECEIPT','ADJUST_IN','ADJUST_OUT') AND work_order_id IS NULL AND original_use_id IS NULL)
  OR (kind='USE' AND work_order_id IS NOT NULL AND original_use_id IS NULL)
  OR (kind='RETURN' AND work_order_id IS NOT NULL AND original_use_id IS NOT NULL))
""");
    db.execute(
        """
        CREATE TABLE service_part_requirements (
          service_id UUID NOT NULL REFERENCES service_items(id) ON DELETE CASCADE,
          part_id UUID NOT NULL REFERENCES parts(id) ON DELETE CASCADE,
          PRIMARY KEY(service_id, part_id))
        """);
    // These are generic catalog entries, not vehicle-fitment or quantity specifications.
    String[][] parts = {
      {"OIL", "엔진오일", "L"}, {"OIL-FILTER", "오일필터", "EA"},
      {"AIR-FILTER", "엔진 에어필터", "EA"}, {"CABIN-FILTER", "에어컨 필터", "EA"},
      {"TIRE", "자동차 타이어", "EA"}, {"BATTERY", "자동차 12V 배터리", "EA"},
      {"FRONT-PAD", "앞 브레이크 패드 세트", "EA"}, {"REAR-PAD", "뒤 브레이크 패드 세트", "EA"},
      {"BRAKE-FLUID", "브레이크액", "L"}, {"COOLANT", "냉각수", "L"},
      {"SPARK-PLUG", "점화플러그", "EA"}, {"WIPER", "와이퍼 블레이드", "EA"},
      {"TRANS-FLUID", "변속기 오일", "L"}, {"BELT", "보조 구동벨트", "EA"},
      {"BULB", "전구", "EA"}, {"WASHER", "워셔액", "L"},
      {"DRAIN-WASHER", "드레인 플러그 와셔", "EA"}
    };
    var partIds = new HashMap<String, UUID>();
    for (var p : parts) {
      var existing =
          db.queryForList(
              "SELECT id FROM parts WHERE sku=? OR (LOWER(TRIM(name))=LOWER(?) AND unit=?) ORDER BY"
                  + " sku",
              "PF-" + p[0],
              p[1],
              p[2]);
      UUID id =
          existing.isEmpty()
              ? uuid("part/" + p[0])
              : UUID.fromString(existing.get(0).get("id").toString());
      if (existing.isEmpty())
        db.update(
            "INSERT INTO parts (id,sku,name,unit,quantity,minimum_quantity,unit_price,active)"
                + " VALUES (?,?,?,?,0,0,0,TRUE)",
            id,
            "PF-" + p[0],
            p[1],
            p[2]);
      partIds.put(p[0], id);
    }
    String[][] services = {
      {"엔진오일 교체", "20000", "30", "OIL,OIL-FILTER,DRAIN-WASHER"},
      {"타이어 교체", "40000", "60", "TIRE"},
      {"배터리 교체", "15000", "30", "BATTERY"},
      {"엔진 에어필터 교체", "10000", "30", "AIR-FILTER"},
      {"에어컨 필터 교체", "10000", "30", "CABIN-FILTER"},
      {"앞 브레이크 패드 교체", "40000", "60", "FRONT-PAD"},
      {"뒤 브레이크 패드 교체", "40000", "60", "REAR-PAD"},
      {"브레이크액 교환", "40000", "60", "BRAKE-FLUID"},
      {"냉각수 교환", "40000", "60", "COOLANT"},
      {"점화플러그 교체", "40000", "60", "SPARK-PLUG"},
      {"와이퍼 교체", "5000", "30", "WIPER"},
      {"변속기 오일 교환", "60000", "90", "TRANS-FLUID"},
      {"보조 구동벨트 교체", "50000", "60", "BELT"},
      {"전구 교체", "10000", "30", "BULB"},
      {"워셔액 보충", "0", "30", "WASHER"},
      {"타이어 위치 교환", "20000", "30", ""},
      {"휠 얼라인먼트 점검·조정", "50000", "60", ""},
      {"차량 기본 점검", "20000", "30", ""}
    };
    for (var s : services) {
      var existing = db.queryForList("SELECT id FROM service_items WHERE name=?", s[0]);
      UUID id =
          existing.isEmpty()
              ? uuid("service/" + s[0])
              : UUID.fromString(existing.get(0).get("id").toString());
      if (existing.isEmpty())
        db.update(
            """
            INSERT INTO service_items VALUES (?,?,?,?,?,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """,
            id,
            s[0],
            "시연용 공임입니다. 실제 견적이 아니며 부품비는 별도입니다. 차종별 규격과 수량은 작업 전에 확인합니다.",
            Integer.parseInt(s[1]),
            Integer.parseInt(s[2]));
      if (!s[3].isEmpty())
        for (String code : s[3].split(","))
          db.update(
              "INSERT INTO service_part_requirements (service_id,part_id) VALUES (?,?)",
              id,
              partIds.get(code));
    }
  }

  private static UUID uuid(String key) {
    return UUID.nameUUIDFromBytes(("pitflow/catalog/v4/" + key).getBytes(StandardCharsets.UTF_8));
  }
}
