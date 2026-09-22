"use client";

import { useEffect, useState } from "react";
import { api, errorText, won } from "@/lib/api";
import { useAuth } from "./auth-provider";
import {
  billingKindLabel,
  billingMethodLabel,
  HistoryWork,
  InvoiceDetail,
} from "@/lib/billing";
import { localTime, workLabel } from "@/lib/work";
import { Vehicle } from "@/lib/api";
import { requestTossPayment, tossPaymentError } from "@/lib/toss-payment";

export function BillingHistory() {
  const { user } = useAuth();
  const [vehicles, setVehicles] = useState<Vehicle[]>([]);
  const [history, setHistory] = useState<HistoryWork[]>([]);
  const [selectedVehicle, setSelectedVehicle] = useState("");
  const [selectedInvoice, setSelectedInvoice] = useState("");
  const [invoice, setInvoice] = useState<InvoiceDetail | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [payingInvoice, setPayingInvoice] = useState("");

  async function pay(invoiceId: string) {
    setPayingInvoice(invoiceId);
    setError("");
    try {
      await requestTossPayment(invoiceId);
    } catch (reason) {
      setError(tossPaymentError(reason));
      setPayingInvoice("");
    }
  }

  useEffect(() => {
    if (!user) return;
    api<Vehicle[]>("/api/vehicles")
      .then(setVehicles)
      .catch((e) => setError(errorText(e)));
  }, [user]);

  useEffect(() => {
    if (!user) return;
    const controller = new AbortController();
    setSelectedInvoice("");
    setInvoice(null);
    setLoading(true);
    setError("");
    const query = selectedVehicle ? `?vehicleId=${selectedVehicle}` : "";
    api<HistoryWork[]>(`/api/billing/history${query}`, {
      signal: controller.signal,
    })
      .then((rows) => {
        if (!controller.signal.aborted) setHistory(rows);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(errorText(e));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [user, selectedVehicle]);

  useEffect(() => {
    setInvoice(null);
    if (!selectedInvoice || !user) return;
    const controller = new AbortController();
    api<InvoiceDetail>(`/api/billing/invoices/${selectedInvoice}`, {
      signal: controller.signal,
    })
      .then((row) => {
        if (!controller.signal.aborted) setInvoice(row);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(errorText(e));
      });
    return () => controller.abort();
  }, [selectedInvoice, user]);

  if (!user) return <p role="alert">로그인이 필요합니다.</p>;
  const unpaid = history
    .flatMap((work) => work.invoices)
    .filter((row) => row.status === "OPEN" && Number(row.balance) > 0);
  const unpaidTotal = unpaid.reduce((sum, row) => sum + Number(row.balance), 0);
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">MY SERVICE HISTORY</span>
          <h1>정비 이력·수납</h1>
          <p>내 차량의 완료·취소 작업과 발행된 정산 명세를 확인하세요.</p>
        </div>
      </div>
      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}
      {unpaid.length > 0 && (
        <section className="work-panel history-payment-summary">
          <div>
            <span className="eyebrow">PAYMENT DUE</span>
            <strong>
              미결제 정산 {unpaid.length}건 · {won(unpaidTotal)}
            </strong>
            {unpaid.length > 1 && (
              <p className="muted">각 정산 명세는 한 건씩 결제됩니다.</p>
            )}
          </div>
          <button
            className="button primary"
            disabled={!!payingInvoice}
            onClick={() => void pay(unpaid[0].id)}
          >
            {payingInvoice === unpaid[0].id
              ? "결제창 준비 중…"
              : `${won(Number(unpaid[0].balance))} 결제하기`}
          </button>
        </section>
      )}
      <div className="history-filter">
        <label>
          차량 선택
          <select
            value={selectedVehicle}
            onChange={(e) => setSelectedVehicle(e.target.value)}
          >
            <option value="">모든 차량</option>
            {vehicles.map((vehicle) => (
              <option key={vehicle.id} value={vehicle.id}>
                {vehicle.manufacturer} {vehicle.model} · {vehicle.plateNumber}
              </option>
            ))}
          </select>
        </label>
      </div>
      {loading ? (
        <p role="status">정비 이력을 불러오는 중입니다…</p>
      ) : history.length ? (
        <div className="history-list">
          {history.map((work) => (
            <article className="work-panel" key={work.id}>
              <div className="section-heading">
                <div>
                  <span className="eyebrow">{workLabel[work.status]}</span>
                  <h2>
                    {work.vehicle_label} · {work.plate_number}
                  </h2>
                </div>
                <span>{localTime(work.received_at)}</span>
              </div>
              <p>
                입고 주행거리 {work.received_mileage.toLocaleString()} km · 담당{" "}
                {work.mechanic_name ?? "미배정"}
              </p>
              {work.notes && <p className="muted">요청사항: {work.notes}</p>}
              <h3>정비 항목</h3>
              <ul className="work-items">
                {work.items.map((item) => (
                  <li key={item.name}>
                    <span>{item.name}</span>
                    <span>
                      {item.done ? "완료" : "미완료"} ·{" "}
                      {won(Number(item.labor_price))}
                    </span>
                  </li>
                ))}
              </ul>
              <h3>정산 명세</h3>
              {work.invoices.length ? (
                work.invoices.map((row) => (
                  <div className="history-invoice-block" key={row.id}>
                    <button
                      className="history-invoice"
                      onClick={() => setSelectedInvoice(row.id)}
                    >
                      <span>
                        {row.status === "OPEN" ? "유효" : "취소"} ·{" "}
                        {localTime(row.issued_at)}
                      </span>
                      <strong>{won(Number(row.total))}</strong>
                    </button>
                    {row.status === "VOID" ? (
                      <span className="muted">취소된 명세</span>
                    ) : Number(row.balance) > 0 ? (
                      <div className="history-invoice-payment">
                        <span>
                          정산금액 {won(Number(row.total))} · 미결제{" "}
                          {won(Number(row.balance))}
                        </span>
                        <button
                          className="button primary compact-button"
                          disabled={!!payingInvoice}
                          onClick={() => void pay(row.id)}
                        >
                          {payingInvoice === row.id
                            ? "결제창 준비 중…"
                            : `${won(Number(row.balance))} 결제하기`}
                        </button>
                      </div>
                    ) : (
                      <strong className="history-payment-complete">결제 완료</strong>
                    )}
                  </div>
                ))
              ) : (
                <p>정산 준비 중</p>
              )}
            </article>
          ))}
        </div>
      ) : (
        <p className="empty-state">완료되거나 취소된 정비 이력이 없습니다.</p>
      )}
      {invoice && (
        <div className="work-panel">
          <h2>정산 명세 상세 · {invoice.plate_number}</h2>
          <p>
            {invoice.status === "OPEN" ? "유효" : "취소됨"} ·{" "}
            {localTime(invoice.issued_at)}
          </p>
          <LineItemsCustomer items={invoice.items} />
          <p className="billing-total">
            합계 <strong>{won(Number(invoice.total))}</strong>
          </p>
          <p>
            수납 {won(Number(invoice.paid))} · 잔액{" "}
            {won(Number(invoice.balance))}
          </p>
          {invoice.status === "OPEN" && Number(invoice.balance) > 0 && (
            <div className="billing-payment-cta">
              <button
                className="button primary"
                disabled={!!payingInvoice}
                onClick={() => void pay(invoice.id)}
              >
                {payingInvoice === invoice.id
                  ? "결제창 준비 중…"
                  : `${won(Number(invoice.balance))} 결제하기`}
              </button>
              <p className="muted">테스트 결제 환경에서만 승인되며, 승인 완료 후 수납으로 반영됩니다.</p>
            </div>
          )}
          <h3>수납 이력</h3>
          {invoice.payments.map((payment) => (
            <article className="work-movement" key={payment.id}>
              <strong>
                {billingKindLabel[payment.kind]} · {won(Number(payment.amount))}{" "}
                · {billingMethodLabel[payment.method]}
              </strong>
              <p>
                {localTime(payment.created_at)} · {payment.reason}
              </p>
            </article>
          ))}
        </div>
      )}
    </>
  );
}

function LineItemsCustomer({ items }: { items: InvoiceDetail["items"] }) {
  return (
    <ul className="billing-lines">
      {items.map((item) => (
        <li key={`${item.kind}-${item.source_id}`}>
          <span>
            {item.kind === "LABOR" ? "공임" : "부품"} · {item.name} ·{" "}
            {String(item.quantity)} {item.unit}
          </span>
          <strong>{won(Number(item.amount))}</strong>
        </li>
      ))}
    </ul>
  );
}
