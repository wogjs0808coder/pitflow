package com.pitflow.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitflow.common.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TossPaymentHttpGateway implements TossPaymentGateway {
  private final String clientKey;
  private final String secretKey;
  private final RestClient http;
  private final ObjectMapper json;

  public TossPaymentHttpGateway(
      @Value("${pitflow.toss.client-key:}") String clientKey,
      @Value("${pitflow.toss.secret-key:}") String secretKey,
      @Value("${pitflow.toss.api-base-url:https://api.tosspayments.com}") String apiBaseUrl,
      ObjectMapper json) {
    this.clientKey = clientKey.strip();
    this.secretKey = secretKey.strip();
    this.json = json;
    String basic = Base64.getEncoder().encodeToString((this.secretKey + ":").getBytes(StandardCharsets.UTF_8));
    this.http = RestClient.builder().baseUrl(apiBaseUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic).build();
  }

  @Override public boolean configured() { return !clientKey.isBlank() && !secretKey.isBlank(); }
  @Override public String clientKey() { return clientKey; }

  @Override
  public Payment confirm(String paymentKey, String orderId, BigDecimal amount) {
    return call(() -> http.post().uri("/v1/payments/confirm")
        .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
        .retrieve().body(String.class));
  }

  @Override
  public Payment lookup(String paymentKey) {
    return call(() -> http.get().uri("/v1/payments/{paymentKey}", paymentKey)
        .retrieve().body(String.class));
  }

  @Override
  public Payment cancel(String paymentKey, String reason, UUID idempotencyKey) {
    return call(() -> http.post().uri("/v1/payments/{paymentKey}/cancel", paymentKey)
        .header("Idempotency-Key", idempotencyKey.toString())
        .body(Map.of("cancelReason", reason)).retrieve().body(String.class));
  }

  private Payment call(java.util.function.Supplier<String> request) {
    if (!configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Toss 테스트 결제가 설정되지 않았습니다.");
    try {
      JsonNode value = json.readTree(request.get());
      return new Payment(value.path("paymentKey").asText(), value.path("orderId").asText(),
          value.path("totalAmount").decimalValue(), value.path("status").asText());
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "결제사 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
  }
}
