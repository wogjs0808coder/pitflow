"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, errorText } from "@/lib/api";

type Props = {
  invoiceId: string;
  paymentKey?: string;
  orderId?: string;
  amount?: string;
  failure?: string;
  admin?: boolean;
};

export function TossPaymentResult({ invoiceId, paymentKey, orderId, amount, failure, admin = false }: Props) {
  const [state, setState] = useState<"confirming" | "done" | "failed">("confirming");
  const [message, setMessage] = useState(failure ? "중단된 결제 시도를 정리하고 있습니다…" : "결제 승인을 확인하고 있습니다…");

  useEffect(() => {
    if (failure) {
      if (!invoiceId || !orderId) {
        setState("failed");
        setMessage(failure);
        return;
      }
      const storageKey = `pitflow:toss-abandon:${orderId}`;
      let key = sessionStorage.getItem(storageKey);
      if (!key) {
        key = crypto.randomUUID();
        sessionStorage.setItem(storageKey, key);
      }
      api(
        admin
          ? `/api/admin/billing/invoices/${invoiceId}/toss/orders/${encodeURIComponent(orderId)}`
          : `/api/billing/invoices/${invoiceId}/toss/orders/${encodeURIComponent(orderId)}`,
        { method: "DELETE", headers: { "Idempotency-Key": key } },
      )
        .then(() => {
          sessionStorage.removeItem(storageKey);
          setState("failed");
          setMessage(`${failure} 다른 결제 방법을 선택하거나 다시 시도할 수 있습니다.`);
        })
        .catch((reason) => {
          setState("failed");
          setMessage(errorText(reason));
        });
      return;
    }
    if (!invoiceId || !paymentKey || !orderId || !amount) return;
    const storageKey = `pitflow:toss-confirm:${orderId}`;
    let key = sessionStorage.getItem(storageKey);
    if (!key) {
      key = crypto.randomUUID();
      sessionStorage.setItem(storageKey, key);
    }
    api(admin ? `/api/admin/billing/toss/confirm` : `/api/billing/toss/confirm`, {
      method: "POST",
      headers: { "Idempotency-Key": key },
      body: JSON.stringify({ paymentKey, orderId, amount, invoiceId }),
    })
      .then(() => {
        sessionStorage.removeItem(storageKey);
        setState("done");
        setMessage("결제가 승인되어 정산 명세에 반영되었습니다.");
      })
      .catch((reason) => {
        setState("failed");
        setMessage(errorText(reason));
      });
  }, [admin, amount, failure, invoiceId, orderId, paymentKey]);

  return (
    <section className="work-panel payment-result" aria-live="polite">
      <span className="eyebrow">TOSS TEST PAYMENT</span>
      <h1>{state === "done" ? "결제 완료" : state === "failed" ? "결제 확인 필요" : "결제 승인 중"}</h1>
      <p>{message}</p>
      <Link
        className="button secondary"
        href={admin ? `/admin/billing?invoiceId=${invoiceId}` : "/history"}
      >
        {admin ? "정산·수납 관리로 돌아가기" : "정비 이력·수납으로 돌아가기"}
      </Link>
    </section>
  );
}
