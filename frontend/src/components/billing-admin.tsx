"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { api, errorText, won } from "@/lib/api";
import { useAuth } from "./auth-provider";
import { useWorkCommand } from "./work-command";
import {
  BillingItem,
  Invoice,
  InvoiceDetail,
  Quote,
  Summary,
  billingKindLabel,
  billingMethodLabel,
  decimalNumber,
} from "@/lib/billing";
import { Work, workLabel, localTime } from "@/lib/work";
import { requestTossPayment, tossPaymentError } from "@/lib/toss-payment";

import { seoulToday as today } from "@/lib/appointments";
const monthStart = () => `${today().slice(0, 8)}01`;

function Fields({ e }: { e: FormEvent<HTMLFormElement> }) {
  e.preventDefault();
  return new FormData(e.currentTarget);
}

function LineItems({ items }: { items: BillingItem[] }) {
  return (
    <ul className="billing-lines">
      {items.map((item) => (
        <li key={`${item.kind}-${item.source_id}`}>
          <span>
            {item.kind === "LABOR" ? "공임" : "부품"} · {item.name} ·{" "}
            {String(item.quantity)} {item.unit}
          </span>
          <strong>{won(decimalNumber(item.amount))}</strong>
        </li>
      ))}
    </ul>
  );
}

export function BillingAdmin() {
  const { user } = useAuth();
  const [orders, setOrders] = useState<Work[]>([]);
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [quote, setQuote] = useState<Quote | null>(null);
  const [detail, setDetail] = useState<InvoiceDetail | null>(null);
  const [selectedWork, setSelectedWork] = useState("");
  const [selectedInvoice, setSelectedInvoice] = useState("");
  const [summary, setSummary] = useState<Summary | null>(null);
  const [from, setFrom] = useState(monthStart);
  const [to, setTo] = useState(today);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  const [confirmZero, setConfirmZero] = useState(false);
  const [payingInvoice, setPayingInvoice] = useState("");
  const reload = useCallback(async () => setRevision((value) => value + 1), []);
  const command = useWorkCommand(reload);

  useEffect(() => {
    const query = new URLSearchParams(window.location.search);
    const invoiceId = query.get("invoiceId");
    const workOrderId = query.get("workOrderId");
    if (invoiceId) setSelectedInvoice(invoiceId);
    else if (workOrderId) setSelectedWork(workOrderId);
  }, []);

  async function payWithToss(invoiceId: string) {
    setPayingInvoice(invoiceId);
    setError("");
    try {
      await requestTossPayment(invoiceId, true);
    } catch (reason) {
      setError(tossPaymentError(reason));
      setPayingInvoice("");
    }
  }

  useEffect(() => {
    if (user?.role !== "ADMIN") return;
    const controller = new AbortController();
    setLoading(true);
    setError("");
    Promise.all([
      api<Work[]>("/api/admin/work-orders", { signal: controller.signal }),
      api<Invoice[]>("/api/admin/billing/invoices", {
        signal: controller.signal,
      }),
    ])
      .then(([workOrders, invoiceRows]) => {
        if (controller.signal.aborted) return;
        setOrders(workOrders.filter((work) => work.status === "COMPLETED"));
        setInvoices(invoiceRows);
        const active = invoiceRows.find(
          (row) => row.work_order_id === selectedWork && row.status === "OPEN",
        );
        if (active) {
          setSelectedInvoice(active.id);
          setSelectedWork("");
        }
        setSummary(null);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(errorText(e));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [user?.role, revision, selectedWork]);

  useEffect(() => {
    if (!selectedWork || user?.role !== "ADMIN") {
      setQuote(null);
      return;
    }
    const controller = new AbortController();
    setQuote(null);
    api<Quote>(`/api/admin/billing/work-orders/${selectedWork}/preview`, {
      signal: controller.signal,
    })
      .then((value) => {
        if (!controller.signal.aborted) {
          setQuote(value);
          setConfirmZero(false);
        }
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(errorText(e));
      });
    return () => controller.abort();
  }, [selectedWork, user?.role, revision]);

  useEffect(() => {
    if (!selectedInvoice || user?.role !== "ADMIN") {
      setDetail(null);
      return;
    }
    const controller = new AbortController();
    setDetail(null);
    api<InvoiceDetail>(`/api/admin/billing/invoices/${selectedInvoice}`, {
      signal: controller.signal,
    })
      .then((value) => {
        if (!controller.signal.aborted) setDetail(value);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(errorText(e));
      });
    return () => controller.abort();
  }, [selectedInvoice, user?.role, revision]);

  if (user?.role !== "ADMIN")
    return <p role="alert">관리자만 이용할 수 있습니다.</p>;
  const disabled = command.blocked || loading || !!error;
  const issueable = orders.filter(
    (order) =>
      !invoices.some(
        (invoice) =>
          invoice.work_order_id === order.id && invoice.status === "OPEN",
      ),
  );

  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">BILLING & HISTORY</span>
          <h1>정산·수납 관리</h1>
          <p>
            완료된 작업의 당시 공임과 실제 사용 부품을 확인하고 현장 수납을
            기록하세요.
          </p>
        </div>
        <button
          className="button secondary"
          disabled={command.busy}
          onClick={() => void reload()}
        >
          새로고침
        </button>
      </div>
      {command.feedback}
      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}

      <section className="work-panel">
        <h2>운영 현황</h2>
        <form
          className="billing-period"
          onSubmit={(e) => {
            const fields = Fields({ e });
            setError("");
            api<Summary>(
              `/api/admin/billing/summary?from=${fields.get("from")}&to=${fields.get("to")}`,
            )
              .then(setSummary)
              .catch((reason) => setError(errorText(reason)));
          }}
        >
          <label>
            시작일
            <input
              name="from"
              type="date"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
              required
            />
          </label>
          <label>
            종료일
            <input
              name="to"
              type="date"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              required
            />
          </label>
          <button className="button secondary">현황 조회</button>
        </form>
        {summary && (
          <div className="billing-metrics">
            <div>
              <span>완료 작업</span>
              <strong>{summary.completed_count}건</strong>
            </div>
            <div>
              <span>수납</span>
              <strong>{won(decimalNumber(summary.received))}</strong>
            </div>
            <div>
              <span>수납 취소</span>
              <strong>{won(decimalNumber(summary.reversed))}</strong>
            </div>
            <div>
              <span>순수납</span>
              <strong>{won(decimalNumber(summary.net))}</strong>
            </div>
          </div>
        )}
      </section>

      <div className="work-layout">
        <section>
          <div className="section-heading">
            <h2>완료 작업 정산</h2>
            <span className="count-label">미발행 {issueable.length}건</span>
          </div>
          {loading ? (
            <p role="status">완료 작업을 불러오는 중입니다…</p>
          ) : issueable.length ? (
            issueable.map((order) => (
              <button
                key={order.id}
                className={`work-panel work-select ${selectedWork === order.id ? "selected-work" : ""}`}
                onClick={() => {
                  setSelectedWork(order.id);
                  setSelectedInvoice("");
                }}
                aria-pressed={selectedWork === order.id}
                disabled={command.busy}
              >
                <span className="eyebrow">
                  {workLabel[order.status]} · 정산 전
                </span>
                <strong>
                  {order.vehicle_label} · {order.plate_number}
                </strong>
                <span>
                  {localTime(order.received_at)} · 입고{" "}
                  {order.received_mileage.toLocaleString()} km
                </span>
                <span>담당 {order.mechanic_name ?? "미배정"}</span>
              </button>
            ))
          ) : (
            <p className="empty-state">정산할 완료 작업이 없습니다.</p>
          )}

          <div className="section-heading">
            <h2>발행된 정산 명세</h2>
            <span className="count-label">{invoices.length}건</span>
          </div>
          {invoices.map((invoice) => (
            <button
              key={invoice.id}
              className={`work-panel work-select ${selectedInvoice === invoice.id ? "selected-work" : ""}`}
              onClick={() => {
                setSelectedInvoice(invoice.id);
                setSelectedWork("");
              }}
              aria-pressed={selectedInvoice === invoice.id}
              disabled={command.busy}
            >
              <span className="eyebrow">
                {invoice.status === "OPEN" ? "유효 명세" : "취소 명세"}
              </span>
              <strong>
                {invoice.vehicle_label} · {invoice.plate_number}
              </strong>
              <span>
                {localTime(invoice.issued_at)} ·{" "}
                {won(decimalNumber(invoice.total))}
              </span>
              <span>수납 {won(decimalNumber(invoice.paid))}</span>
            </button>
          ))}
        </section>

        <section>
          {quote && (
            <div className="work-panel">
              <h2>정산 미리보기</h2>
              <p>
                {
                  orders.find((order) => order.id === quote.work_order_id)
                    ?.plate_number
                }
              </p>
              <LineItems items={quote.items} />
              <p className="billing-total">
                합계 <strong>{won(decimalNumber(quote.total))}</strong>
              </p>
              {quote.has_zero_prices && (
                <label className="check-label">
                  <input
                    type="checkbox"
                    checked={confirmZero}
                    onChange={(e) => setConfirmZero(e.target.checked)}
                  />{" "}
                  단가 0원 항목을 확인했습니다.
                </label>
              )}
              <button
                className="button primary"
                disabled={disabled || (quote.has_zero_prices && !confirmZero)}
                onClick={() =>
                  void command.run(
                    `/api/admin/billing/work-orders/${quote.work_order_id}/invoices`,
                    {
                      expectedFingerprint: quote.fingerprint,
                      confirmZeroPrices: confirmZero,
                    },
                  )
                }
              >
                정산 명세 발행
              </button>
              <p className="muted">
                발행 후 부품 반환이 발생하면 기존 명세는 수정하지 않고 취소 후
                다시 발행합니다.
              </p>
            </div>
          )}
          {selectedInvoice && !detail && (
            <p role="status">정산 명세를 불러오는 중입니다…</p>
          )}
          {detail && (
            <div className="work-panel">
              <h2>정산 명세 상세 · {detail.plate_number}</h2>
              <p>
                {detail.status === "OPEN" ? "유효" : "취소됨"} · 발행{" "}
                {localTime(detail.issued_at)}
              </p>
              {detail.stale && (
                <div className="error">
                  작업의 부품 반환 내역이 발행 당시와 달라졌습니다. 수납 전에
                  명세를 취소하고 다시 발행하세요.
                </div>
              )}
              <LineItems items={detail.items} />
              <p className="billing-total">
                합계 <strong>{won(decimalNumber(detail.total))}</strong>
              </p>
              <p>
                수납 {won(decimalNumber(detail.paid))} · 잔액{" "}
                {won(decimalNumber(detail.balance))}
              </p>
              {detail.status === "OPEN" &&
                decimalNumber(detail.balance) > 0 &&
                !detail.stale && (
                  <div className="billing-collection-options">
                    <div className="billing-payment-cta">
                      <h3>카운터 Toss 결제</h3>
                      <p className="muted">
                        결제 고객은 관리자 계정이 아니라 이 명세의 고객으로 기록됩니다.
                      </p>
                      <button
                        className="button primary"
                        disabled={disabled || !!payingInvoice}
                        onClick={() => void payWithToss(detail.id)}
                      >
                        {payingInvoice === detail.id
                          ? "결제창 준비 중…"
                          : `${won(decimalNumber(detail.balance))} Toss 결제`}
                      </button>
                    </div>
                    <form
                      onSubmit={(e) => {
                        const fields = Fields({ e });
                        void command.run(
                          `/api/admin/billing/invoices/${detail.id}/payments`,
                          {
                            method: fields.get("method"),
                            reference: fields.get("reference"),
                            expectedTotal: String(detail.total),
                          },
                        );
                      }}
                    >
                      <h3>현장 수납</h3>
                      <fieldset disabled={disabled || !!payingInvoice}>
                        <label>
                          수납 방법
                          <select name="method" defaultValue="CARD">
                            <option value="CASH">현금</option>
                            <option value="CARD">카드</option>
                            <option value="TRANSFER">계좌이체</option>
                          </select>
                        </label>
                        <label>
                          승인번호·메모
                          <input name="reference" maxLength={100} />
                        </label>
                        <button className="button secondary">
                          {won(decimalNumber(detail.balance))} 현장 수납
                        </button>
                      </fieldset>
                    </form>
                  </div>
                )}
              {detail.status === "OPEN" && decimalNumber(detail.paid) === 0 && (
                <form
                  onSubmit={(e) => {
                    const fields = Fields({ e });
                    void command.run(
                      `/api/admin/billing/invoices/${detail.id}/void`,
                      { reason: fields.get("reason") },
                    );
                  }}
                >
                  <h3>명세 취소</h3>
                  <fieldset disabled={disabled}>
                    <label>
                      취소 사유
                      <input name="reason" required maxLength={500} />
                    </label>
                    <button className="button secondary">정산 명세 취소</button>
                  </fieldset>
                </form>
              )}
              <h3>수납 이력</h3>
              {detail.payments.map((payment) => (
                <article className="work-movement" key={payment.id}>
                  <strong>
                    {billingKindLabel[payment.kind]} ·{" "}
                    {won(decimalNumber(payment.amount))} ·{" "}
                    {billingMethodLabel[payment.method]}
                  </strong>
                  <p>
                    {localTime(payment.created_at)} · {payment.reason}
                    {payment.reference && ` · ${payment.reference}`}
                  </p>
                  {payment.kind === "PAYMENT" &&
                    !detail.payments.some(
                      (item) => item.original_payment_id === payment.id,
                    ) && (
                      <form
                        onSubmit={(e) => {
                          const fields = Fields({ e });
                          void command.run(
                            payment.provider === "TOSS"
                              ? `/api/admin/billing/payments/${payment.id}/toss-refund`
                              : `/api/admin/billing/payments/${payment.id}/reverse`,
                            { reason: fields.get("reason") },
                          );
                        }}
                      >
                        <fieldset disabled={disabled}>
                          <label>
                            취소 사유
                            <input name="reason" required maxLength={500} />
                          </label>
                          <button className="button secondary">
                            {payment.provider === "TOSS" ? "Toss 결제 환불" : "이 수납 취소"}
                          </button>
                        </fieldset>
                      </form>
                    )}
                </article>
              ))}
              {!detail.payments.length && <p>수납 기록이 없습니다.</p>}
            </div>
          )}
          {!quote && !detail && (
            <p>완료 작업 또는 정산 명세를 선택해 주세요.</p>
          )}
        </section>
      </div>
      <p className="info-note">
        현장 수납은 관리자가 확인한 결제 사실을 기록합니다. 고객의 Toss 테스트
        결제는 승인 후 자동 반영되며 이 화면에서 결제사 환불을 진행합니다.
      </p>
      <Link className="inline-link" href="/admin/work-orders">
        정비 작업 관리로 이동 →
      </Link>
    </>
  );
}
