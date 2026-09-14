package com.pitflow;

import static org.assertj.core.api.Assertions.*;

import com.pitflow.catalog.*;
import com.pitflow.catalog.ServiceRequest.PartRequirement;
import com.pitflow.common.ApiException;
import java.math.BigDecimal;
import java.util.*;
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

  @Test
  void adminCanSaveConfirmedPartRequirementsAndSeeEstimatedTotal() {
    UUID tire = db.queryForObject("SELECT id FROM parts WHERE sku='PF-TIRE'", UUID.class);
    BigDecimal tirePrice =
        db.queryForObject("SELECT unit_price FROM parts WHERE id=?", BigDecimal.class, tire);

    ServiceView created =
        catalog.create(
            new ServiceRequest(
                "견적 통합 테스트",
                "관리자 부품 구성 저장 검증",
                new BigDecimal("10000"),
                60,
                true,
                List.of(new PartRequirement(tire, new BigDecimal("2")))));

    assertThat(created.requirementsConfirmed()).isTrue();
    assertThat(created.parts()).hasSize(1);
    assertThat(created.parts().get(0).partId()).isEqualTo(tire);
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
                tire))
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
  void inactivePartCannotBecomeAConfirmedRequirement() {
    UUID tire = db.queryForObject("SELECT id FROM parts WHERE sku='PF-TIRE'", UUID.class);
    db.update("UPDATE parts SET active=FALSE WHERE id=?", tire);

    assertThatThrownBy(
            () ->
                catalog.create(
                    new ServiceRequest(
                        "비활성 부품 테스트",
                        "차단 검증",
                        new BigDecimal("10000"),
                        30,
                        true,
                        List.of(new PartRequirement(tire, BigDecimal.ONE)))))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("비활성");
  }
}
