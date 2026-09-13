"use client";
import { useEffect, useState } from "react";
import { api, errorText } from "@/lib/api";
import { seoulToday } from "@/lib/appointments";
type Settings = { revision: number; closedDays: string[]; overrides: Record<string, boolean> };
const days = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];
export function BookingCalendarSettings({ onSaved }: { onSaved: () => void }) {
  const [value, setValue] = useState<Settings | null>(null);
  const [date, setDate] = useState(seoulToday);
  const [closed, setClosed] = useState(true);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function load() {
    setBusy(true); setError("");
    try { setValue(await api<Settings>("/api/admin/booking-calendar")); }
    catch (e) { setError(errorText(e)); }
    finally { setBusy(false); }
  }
  useEffect(() => { void load(); }, []);
  async function save() {
    if (!value || busy) return;
    setBusy(true); setError("");
    try { setValue(await api<Settings>("/api/admin/booking-calendar", { method: "PUT", body: JSON.stringify(value) })); onSaved(); }
    catch(e) { setError(errorText(e)); }
    finally { setBusy(false); }
  }
  return <details className="work-panel"><summary>휴무일 관리</summary>
    <p>정기 휴무와 날짜별 예외를 설정하세요. 기존 예약은 유지되며 새 예약 가능 시간에 적용됩니다. 기존 고객에게 일정 변경이 필요하면 별도로 안내해 주세요.</p>
    {error && <p className="error" role="alert">{error}</p>}
    <button className="button secondary" disabled={busy} onClick={() => void load()}>저장된 설정 다시 불러오기</button>
    {value && <fieldset disabled={busy}><legend>정기 휴무 요일</legend>
      <div className="calendar-date-controls">{days.map((day, i) => <label className="check-label" key={day}><input type="checkbox" checked={value.closedDays.includes(day)} onChange={e => setValue({ ...value, closedDays: e.target.checked ? [...value.closedDays, day] : value.closedDays.filter(d => d !== day) })} />{"월화수목금토일"[i]}요일</label>)}</div>
      <h3>날짜별 예외</h3><p>날짜별 설정이 정기 휴무보다 우선합니다. 삭제하면 해당 요일의 정기 설정을 따릅니다.</p>
      <label>날짜<input type="date" value={date} onChange={e => setDate(e.target.value)} /></label>
      <label>운영 여부<select value={String(closed)} onChange={e => setClosed(e.target.value === "true")}><option value="true">임시 휴무</option><option value="false">특별 영업</option></select></label>
      <button type="button" className="button secondary" disabled={!date} onClick={() => setValue({ ...value, overrides: { ...value.overrides, [date]: closed } })}>예외 목록에 추가</button>
      <ul>{Object.entries(value.overrides).sort(([a],[b]) => a.localeCompare(b)).map(([day, off]) => <li key={day}>{day} · {off ? "휴무" : "영업"} <button type="button" className="text-button" onClick={() => { const next = { ...value.overrides }; delete next[day]; setValue({ ...value, overrides: next }); }}>삭제</button></li>)}</ul>
      <button className="button primary" onClick={() => void save()}>휴무 설정 저장</button>
    </fieldset>}
  </details>;
}
