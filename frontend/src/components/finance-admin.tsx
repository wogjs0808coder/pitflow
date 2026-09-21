"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "@/components/auth-provider";
import { api, errorText, won } from "@/lib/api";
import { FinanceEntry, FinanceSettings, FinanceSummary, FinanceWork, TreasuryAccountType, TreasurySummary } from "@/lib/finance";
import { localTime } from "@/lib/work";
import { useWorkCommand } from "./work-command";

const seoulDate = () => {
  const parts = new Intl.DateTimeFormat("en", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${value.year}-${value.month}-${value.day}`;
};
const initialTo = seoulDate();
const initialFrom = `${initialTo.slice(0, 8)}01`;
const money = (value: number | null) => (value === null ? "미확정" : won(value));
const percent = (value: number | null) => value === null ? "미확정" : `${value.toLocaleString("ko-KR", { maximumFractionDigits: 1 })}%`;
const ENTRY_CATEGORIES = [
  ["RENT", "임차료"], ["UTILITIES", "공과금"], ["INSURANCE", "보험료"],
  ["SOFTWARE", "소프트웨어"], ["SHOP_SUPPLIES", "소모품"],
  ["EQUIPMENT_MAINTENANCE", "장비 유지보수"], ["CARD_FEES", "카드 수수료"],
  ["DEPRECIATION", "감가상각"], ["INTEREST", "이자 비용"], ["TAX", "세금"],
  ["OTHER_OPERATING", "기타 운영비"], ["OTHER_INCOME", "기타 수익"],
] as const;
const categoryName = (category: string) => ENTRY_CATEGORIES.find(([value]) => value === category)?.[1] ?? category;
const TREASURY_ACCOUNTS: [TreasuryAccountType, string][] = [
  ["OPERATING", "운영자금"],
  ["DEPOSIT", "은행예치"],
  ["INVESTMENT", "투자자산"],
];

export function FinanceAdmin() {
  const { user } = useAuth();
  const [from, setFrom] = useState(initialFrom);
  const [to, setTo] = useState(initialTo);
  const [period, setPeriod] = useState({ from: initialFrom, to: initialTo });
  const [summary, setSummary] = useState<FinanceSummary | null>(null);
  const [works, setWorks] = useState<FinanceWork[]>([]);
  const [settings, setSettings] = useState<FinanceSettings | null>(null);
  const [entries, setEntries] = useState<FinanceEntry[]>([]);
  const [treasury, setTreasury] = useState<TreasurySummary | null>(null);
  const [selected, setSelected] = useState<FinanceWork | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [partsCost, setPartsCost] = useState("");
  const [laborCost, setLaborCost] = useState("");
  const [reason, setReason] = useState("");

  const load = useCallback(async () => {
    if (user?.role !== "ADMIN") return;
    setLoading(true);
    setError("");
    try {
      const query = `from=${period.from}&to=${period.to}`;
      const [nextSummary, nextWorks, nextSettings, nextEntries, nextTreasury] = await Promise.all([
        api<FinanceSummary>(`/api/admin/finance/summary?${query}`),
        api<FinanceWork[]>(`/api/admin/finance/work-orders?${query}`),
        api<FinanceSettings>("/api/admin/finance/settings"),
        api<FinanceEntry[]>(`/api/admin/finance/entries?${query}`),
        api<TreasurySummary>("/api/admin/finance/treasury"),
      ]);
      setSummary(nextSummary);
      setWorks(nextWorks);
      setSettings(nextSettings);
      setEntries(nextEntries);
      setTreasury(nextTreasury);
      setSelected((current) => nextWorks.find((work) => work.id === current?.id) ?? null);
    } catch (reason) {
      setError(errorText(reason));
    } finally {
      setLoading(false);
    }
  }, [period, user?.role]);
  const command = useWorkCommand(load);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    setPartsCost("");
    setLaborCost("");
    setReason("");
  }, [selected?.id, selected?.manual_unresolved_parts_cost, selected?.manual_labor_cost]);

  if (user?.role !== "ADMIN") return <p role="alert">관리자만 이용할 수 있습니다.</p>;
  const submit = (event: FormEvent) => {
    event.preventDefault();
    setPeriod({ from, to });
  };
  const submitResolution = (event: FormEvent) => {
    event.preventDefault();
    if (!selected) return;
    const body: {
      unresolvedPartsCost?: number;
      laborCost?: number;
      reason: string;
    } = { reason };
    if (partsCost !== "") body.unresolvedPartsCost = Number(partsCost);
    if (laborCost !== "") body.laborCost = Number(laborCost);
    void command.run(
      `/api/admin/finance/work-orders/${selected.id}/cost-resolution`,
      body,
    );
  };
  const submitSettings = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    void command.run("/api/admin/finance/settings", {
      defaultMonthlyBaseSalary: Number(data.get("defaultMonthlyBaseSalary")),
      defaultMonthlyStandardHours: Number(data.get("defaultMonthlyStandardHours")),
      targetPayrollRatio: Number(data.get("targetPayrollRatio")),
    }, "PATCH");
  };
  const submitEntry = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    void command.run("/api/admin/finance/entries", {
      entryDate: data.get("entryDate"),
      category: data.get("category"),
      amount: Number(data.get("amount")),
      description: data.get("description"),
      affectsTreasury: data.get("affectsTreasury") === "on",
    });
  };
  const submitPayroll = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    void command.run("/api/admin/finance/treasury/payroll-payment", {
      amount: Number(data.get("amount")),
      paymentDate: data.get("paymentDate"),
      reason: data.get("reason"),
    });
  };
  const reverseEntry = (entry: FinanceEntry) => {
    const reason = window.prompt("역분개 사유를 입력해 주세요.");
    if (!reason?.trim()) return;
    void command.run(`/api/admin/finance/entries/${entry.id}/reversal`, { reason: reason.trim() });
  };
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">FINANCE & COST</span>
          <h1>재무·원가 분석</h1>
          <p>발행 명세 매출과 완료 시점 인건비, 실제 FIFO 부품원가를 함께 확인합니다.</p>
        </div>
        {treasury && (
          <section className="treasury-card" aria-label="현재 회사자산">
            <span className="eyebrow">TREASURY</span>
            <span>{treasury.managed_assets_fully_known ? "현재 회사자산" : "확인된 회사자산"}</span>
            <strong className="treasury-total">{won(treasury.managed_assets_known_total)}</strong>
            <div className="treasury-managed-summary">
              <span>금융자산 <strong>{won(treasury.financial_assets_total)}</strong></span>
              <span>
                확정 재고자산
                <strong>{won(treasury.inventory.known_value)}</strong>
                <Link className="inline-link" href="/admin/parts">재고자산 상세 보기 →</Link>
              </span>
              <span>미수채권 <strong>{won(treasury.receivables)}</strong></span>
            </div>
            {!treasury.managed_assets_fully_known && (
              <p className="finance-warning">
                원가 미확정 재고 {treasury.inventory.unknown_part_count}개 품목 / {treasury.inventory.unknown_quantity.toLocaleString("ko-KR")}개
              </p>
            )}
            <div className="treasury-accounts">
              {TREASURY_ACCOUNTS.map(([type, label]) => {
                const account = treasury.accounts[type];
                return (
                  <div key={type}>
                    <span>{label}</span>
                    <strong>{won(account.balance)}</strong>
                    <small>현재 {percent(account.current_ratio)} / 목표 {percent(account.target_ratio)}</small>
                  </div>
                );
              })}
            </div>
            <button
              className="button secondary"
              disabled={command.blocked || !treasury.can_rebalance}
              onClick={() => void command.run("/api/admin/finance/treasury/rebalance", {})}
            >
              목표 비중으로 재조정
            </button>
            <div className="treasury-simulation">
              <span>예금 연 {percent(treasury.simulation.annual_deposit_rate)}</span>
              <span>최근 정산 {treasury.simulation.last_settlement_date ?? "정산 전"}</span>
              {treasury.simulation.last_deposit_interest !== null && <span>최근 예금 이자 +{won(treasury.simulation.last_deposit_interest)}</span>}
              {treasury.simulation.last_investment_return_rate !== null && <span>최근 투자 {percent(treasury.simulation.last_investment_return_rate)} / {won(treasury.simulation.last_investment_return_amount ?? 0)}</span>}
            </div>
            <details className="treasury-payroll">
              <summary>급여 실제 지급</summary>
              <form onSubmit={submitPayroll}>
                <label>지급일<input name="paymentDate" type="date" required defaultValue={initialTo} /></label>
                <label>지급액<input name="amount" type="number" min="1" step="1" required /></label>
                <label>사유<input name="reason" maxLength={500} required /></label>
                <button className="button secondary" disabled={command.blocked}>운영자금에서 지급</button>
              </form>
              <p className="field-help">기간 급여 추정치와 별개인 실제 현금 지급입니다.</p>
            </details>
          </section>
        )}
      </div>
      {error && <div className="error" role="alert">{error}</div>}
      <form className="billing-period" onSubmit={submit}>
        <label>시작일<input type="date" value={from} onChange={(event) => setFrom(event.target.value)} required /></label>
        <label>종료일<input type="date" value={to} onChange={(event) => setTo(event.target.value)} required /></label>
        <button className="button primary" disabled={loading}>조회</button>
      </form>
      {summary && (
        <>
          <section className="work-panel">
            <div className="billing-metrics finance-metrics finance-kpis">
              <div><span>매출</span><strong>{won(summary.revenue)}</strong></div>
              <div><span>수납</span><strong>{won(summary.paid_amount)}</strong></div>
              <div><span>미수</span><strong>{won(summary.outstanding_amount)}</strong></div>
              <div><span>확인·확정된 부품 원가</span><strong>{won(summary.parts_cost)}</strong></div>
              <div><span>기간 급여</span><strong>{money(summary.period_payroll_expense)}</strong></div>
              <div><span>영업이익</span><strong>{money(summary.operating_profit)}</strong></div>
              <div><span>매출총이익</span><strong>{money(summary.gross_profit)}</strong></div>
              <div><span>작업별 기여이익</span><strong>{money(summary.contribution_margin)}</strong></div>
              <div><span>순이익</span><strong>{money(summary.net_profit)}</strong></div>
              <div><span>수납률</span><strong>{percent(summary.collection_rate)}</strong></div>
              <div><span>급여 비율 / 목표</span><strong>{percent(summary.payroll_ratio)} / {percent(summary.target_payroll_ratio)}</strong></div>
              <div><span>평균 정비 매출</span><strong>{money(summary.average_repair_order)}</strong></div>
            </div>
            {summary.has_unknown_cost && <p className="finance-warning">미확정 부품 또는 작업 원가가 있습니다. 해당 원가에 의존하는 지표는 미확정으로 표시됩니다.</p>}
            {summary.payroll_unknown && <p className="finance-warning">월급이 설정되지 않은 활성 정비사가 있어 기간 급여와 손익은 미확정입니다.</p>}
            <p className="field-help">작업별 기여이익은 완료 작업의 스냅샷 원가 기준이며, 영업이익은 기간 급여와 운영비를 반영한 관리 손익입니다.</p>
          </section>

          <section className="work-panel finance-management-section">
            <div>
              <span className="eyebrow">LABOR MANAGEMENT</span>
              <h2>기간 급여·배부 분석</h2>
            </div>
            <div className="finance-management-summary">
              <div><span>기간 급여</span><strong>{money(summary.period_payroll_expense)}</strong></div>
              <div><span>작업 배부 인건비</span><strong>{won(summary.allocated_labor_cost)}</strong></div>
              <div><span>배부 차이</span><strong>{money(summary.labor_allocation_variance)}</strong></div>
              <div><span>작업시간 활용률</span><strong>{percent(summary.labor_utilization_rate)}</strong></div>
              <div><span>정비사당 매출</span><strong>{money(summary.revenue_per_mechanic)}</strong></div>
            </div>
            <div className="finance-table-wrap">
              <table className="finance-table">
                <thead><tr><th>정비사</th><th>월급 / 기준시간</th><th>계산 시간당 원가</th><th>배부 작업시간</th><th>배부 원가</th><th>기간 급여</th></tr></thead>
                <tbody>
                  {summary.mechanics.map((mechanic) => (
                    <tr key={mechanic.id}>
                      <td><strong>{mechanic.name}</strong><span>{mechanic.code}</span></td>
                      <td>{mechanic.monthly_base_salary === null ? "미확정" : `${won(mechanic.monthly_base_salary)} / ${mechanic.monthly_standard_hours}시간`}</td>
                      <td>{money(mechanic.derived_hourly_cost)}</td>
                      <td>{mechanic.allocated_minutes === null ? "미확정" : `${(mechanic.allocated_minutes / 60).toLocaleString("ko-KR", { maximumFractionDigits: 1 })}시간`}</td>
                      <td>{won(mechanic.allocated_labor_cost)}</td>
                      <td>{money(mechanic.period_payroll_expense)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <div className="finance-management-grid">
            <section className="work-panel finance-management-section">
              <div><span className="eyebrow">PLANNING REFERENCE</span><h2>재무 기준값</h2></div>
              {settings && (
                <form className="finance-settings-form" onSubmit={submitSettings}>
                  <label>참고 월급<input name="defaultMonthlyBaseSalary" type="number" min="0" step="1" required defaultValue={settings.default_monthly_base_salary} /></label>
                  <label>참고 월 근로시간<input name="defaultMonthlyStandardHours" type="number" min="0.01" step="0.01" required defaultValue={settings.default_monthly_standard_hours} /></label>
                  <label>목표 급여 비율 (%)<input name="targetPayrollRatio" type="number" min="0" max="100" step="0.01" required defaultValue={settings.target_payroll_ratio} /></label>
                  <p className="field-help">계획 참고값입니다. 정비사별 실제 급여는 정비사 관리에서 별도로 저장해야 합니다.</p>
                  <button className="button secondary" disabled={command.blocked}>기준값 저장</button>
                </form>
              )}
            </section>
            <section className="work-panel finance-management-section">
              <div><span className="eyebrow">OPERATING LEDGER</span><h2>운영비·기타 손익 입력</h2></div>
              <form className="finance-entry-form" onSubmit={submitEntry}>
                <label>일자<input name="entryDate" type="date" required defaultValue={initialTo} /></label>
                <label>구분<select name="category" required>{ENTRY_CATEGORIES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
                <label>금액<input name="amount" type="number" min="0" step="1" required /></label>
                <label className="finance-entry-description">설명<input name="description" maxLength={300} required /></label>
                <label className="finance-cash-toggle"><input name="affectsTreasury" type="checkbox" />운영자금에 반영 (실제 지급/수입)</label>
                <p className="field-help">감가상각은 선택해도 운영자금에 반영되지 않습니다.</p>
                <button className="button primary" disabled={command.blocked}>전표 추가</button>
              </form>
            </section>
          </div>

          <section className="work-panel finance-management-section">
            <div><span className="eyebrow">PERIOD ENTRIES</span><h2>기간 전표</h2></div>
            <div className="finance-category-totals">
              {Object.entries(summary.expense_by_category).filter(([, value]) => value !== 0).map(([category, value]) => (
                <span key={category}>{categoryName(category)} <strong>{won(value)}</strong></span>
              ))}
            </div>
            <div className="finance-table-wrap">
              <table className="finance-table">
                <thead><tr><th>일자</th><th>구분</th><th>설명</th><th>금액</th><th>입력자</th><th>처리</th></tr></thead>
                <tbody>
                  {entries.map((entry) => (
                    <tr key={entry.id} className={entry.entry_kind === "REVERSAL" ? "finance-reversal" : ""}>
                      <td>{entry.entry_date}</td><td>{categoryName(entry.category)}{entry.affects_treasury ? " · 현금" : ""}</td><td>{entry.description}</td>
                      <td>{entry.entry_kind === "REVERSAL" ? "-" : ""}{won(entry.amount)}</td><td>{entry.created_by_name}</td>
                      <td>{entry.entry_kind === "ENTRY" && !entry.reversed ? <button className="button secondary" type="button" disabled={command.blocked} onClick={() => reverseEntry(entry)}>역분개</button> : entry.reversed ? "역분개 완료" : "역분개"}</td>
                    </tr>
                  ))}
                  {!entries.length && <tr><td colSpan={6}>기간 내 전표가 없습니다.</td></tr>}
                </tbody>
              </table>
            </div>
          </section>
        </>
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
                <div><dt>부품 원가</dt><dd>{won(selected.parts_cost)}</dd></div>
                <div><dt>자동 확인 부품 원가</dt><dd>{won(selected.automatic_parts_cost_known)}</dd></div>
                {selected.parts_cost_manually_resolved && <div><dt>수동 확정 부품 원가</dt><dd>{won(selected.manual_unresolved_parts_cost ?? 0)} · 수동 확정</dd></div>}
                <div><dt>UNKNOWN 부품 수량</dt><dd>{String(selected.unknown_parts_quantity)}</dd></div>
                <div><dt>인건비</dt><dd>{money(selected.labor_cost)}</dd></div>
                {selected.labor_cost_manually_resolved && <div><dt>수동 확정 인건비</dt><dd>{won(selected.manual_labor_cost ?? 0)} · 수동 확정</dd></div>}
                <div><dt>작업 시간</dt><dd>{selected.labor_minutes_snapshot === null ? "미확정" : `${selected.labor_minutes_snapshot}분`}</dd></div>
                <div><dt>완료 시점 시간당 원가</dt><dd>{money(selected.labor_hourly_cost_snapshot)}</dd></div>
                <div><dt>현재 월급 기준</dt><dd>{selected.current_monthly_base_salary === null ? "미설정" : `${won(selected.current_monthly_base_salary)} / ${selected.current_monthly_standard_hours}시간`}</dd></div>
                <div><dt>현재 계산 시간당 원가</dt><dd>{money(selected.current_derived_hourly_cost)}</dd></div>
                <div><dt>총원가</dt><dd>{money(selected.total_cost)}</dd></div>
                <div><dt>기여이익</dt><dd>{money(selected.contribution_margin)}</dd></div>
                <div><dt>수납 / 미수</dt><dd>{won(selected.paid_amount)} / {won(selected.outstanding_amount)}</dd></div>
              </dl>
              {selected.has_unknown_cost && <p className="finance-warning">확인되지 않은 {selected.has_unknown_parts_cost ? "부품 원가" : ""}{selected.has_unknown_parts_cost && selected.has_unknown_labor_cost ? "와 " : ""}{selected.has_unknown_labor_cost ? "인건비 원가" : ""}가 있습니다.</p>}
              {(selected.unknown_parts_quantity > 0 || selected.automatic_labor_cost === null) && (
                <form className="finance-resolution" onSubmit={submitResolution}>
                  <div>
                    <span className="eyebrow">MANUAL COST RESOLUTION</span>
                    <h3>미확정 원가 수동 확정 및 정정</h3>
                    <p>자동 원가 기록은 변경하지 않습니다. 새 입력은 감사 이력에 정정 행으로 추가됩니다.</p>
                  </div>
                  {selected.unknown_parts_quantity > 0 && (
                    <label>
                      미확정 부품 원가
                      <input type="number" min="0" step="1" value={partsCost} onChange={(event) => setPartsCost(event.target.value)} placeholder={selected.manual_unresolved_parts_cost === null ? "실제 확인 금액" : `현재 ${selected.manual_unresolved_parts_cost}`} />
                      <span className="field-help">자동 확인분 {won(selected.automatic_parts_cost_known)}에 더해집니다.</span>
                    </label>
                  )}
                  {selected.automatic_labor_cost === null && (
                    <label>
                      인건비 원가
                      <input type="number" min="0" step="1" value={laborCost} onChange={(event) => setLaborCost(event.target.value)} placeholder={selected.manual_labor_cost === null ? "실제 확인 금액" : `현재 ${selected.manual_labor_cost}`} />
                    </label>
                  )}
                  <label>
                    정정 사유
                    <textarea maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} required placeholder="확인한 전표나 정비기록 근거를 입력해 주세요." />
                  </label>
                  <button className="button primary" disabled={command.blocked || !reason.trim() || (partsCost === "" && laborCost === "")}>원가 확정</button>
                  {selected.cost_resolution_reason && (
                    <p className="field-help">최근 정정: {selected.cost_resolution_reason} · {selected.cost_resolution_by ?? "관리자"} · {selected.cost_resolution_at ? localTime(selected.cost_resolution_at) : ""}</p>
                  )}
                </form>
              )}
            </>
          ) : <p>작업을 선택하면 원가 근거를 확인할 수 있습니다.</p>}
        </section>
      </div>
      {command.feedback}
    </>
  );
}
