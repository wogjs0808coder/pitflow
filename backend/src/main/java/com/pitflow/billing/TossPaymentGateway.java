package com.pitflow.billing;

import java.math.BigDecimal;
import java.util.UUID;

public interface TossPaymentGateway {
  record Payment(String paymentKey, String orderId, BigDecimal totalAmount, String status) {}

  boolean configured();
  String clientKey();
  Payment confirm(String paymentKey, String orderId, BigDecimal amount);
  Payment lookup(String paymentKey);
  Payment cancel(String paymentKey, String reason, UUID idempotencyKey);
}
