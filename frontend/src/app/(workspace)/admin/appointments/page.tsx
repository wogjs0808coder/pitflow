"use client";
import { useEffect, useState } from "react";
import { CalendarDays, ChevronLeft, ChevronRight, RefreshCw } from "lucide-react";
import { useAuth } from "@/components/auth-provider";
import { BookingCalendarSettings } from "@/components/booking-calendar-settings";
import { AppointmentCard } from "@/components/appointment-card";
import { api, errorText } from "@/lib/api";
import { Appointment, AppointmentStatus, WorkBay, BookingPolicy, addDays, seoulToday, dayLabel, timeLabel, statusLabel, actionLabel } from "@/lib/appointments";

export default function AdminAppointmentsPage() {
  const { user } = useAuth();
  const [date, setDate] = useState(seoulToday);
  const [data, setData] = useState<{ appointments: Appointment[]; bays: WorkBay[]; policy: BookingPolicy } | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    if (user?.role !== "ADMIN") return;
    const controller = new AbortController();
    const options = { signal: controller.signal };
    setLoading(true); setError("");
    Promise.all([
      api<Appointment[]>(`/api/admin/appointments?from=${date}&to=${date}`, options),
      api<WorkBay[]>("/api/admin/work-bays", options),
      api<BookingPolicy>("/api/appointments/policy", options),
    ]).then(([appointments, bays, policy]) => {
      if (controller.signal.aborted) return;
      setData({ appointments, bays, policy });
      setSelectedId(id => appointments.some(a => a.id === id) ? id : appointments[0]?.id ?? null);
    }).catch(e => { if (!controller.signal.aborted) setError(errorText(e)); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [date, user?.role, revision]);
  if (user?.role !== "ADMIN") return <div className="error" role="alert">관리자만 예약 캘린더를 확인할 수 있습니다.</div>;
  const selected = data?.appointments.find(a => a.id === selectedId);
  const active = data?.appointments.filter(a => a.status !== "CANCELLED" && a.status !== "NO_SHOW") ?? [];
  const inactive = data?.appointments.filter(a => a.status === "CANCELLED" || a.status === "NO_SHOW") ?? [];
  // Preserve a column for historical reservations even if a bay has since been disabled.
  const bays = [...(data?.bays ?? [])];
  data?.appointments.forEach(a => { if (!bays.some(b => b.id === a.workBayId)) bays.push({ id: a.workBayId, name: a.workBayName }); });
  const toMinute = (time: string) => Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5));
  const open = data ? Math.min(toMinute(data.policy.opensAt), ...active.map(a => toMinute(timeLabel(a.startsAt)))) : 540;
  const close = data ? Math.max(toMinute(data.policy.closesAt), ...active.map(a => toMinute(timeLabel(a.endsAt)))) : 1080;
  const times = Array.from({ length: (close - open) / 30 }, (_, i) => `${String(Math.floor((open + i * 30) / 60)).padStart(2, "0")}:${String((open + i * 30) % 60).padStart(2, "0")}`);
  function moveDate(next: string) { if (next) { setDate(next); setNotice(""); } }
  async function change(status: AppointmentStatus) {
    if (!selected || busy) return;
    if (!window.confirm(`${selected.customerName}님의 예약을 ‘${statusLabel[status]}’ 상태로 변경하시겠습니까?`)) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await api(`/api/admin/appointments/${selected.id}/status`, { method: "PATCH", body: JSON.stringify({ status }) });
      setNotice(`‘${statusLabel[status]}’으로 변경했습니다.`); setRevision(n => n + 1);
    } catch (e) { setError(errorText(e)); }
    finally { setBusy(false); }
  }
  return <>
    <div className="page-heading"><div><span className="eyebrow">SERVICE SCHEDULE</span><h1>예약 캘린더</h1><p>작업 공간별 일정을 확인하고 예약 상태를 관리하세요.</p></div><CalendarDays size={32} /></div>
    <BookingCalendarSettings onSaved={() => { setRevision(n => n + 1); setNotice("휴무 설정을 저장했습니다."); }} />
    <div className="booking-toolbar">
      <div className="calendar-date-controls"><button className="button secondary" aria-label="이전 날짜" disabled={busy} onClick={() => moveDate(addDays(date, -1))}><ChevronLeft size={18} /></button><label>조회 날짜<input type="date" value={date} disabled={busy} onChange={e => moveDate(e.target.value)} /></label><button className="button secondary" aria-label="다음 날짜" disabled={busy} onClick={() => moveDate(addDays(date, 1))}><ChevronRight size={18} /></button></div>
      <div className="calendar-date-controls"><button className="button secondary" disabled={busy} onClick={() => moveDate(seoulToday())}>오늘</button><button className="button secondary" disabled={loading || busy} onClick={() => setRevision(n => n + 1)}><RefreshCw size={16} />새로고침</button></div>
    </div>
    {error && <div className="error" role="alert">{error}</div>}{notice && <div className="notice" role="status">{notice}</div>}
    {loading ? <p role="status">일정을 불러오는 중입니다…</p> : data && <>
      <div className="calendar-metrics"><div><span>전체 예약</span><strong>{data.appointments.length}<small>건</small></strong></div><div><span>확인 대기</span><strong>{data.appointments.filter(a => a.status === "PENDING").length}<small>건</small></strong></div><div><span>예약 확정</span><strong>{data.appointments.filter(a => a.status === "CONFIRMED").length}<small>건</small></strong></div><div><span>방문 완료</span><strong>{data.appointments.filter(a => a.status === "VISITED").length}<small>건</small></strong></div></div>
      <div className="calendar-layout">
        <section className="calendar-panel" aria-label="작업 공간별 예약 시간표"><div className="section-heading"><h2>{dayLabel(date)}</h2><span className="booking-caption">한국 시간 · 30분 간격</span></div>
          {!active.length && <p className="muted">이 날은 진행할 예약이 없습니다.</p>}
          <div className="calendar-scroll" tabIndex={0} aria-label="시간표 스크롤 영역">
            <div className="bay-calendar" style={{ gridTemplateColumns: `64px repeat(${Math.max(bays.length, 1)}, minmax(145px, 1fr))`, gridTemplateRows: `42px repeat(${times.length}, 64px)` }}>
              <div className="calendar-column-head" style={{ gridColumn: 1, gridRow: 1 }}>시간</div>
              {bays.map((b, index) => <div className="calendar-column-head" style={{ gridColumn: index + 2, gridRow: 1 }} key={b.id}>{b.name}</div>)}
              {times.map((t, index) => <div className="calendar-time" key={t} style={{ gridColumn: 1, gridRow: index + 2 }}>{t}</div>)}
              {bays.flatMap((b, column) => times.map((t, row) => <div className="calendar-cell" aria-hidden="true" key={`${b.id}-${t}`} style={{ gridColumn: column + 2, gridRow: row + 2 }} />))}
              {active.map(a => <button key={a.id} className={`calendar-booking status-${a.status.toLowerCase()} ${a.id === selectedId ? "selected-booking" : ""}`} disabled={busy || !!error} aria-pressed={a.id === selectedId} aria-label={`${timeLabel(a.startsAt)} ${a.customerName} ${a.plateNumber} ${statusLabel[a.status]}`} onClick={() => setSelectedId(a.id)} style={{ gridColumn: bays.findIndex(b => b.id === a.workBayId) + 2, gridRow: `${(toMinute(timeLabel(a.startsAt)) - open) / 30 + 2} / span ${a.durationMinutes / 30}` }}><strong>{timeLabel(a.startsAt)} {a.customerName}</strong><span>{a.plateNumber} · {statusLabel[a.status]}</span></button>)}
            </div>
          </div>
          {!!inactive.length && <div className="calendar-history"><h3>취소·미방문</h3>{inactive.map(a => <button className="calendar-history-row" disabled={busy} key={a.id} onClick={() => setSelectedId(a.id)}><span>{timeLabel(a.startsAt)} · {a.customerName}</span><span className={`booking-status status-${a.status.toLowerCase()}`}>{statusLabel[a.status]}</span></button>)}</div>}
        </section>
        <aside className="calendar-detail" aria-label="선택한 예약 상세">
          {selected ? <><h2>예약 상세</h2><p><strong>{selected.customerName}</strong><br /><span className="muted customer-email">{selected.customerEmail}</span></p><AppointmentCard appointment={selected}>
            <div className="booking-state-actions">{selected.allowedStatuses.map(s => <button key={s} className={`button ${s === "CANCELLED" ? "secondary danger" : "primary"}`} disabled={busy || !!error} onClick={() => void change(s)}>{busy ? "처리 중…" : actionLabel[s]}</button>)}</div>
            <p className="booking-caption">확정 예약은 예약일 전에도 방문 처리할 수 있습니다. 실제 도착한 고객만 처리하세요. 예약 종료 이후에는 미방문 처리가 가능합니다.</p>
          </AppointmentCard></> : <div className="empty-state"><h2>선택한 예약이 없습니다</h2><p>시간표의 예약을 선택하면 상세 내용이 표시됩니다.</p></div>}
        </aside>
      </div>
    </>}
  </>;
}
