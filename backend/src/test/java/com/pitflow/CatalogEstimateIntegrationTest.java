package com.pitflow;

import static org.assertj.core.api.Assertions.*;

import com.pitflow.catalog.*;
import com.pitflow.catalog.ServiceRequest.PartRequirement;
import com.pitflow.common.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CatalogEstimateIntegrationTest {
  @Autowired CatalogService catalog;
  @Autowired JdbcTemplate db;
  UUID tirePart;
  UUID washerPart;

  @BeforeEach
  void ensurePartFixtures() {
    tirePart = ensurePart("PF-TIRE", "테스트 타이어", "EA", new BigDecimal("100000"));
    washerPart = ensurePart("PF-WASHER", "테스트 워셔액", "L", new BigDecimal("5000"));
  }

  private UUID ensurePart(String sku, String name, String unit, BigDecimal unitPrice) {
    UUID id =
        UUID.nameUUIDFromBytes(("catalog-estimate/" + sku).getBytes(StandardCharsets.UTF_8));
    db.update(
        """
        INSERT INTO parts(
            id,sku,name,unit,quantity,minimum_quantity,unit_price,active,archived,description
        )
        SELECT ?,?,?,?,0,0,?,TRUE,FALSE,''
        WHERE NOT EXISTS (SELECT 1 FROM parts WHERE sku=?)
        """,
        id,
        sku,
        name,
        unit,
        unitPrice,
        sku);
    db.update(
        "UPDATE parts SET name=?,unit=?,unit_price=?,active=TRUE,archived=FALSE WHERE sku=?",
        name,
        unit,
        unitPrice,
        sku);
    return db.queryForObject("SELECT id FROM parts WHERE sku=?", UUID.class, sku);
  }

  @Test
  void adminCanSaveConfirmedPartRequirementsAndSeeEstimatedTotal() {
    BigDecimal tirePrice =
        db.queryForObject("SELECT unit_price FROM parts WHERE id=?", BigDecimal.class, tirePart);

    ServiceView created =
        catalog.create(
            new ServiceRequest(
                "견적 통합 테스트",
                "관리자 부품 구성 저장 검증",
                new BigDecimal("10000"),
                60,
                true,
                List.of(new PartRequirement(tirePart, new BigDecimal("2")))));

    assertThat(created.requirementsConfirmed()).isTrue();
    assertThat(created.parts()).hasSize(1);
    assertThat(created.parts().get(0).partId()).isEqualTo(tirePart);
    assertThat(created.parts().get(0).quantity()).isEqualByComparingTo("2");
    assertThat(created.estimatedPartsPrice())
        .isEqualByComparingTo(tirePrice.multiply(new BigDecimal("2")));
    assertThat(created.estimatedTotalPrice())
        .isEqualByComparingTo(tirePrice.multiply(new BigDecimal("2")).add(new BigDecimal("10000")));

    assertThat(
            db.queryForObject(
                "SELECT requirements_confirmed FROM service_items WHERE id=?",
                Boolean.class,
                created.id()))
        .isTrue();
    assertThat(
            db.queryForObject(
                "SELECT required_quantity FROM service_part_requirements WHERE service_id=? AND part_id=?",
                BigDecimal.class,
                created.id(),
                tirePart))
        .isEqualByComparingTo("2");
  }

  @Test
  void emptyRequirementListConfirmsLaborOnlyService() {
    ServiceView created =
        catalog.create(
            new ServiceRequest(
                "공임 전용 테스트",
                "부품 없는 서비스",
                new BigDecimal("20000"),
                30,
                true,
                List.of()));

    assertThat(created.requirementsConfirmed()).isTrue();
    assertThat(created.parts()).isEmpty();
    assertThat(created.estimatedPartsPrice()).isEqualByComparingTo("0");
    assertThat(created.estimatedTotalPrice()).isEqualByComparingTo("20000");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM service_part_requirements WHERE service_id=?",
                Integer.class,
                created.id()))
        .isZero();
  }

  @Test
  void washerServiceKeepsVariableQuantityWhenAdminSavesIt() {
    UUID washerService =
        UUID.fromString("f6b2e966-cf84-3576-9a3f-a64ebf1de473");

    // Other integration tests may clear service_items from the shared CI test DB.
    // This test owns its prerequisite instead of depending on migration seed state.
    db.update(
        """
        INSERT INTO service_items(
            id,
            name,
            description,
            labor_price,
            duration_minutes,
            active,
            created_at,
            updated_at,
            requirements_confirmed
        )
        SELECT
            ?,
            '워셔액 보충 서비스',
            '실제 제공량은 작업 시 확정합니다.',
            0,
            30,
            TRUE,
            CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP,
            FALSE
        WHERE NOT EXISTS (
            SELECT 1
            FROM service_items
            WHERE id=?
        )
        """,
        washerService,
        washerService);

    ServiceView saved =
        catalog.update(
            washerService,
            new ServiceRequest(
                "워셔액 보충 서비스",
                "실제 제공량은 작업 시 확정합니다.",
                BigDecimal.ZERO,
                30,
                true,
                List.of(new PartRequirement(washerPart, null))));

    assertThat(saved.requirementsConfirmed()).isTrue();
    assertThat(saved.parts()).hasSize(1);
    assertThat(saved.parts().get(0).partId()).isEqualTo(washerPart);
    assertThat(saved.parts().get(0).quantity()).isNull();
    assertThat(saved.estimatedPartsPrice()).isEqualByComparingTo("0");
    assertThat(saved.estimatedTotalPrice()).isEqualByComparingTo("0");

    assertThat(
            db.queryForObject(
                """
                SELECT required_quantity
                FROM service_part_requirements
                WHERE service_id=? AND part_id=?
                """,
                BigDecimal.class,
                washerService,
                washerPart))
        .isNull();

    assertThat(
            db.queryForObject(
                """
                SELECT quantity_confirmed
                FROM service_part_requirements
                WHERE service_id=? AND part_id=?
                """,
                Boolean.class,
                washerService,
                washerPart))
        .isFalse();
  }

  @Test
  void inactivePartCannotBecomeAConfirmedRequirement() {
    db.update("UPDATE parts SET active=FALSE WHERE id=?", tirePart);

    assertThatThrownBy(
            () ->
                catalog.create(
                    new ServiceRequest(
                        "비활성 부품 테스트",
                        "차단 검증",
                        new BigDecimal("10000"),
                        30,
                        true,
                        List.of(new PartRequirement(tirePart, BigDecimal.ONE)))))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("비활성");
  }
}
