"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { api, errorText, won } from "@/lib/api";
import { FinanceSummary, FinanceWork } from "@/lib/finance";
import { localTime } from "@/lib/work";

const dateText = (date: Date) => date.toISOString().slice(0, 10);
const today = new Date();
const initialTo = dateText(today);
const initialFrom = dateText(new Date(today.getFullYear(), today.getMonth(), 1));
const money = (value: number | null) => (value === null ? "미확정" : won(value));

export function FinanceAdmin() {
  const { user } = useAuth();
  const [from, setFrom] = useState(initialFrom);
  const [to, setTo] = useState(initialTo);
  const [period, setPeriod] = useState({ from: initialFrom, to: initialTo });
  const [summary, setSummary] = useState<FinanceSummary | null>(null);
  const [works, setWorks] = useState<FinanceWork[]>([]);
  const [selected, setSelected] = useState<FinanceWork | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    if (user?.role !== "ADMIN") return;
    setLoading(true);
    setError("");
    try {
      const query = `from=${period.from}&to=${period.to}`;
      const [nextSummary, nextWorks] = await Promise.all([
        api<FinanceSummary>(`/api/admin/finance/summary?${query}`),
        api<FinanceWork[]>(`/api/admin/finance/work-orders?${query}`),
      ]);
      setSummary(nextSummary);
      setWorks(nextWorks);
      setSelected((current) => nextWorks.find((work) => work.id === current?.id) ?? null);
    } catch (reason) {
      setError(errorText(reason));
    } finally {
      setLoading(false);
    }
  }, [period, user?.role]);

  useEffect(() => {
    void load();
  }, [load]);

  if (user?.role !== "ADMIN") return <p role="alert">관리자만 이용할 수 있습니다.</p>;
  const submit = (event: FormEvent) => {
    event.preventDefault();
    setPeriod({ from, to });
  };
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">FINANCE & COST</span>
          <h1>재무·원가 분석</h1>
          <p>발행 명세 매출과 완료 시점 인건비, 실제 FIFO 부품원가를 함께 확인합니다.</p>
        </div>
      </div>
      {error && <div className="error" role="alert">{error}</div>}
      <form className="billing-period" onSubmit={submit}>
        <label>시작일<input type="date" value={from} onChange={(event) => setFrom(event.target.value)} required /></label>
        <label>종료일<input type="date" value={to} onChange={(event) => setTo(event.target.value)} required /></label>
        <button className="button primary" disabled={loading}>조회</button>
      </form>
      {summary && (
        <section className="work-panel">
          <div className="billing-metrics finance-metrics">
            <div><span>매출</span><strong>{won(summary.revenue)}</strong></div>
            <div><span>부품 원가(확인분)</span><strong>{won(summary.parts_cost_known)}</strong></div>
            <div><span>인건비 원가(확인분)</span><strong>{won(summary.labor_cost_known)}</strong></div>
            <div><span>총원가</span><strong>{money(summary.total_cost)}</strong></div>
            <div><span>기여이익</span><strong>{money(summary.contribution_margin)}</strong></div>
            <div><span>수납 / 미수</span><strong>{won(summary.paid_amount)} / {won(summary.outstanding_amount)}</strong></div>
          </div>
          {summary.has_unknown_cost && <p className="finance-warning">UNKNOWN 원가가 있어 총원가와 기여이익은 미확정입니다.</p>}
        </section>
      )}
      <div className="work-layout">
        <section className="work-list" aria-label="작업별 재무 목록">
          {works.map((work) => (
            <button key={work.id} className={`work-panel work-select ${selected?.id === work.id ? "selected-work" : ""}`} onClick={() => setSelected(work)}>
              <span className="eyebrow">{localTime(work.completed_at)} · {work.invoice_issued ? "명세 발행" : "미발행"}</span>
              <strong>{work.vehicle_label} · {work.plate_number}</strong>
              <span>매출 {won(work.revenue)} · 기여이익 {money(work.contribution_margin)}</span>
              {work.has_unknown_cost && <span className="finance-unknown">원가 미확정</span>}
            </button>
          ))}
          {!loading && !works.length && <p className="empty-state">기간 내 완료 작업이 없습니다.</p>}
        </section>
        <section className="work-panel">
          {selected ? (
            <>
              <span className="eyebrow">WORK ORDER COST</span>
              <h2>{selected.vehicle_label} · {selected.plate_number}</h2>
              <p>담당 {selected.mechanic_name ?? "미배정"} · 완료 {localTime(selected.completed_at)}</p>
              <dl className="finance-detail">
                <div><dt>매출</dt><dd>{won(selected.revenue)}</dd></div>
                <div><dt>부품 원가(확인분)</dt><dd>{won(selected.parts_cost_known)}</dd></div>
                <div><dt>UNKNOWN 부품 수량</dt><dd>{String(selected.unknown_parts_quantity)}</dd></div>
                <div><dt>인건비</dt><dd>{money(selected.labor_cost)}</dd></div>
                <div><dt>작업 시간</dt><dd>{selected.labor_minutes_snapshot === null ? "미확정" : `${selected.labor_minutes_snapshot}분`}</dd></div>
                <div><dt>총원가</dt><dd>{money(selected.total_cost)}</dd></div>
                <div><dt>기여이익</dt><dd>{money(selected.contribution_margin)}</dd></div>
                <div><dt>수납 / 미수</dt><dd>{won(selected.paid_amount)} / {won(selected.outstanding_amount)}</dd></div>
              </dl>
              {selected.has_unknown_cost && <p className="finance-warning">확인되지 않은 {selected.has_unknown_parts_cost ? "부품 원가" : ""}{selected.has_unknown_parts_cost && selected.has_unknown_labor_cost ? "와 " : ""}{selected.has_unknown_labor_cost ? "인건비 원가" : ""}가 있습니다.</p>}
            </>
          ) : <p>작업을 선택하면 원가 근거를 확인할 수 있습니다.</p>}
        </section>
      </div>
    </>
  );
}
