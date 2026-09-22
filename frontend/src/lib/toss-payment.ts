import { loadTossPayments } from "@tosspayments/tosspayments-sdk";
import { api, errorText } from "./api";
import { TossOrder } from "./billing";

export async function requestTossPayment(invoiceId: string, admin = false) {
  const order = await api<TossOrder>(
    admin
      ? `/api/admin/billing/invoices/${invoiceId}/toss/orders`
      : `/api/billing/invoices/${invoiceId}/toss/orders`,
    { method: "POST", headers: { "Idempotency-Key": crypto.randomUUID() } },
  );
  const tossPayments = await loadTossPayments(order.clientKey);
  const payment = tossPayments.payment({ customerKey: order.customerKey });
  const context = admin ? "&admin=1" : "";
  await payment.requestPayment({
    method: "CARD",
    amount: { currency: "KRW", value: Number(order.amount) },
    orderId: order.orderId,
    orderName: order.orderName,
    customerEmail: order.customerEmail,
    customerName: order.customerName,
    successUrl: `${window.location.origin}/billing/toss/success?invoiceId=${order.invoiceId}${context}`,
    failUrl: `${window.location.origin}/billing/toss/fail?invoiceId=${order.invoiceId}${context}`,
    card: {
      useEscrow: false,
      flowMode: "DEFAULT",
      useCardPoint: false,
      useAppCardOnly: false,
    },
  });
}

export function tossPaymentError(reason: unknown) {
  const safe = reason as { message?: unknown; code?: unknown };
  if (typeof safe?.message === "string" && safe.message.trim()) {
    return safe.message.trim().slice(0, 300);
  }
  if (typeof safe?.code === "string" && safe.code.trim()) {
    return `결제를 완료하지 못했습니다. (${safe.code.trim().slice(0, 80)})`;
  }
  return errorText(reason);
}
