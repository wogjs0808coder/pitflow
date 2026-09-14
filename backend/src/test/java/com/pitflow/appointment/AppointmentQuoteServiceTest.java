package com.pitflow.appointment;

import static com.pitflow.appointment.AppointmentModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pitflow.common.ApiException;
import com.pitflow.user.UserRepository;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppointmentQuoteServiceTest {
  private static final UUID OIL = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TIRE = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID AIR = UUID.fromString("953e027e-f285-39fb-aa5c-cfa91e47a613");
  private static final UUID WASHER = UUID.fromString("f6b2e966-cf84-3576-9a3f-a64ebf1de473");

  private AppointmentRepository repository;
  private AppointmentService service;

  @BeforeEach
  void setup() {
    repository = mock(AppointmentRepository.class);
    service = new AppointmentService(repository, mock(UserRepository.class), mock(BookingPolicy.class));
    when(repository.quoteConflicts(anyList())).thenReturn(List.of());
    when(repository.quoteRequirements(anyList())).thenReturn(List.of());
  }

  @Test
  void engineOilQuoteIs85000WithFourLitersAndAirFilter() {
    UUID oilPart = UUID.randomUUID();
    UUID airPart = UUID.randomUUID();
    when(repository.quoteServices(List.of(OIL)))
        .thenReturn(List.of(serviceRow(OIL, "엔진오일 교체", 19000, 30)));
    when(repository.quoteRequirements(List.of(OIL)))
        .thenReturn(
            List.of(
                requirement(OIL, oilPart, "엔진오일", "L", "4", "12000"),
                requirement(OIL, airPart, "엔진 에어필터", "EA", "1", "18000")));

    Quote quote = service.quote(new QuoteRequest(List.of(new QuoteSelection(OIL, 1))));

    assertThat(quote.totalLaborPrice()).isEqualByComparingTo("19000");
    assertThat(quote.totalPartsPrice()).isEqualByComparingTo("66000");
    assertThat(quote.totalPrice()).isEqualByComparingTo("85000");
    assertThat(quote.durationMinutes()).isEqualTo(30);
    assertThat(quote.items().get(0).parts()).extracting(QuotePart::totalQuantity)
        .containsExactly(new BigDecimal("4"), new BigDecimal("1"));
    assertThat(quote.fingerprint()).hasSize(64);
  }

  @Test
  void tireQuantityScalesPriceAndDurationLinearly() {
    UUID tirePart = UUID.randomUUID();
    when(repository.quoteServices(List.of(TIRE)))
        .thenReturn(List.of(serviceRow(TIRE, "타이어 교체", 15000, 30)));
    when(repository.quoteRequirements(List.of(TIRE)))
        .thenReturn(List.of(requirement(TIRE, tirePart, "자동차 타이어", "EA", "1", "100000")));

    for (int quantity = 1; quantity <= 4; quantity++) {
      Quote quote = service.quote(new QuoteRequest(List.of(new QuoteSelection(TIRE, quantity))));
      assertThat(quote.totalPrice()).isEqualByComparingTo(BigDecimal.valueOf(115000L * quantity));
      assertThat(quote.durationMinutes()).isEqualTo(30 * quantity);
      assertThat(quote.items().get(0).parts().get(0).totalQuantity())
          .isEqualByComparingTo(BigDecimal.valueOf(quantity));
    }
  }

  @Test
  void nonTireServiceCannotUseQuantityGreaterThanOne() {
    assertThatThrownBy(
            () -> service.quote(new QuoteRequest(List.of(new QuoteSelection(OIL, 2)))))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("타이어");
    verify(repository, never()).quoteServices(anyList());
  }

  @Test
  void duplicateServiceSelectionIsRejected() {
    assertThatThrownBy(
            () ->
                service.quote(
                    new QuoteRequest(
                        List.of(new QuoteSelection(TIRE, 1), new QuoteSelection(TIRE, 1)))))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("중복");
  }

  @Test
  void explicitConflictRejectsEngineOilAndStandaloneAirFilter() {
    when(repository.quoteServices(List.of(OIL, AIR)))
        .thenReturn(
            List.of(
                serviceRow(OIL, "엔진오일 교체", 19000, 30),
                serviceRow(AIR, "엔진 에어필터 교체", 10000, 30)));
    when(repository.quoteConflicts(List.of(OIL, AIR)))
        .thenReturn(List.of(new QuoteConflictRow(OIL, AIR, "엔진오일 교체 항목에 엔진 에어필터가 포함되어 있습니다.")));

    assertThatThrownBy(
            () ->
                service.quote(
                    new QuoteRequest(
                        List.of(new QuoteSelection(OIL, 1), new QuoteSelection(AIR, 1)))))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("포함");
  }

  @Test
  void washerRequiresPaidServiceAndUsesVariableComplimentaryQuantity() {
    UUID washerPart = UUID.randomUUID();
    when(repository.quoteServices(List.of(WASHER)))
        .thenReturn(List.of(serviceRow(WASHER, "워셔액 보충 서비스", 0, 30)));
    when(repository.quoteRequirements(List.of(WASHER)))
        .thenReturn(List.of(variableRequirement(WASHER, washerPart, "워셔액", "L", "5000")));

    assertThatThrownBy(
            () -> service.quote(new QuoteRequest(List.of(new QuoteSelection(WASHER, 1)))))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("유상 정비");

    when(repository.quoteServices(List.of(TIRE, WASHER)))
        .thenReturn(
            List.of(
                serviceRow(TIRE, "타이어 교체", 15000, 30),
                serviceRow(WASHER, "워셔액 보충 서비스", 0, 30)));
    when(repository.quoteRequirements(List.of(TIRE, WASHER)))
        .thenReturn(
            List.of(
                requirement(TIRE, UUID.randomUUID(), "자동차 타이어", "EA", "1", "100000"),
                variableRequirement(WASHER, washerPart, "워셔액", "L", "5000")));

    Quote quote =
        service.quote(
            new QuoteRequest(List.of(new QuoteSelection(TIRE, 1), new QuoteSelection(WASHER, 1))));
    QuoteItem washer =
        quote.items().stream().filter(item -> item.serviceId().equals(WASHER)).findFirst().orElseThrow();
    assertThat(washer.totalAmount()).isEqualByComparingTo("0");
    assertThat(washer.partsAmount()).isEqualByComparingTo("0");
    assertThat(washer.parts().get(0).chargePolicy()).isEqualTo("COMPLIMENTARY");
    assertThat(washer.parts().get(0).requiredQuantityPerService()).isNull();
    assertThat(washer.parts().get(0).totalQuantity()).isNull();
    assertThat(quote.totalPrice()).isEqualByComparingTo("115000");
    assertThat(quote.fingerprint()).hasSize(64);
  }

  @Test
  void inactiveOrUnconfirmedPartPreventsQuote() {
    UUID part = UUID.randomUUID();
    when(repository.quoteServices(List.of(TIRE)))
        .thenReturn(List.of(serviceRow(TIRE, "타이어 교체", 15000, 30)));
    when(repository.quoteRequirements(List.of(TIRE)))
        .thenReturn(
            List.of(
                new QuoteRequirementRow(
                    TIRE,
                    part,
                    "자동차 타이어",
                    "EA",
                    BigDecimal.ONE,
                    new BigDecimal("100000"),
                    false,
                    false,
                    true)));

    assertThatThrownBy(
            () -> service.quote(new QuoteRequest(List.of(new QuoteSelection(TIRE, 1)))))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("비활성");
  }

  private static QuoteServiceRow serviceRow(
      UUID id, String name, long labor, int durationMinutes) {
    return new QuoteServiceRow(id, name, BigDecimal.valueOf(labor), durationMinutes, true);
  }

  private static QuoteRequirementRow variableRequirement(
      UUID serviceId,
      UUID partId,
      String name,
      String unit,
      String unitPrice) {
    return new QuoteRequirementRow(
        serviceId,
        partId,
        name,
        unit,
        null,
        new BigDecimal(unitPrice),
        true,
        false,
        false);
  }
  private static QuoteRequirementRow requirement(
      UUID serviceId,
      UUID partId,
      String name,
      String unit,
      String requiredQuantity,
      String unitPrice) {
    return new QuoteRequirementRow(
        serviceId,
        partId,
        name,
        unit,
        new BigDecimal(requiredQuantity),
        new BigDecimal(unitPrice),
        true,
        false,
        true);
  }
}
