"use client";
import Link from "next/link";
import { FormEvent, useEffect, useRef, useState } from "react";
import { ArrowLeft, CalendarCheck, ChevronLeft, ChevronRight, Clock3 } from "lucide-react";
import { api, ApiError, errorText, ServiceItem, Vehicle, won } from "@/lib/api";
import { Appointment, Availability, BookingPolicy, AvailableSlot, dayLabel, monthRange, timeLabel } from "@/lib/appointments";
import { AppointmentCard } from "@/components/appointment-card";

type Setup = { cars: Vehicle[]; services: ServiceItem[]; policy: BookingPolicy };
type QuoteSelection = { serviceId: string; quantity: number };
type QuotePart = { partId: string; name: string; unit: string; totalQuantity: number | null; unitPrice: number; amount: number; chargePolicy: "STANDARD" | "COMPLIMENTARY" };
type QuoteItem = { serviceId: string; name: string; quantity: number; laborUnitPrice: number; laborAmount: number; durationMinutes: number; parts: QuotePart[]; partsAmount: number; totalAmount: number };
type AppointmentQuote = { items: QuoteItem[]; totalLaborPrice: number; totalPartsPrice: number; totalPrice: number; durationMinutes: number; fingerprint: string };

const TIRE_SERVICE_ID = "22222222-2222-4222-8222-222222222222";

export default function NewAppointmentPage() {
  const [setup, setSetup] = useState<Setup | null>(null);
  const [initError, setInitError] = useState("");
  const [vehicle, setVehicle] = useState("");
  const [serviceIds, setServiceIds] = useState<string[]>([]);
  const [quantities, setQuantities] = useState<Record<string, number>>({});
  const [date, setDate] = useState("");
  const [visibleMonth, setVisibleMonth] = useState("");
  const [notes, setNotes] = useState("");
  const [quote, setQuote] = useState<AppointmentQuote | null>(null);
  const [availability, setAvailability] = useState<Availability | null>(null);
  const [selected, setSelected] = useState<AvailableSlot | null>(null);
  const [error, setError] = useState("");
  const [feedback, setFeedback] = useState("");
  const [uncertain, setUncertain] = useState(false);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const submitting = useRef(false);
  const [created, setCreated] = useState<Appointment | null>(null);
  const [revision, setRevision] = useState(0);
  const [setupRevision, setSetupRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setInitError("");
    const options = { signal: controller.signal };
    Promise.all([api<Vehicle[]>("/api/vehicles", options), api<ServiceItem[]>("/api/services", options), api<BookingPolicy>("/api/appointments/policy", options)])
      .then(([cars, services, policy]) => {
        if (controller.signal.aborted) return;
        setSetup({ cars, services, policy }); setVehicle(cars[0]?.id ?? ""); setDate(policy.earliestDate); setVisibleMonth(policy.earliestDate.slice(0, 7));
      }).catch(e => { if (!controller.signal.aborted) setInitError(errorText(e)); });
    return () => controller.abort();
  }, [setupRevision]);

  const selections: QuoteSelection[] = serviceIds.map(serviceId => ({ serviceId, quantity: quantities[serviceId] ?? 1 }));
  const selectionKey = selections.map(item => `${item.serviceId}:${item.quantity}`).join(",");
  useEffect(() => {
    const controller = new AbortController();
    setQuote(null); setAvailability(null); setSelected(null); setError("");
    if (!vehicle || !date || !selectionKey || created) { setLoading(false); return () => controller.abort(); }
    setLoading(true);
    const quoteBody = JSON.stringify({ items: selections });
    Promise.all([
      api<AppointmentQuote>("/api/appointments/quote", { method: "POST", body: quoteBody, signal: controller.signal }),
      api<Availability>("/api/appointments/availability/quoted", { method: "POST", body: JSON.stringify({ vehicleId: vehicle, date, items: selections }), signal: controller.signal }),
    ])
      .then(([nextQuote, nextAvailability]) => {
        if (controller.signal.aborted) return;
        setQuote(nextQuote); setAvailability(nextAvailability);
      })
      .catch(e => { if (!controller.signal.aborted) setError(errorText(e)); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  // selectionKey is the stable serialized representation used to invalidate quote/availability together.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [vehicle, date, selectionKey, revision, created]);

  function invalidate() { setQuote(null); setAvailability(null); setSelected(null); }
  function toggleService(id: string) {
    invalidate();
    setServiceIds(ids => ids.includes(id) ? ids.filter(value => value !== id) : [...ids, id]);
  }
  function setQuantity(id: string, quantity: number) {
    invalidate(); setQuantities(current => ({ ...current, [id]: quantity }));
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!selected || !availability || !quote || submitting.current || uncertain) return;
    submitting.current = true; setBusy(true); setError(""); setFeedback("");
    try {
      const result = await api<Appointment>("/api/appointments", {
        method: "POST",
        body: JSON.stringify({ vehicleId: vehicle, workBayId: selected.availableBays[0].id, items: selections, quoteFingerprint: quote.fingerprint, startsAt: selected.startsAt, notes }),
      });
      setCreated(result);
    } catch (e) {
      if (!(e instanceof ApiError) || e.status === 0 || e.status >= 500) {
        setUncertain(true);
      } else if (e.status === 409) {
        invalidate(); setRevision(n => n + 1);
        setFeedback("가격·구성 또는 예약 가능 시간이 변경되었습니다. 최신 견적과 시간을 다시 확인해 주세요.");
      } else setError(errorText(e));
    } finally { submitting.current = false; setBusy(false); }
  }
  if (initError) return <div className="error" role="alert">{initError} <button className="text-button" onClick={() => setSetupRevision(n => n + 1)}>다시 시도</button></div>;
  if (!setup) return <p role="status">예약 정보를 준비하고 있습니다…</p>;
  if (created) return <>
    <div className="page-heading"><div><span className="eyebrow">BOOKING RECEIVED</span><h1>예약을 신청했습니다</h1><p>정비소에서 확인하면 ‘예약 확정’으로 변경됩니다.</p></div><CalendarCheck size={40} className="booking-success-icon" /></div>
    <div className="booking-confirmation"><AppointmentCard appointment={created} /><Link className="button primary full" href={`/appointments?month=${created.startsAt.slice(0, 7)}`}>내 예약 내역 보기</Link></div>
  </>;
  const selectedItems = setup.services.filter(s => serviceIds.includes(s.id));
  const selectedCar = setup.cars.find(c => c.id === vehicle);
  const fallbackLabor = selectedItems.reduce((sum, s) => sum + s.laborPrice * (quantities[s.id] ?? 1), 0);
  const fallbackDuration = selectedItems.reduce((sum, s) => sum + s.durationMinutes * (quantities[s.id] ?? 1), 0);
  const dayNames: Record<string, string> = { MONDAY: "월", TUESDAY: "화", WEDNESDAY: "수", THURSDAY: "목", FRIDAY: "금", SATURDAY: "토", SUNDAY: "일" };
  const firstWeekday = visibleMonth ? (new Date(`${visibleMonth}-01T12:00:00Z`).getUTCDay() + 6) % 7 : 0;
  const daysInMonth = visibleMonth ? Number(monthRange(visibleMonth).to.slice(8)) : 0;
  function moveMonth(offset: number) {
    const next = new Date(`${visibleMonth}-01T12:00:00Z`);
    next.setUTCMonth(next.getUTCMonth() + offset);
    setVisibleMonth(next.toISOString().slice(0, 7));
  }
  return <>
    <Link className="inline-link" href="/appointments"><ArrowLeft size={16} />내 예약 내역</Link>
    <div className="page-heading booking-page-heading"><div><span className="eyebrow">BOOK A SERVICE</span><h1>정비 예약하기</h1><p>차량과 정비 항목을 선택하면 예상 금액과 방문 가능한 시간이 표시됩니다.</p></div></div>
    {!setup.cars.length ? <div className="empty-state"><h2>먼저 차량을 등록해 주세요</h2><p>내 차량을 등록한 후 정비를 예약할 수 있습니다.</p><Link className="button primary" href="/vehicles">차량 등록하기</Link></div> : !setup.services.length ? <div className="empty-state"><h2>현재 예약 가능한 정비 항목이 없습니다</h2><p>정비소에서 항목을 준비 중입니다.</p></div> :
      <form className="booking-layout" onSubmit={submit}>
        <div className="booking-steps">
          <fieldset className="booking-panel" disabled={busy}><legend><span className="step-number">1</span>차량과 정비 항목</legend>
            <label className="booking-field">내 차량<select value={vehicle} onChange={e => { invalidate(); setVehicle(e.target.value); }} required>{setup.cars.map(c => <option value={c.id} key={c.id}>{c.manufacturer} {c.model} · {c.plateNumber}</option>)}</select></label>
            <div className="service-options">{setup.services.map(s => {
              const checked = serviceIds.includes(s.id);
              const quantity = quantities[s.id] ?? 1;
              return <label className={`service-option ${checked ? "is-selected" : ""}`} key={s.id}>
                <input type="checkbox" checked={checked} onChange={() => toggleService(s.id)} />
                <span><strong>{s.name}</strong><small>{s.durationMinutes}분 · 공임 {won(s.laborPrice)}</small>
                  {checked && s.id === TIRE_SERVICE_ID && <small>교체 수량 <select aria-label="타이어 교체 수량" value={quantity} onChange={e => setQuantity(s.id, Number(e.target.value))}>{[1, 2, 3, 4].map(value => <option key={value} value={value}>{value}본</option>)}</select></small>}
                </span>
              </label>;
            })}</div>
          </fieldset>
          <fieldset className="booking-panel" disabled={busy}><legend><span className="step-number">2</span>방문 날짜와 시간</legend>
            <p className="booking-caption">{setup.policy.opensAt.slice(0, 5)}~{setup.policy.closesAt.slice(0, 5)} · {setup.policy.closedDays.map(d => dayNames[d]).join("·")}요일 정기 휴무 · 날짜별 예외는 조회 결과에 반영됩니다 · 한국 시간</p>
            <div className="booking-date-picker" aria-label="방문 날짜 선택">
              <div className="booking-date-heading"><strong>{visibleMonth.slice(0, 4)}년 {Number(visibleMonth.slice(5))}월</strong>
                <div className="calendar-date-controls">
                  <button type="button" className="button secondary" aria-label="이전 달" disabled={`${visibleMonth}-01` <= `${setup.policy.earliestDate.slice(0, 7)}-01`} onClick={() => moveMonth(-1)}><ChevronLeft size={18} /></button>
                  <button type="button" className="button secondary" aria-label="다음 달" disabled={`${visibleMonth}-01` >= `${setup.policy.latestDate.slice(0, 7)}-01`} onClick={() => moveMonth(1)}><ChevronRight size={18} /></button>
                </div>
              </div>
              <p className="booking-caption">선택한 방문 날짜: {dayLabel(date)}</p>
              <div className="booking-date-grid">
                {"월화수목금토일".split("").map(day => <span className="booking-date-weekday" key={day}>{day}</span>)}
                {Array.from({ length: firstWeekday }, (_, index) => <span key={`blank-${index}`} aria-hidden="true" />)}
                {Array.from({ length: daysInMonth }, (_, index) => {
                  const day = `${visibleMonth}-${String(index + 1).padStart(2, "0")}`;
                  return <button type="button" key={day} className={`booking-date-day ${date === day ? "is-selected" : ""}`}
                    aria-label={day} aria-pressed={date === day} disabled={day < setup.policy.earliestDate || day > setup.policy.latestDate}
                    onClick={() => { invalidate(); setDate(day); }}>{index + 1}</button>;
                })}
              </div>
            </div>
            {feedback && <div className="notice" role="status">{feedback}</div>}
            {uncertain && <div className="notice" role="alert">
              <strong>예약 처리 결과를 확인하지 못했습니다.</strong> 실제로 등록되었을 수 있으므로 내 예약에서 확인한 뒤 다시 시도해 주세요.
              <div className="form-actions"><Link className="button secondary" href={`/appointments?month=${date.slice(0, 7)}`}>내 예약 확인</Link>
                <button className="button secondary" type="button" onClick={() => { setUncertain(false); invalidate(); setRevision(n => n + 1); }}>등록 여부 확인 후 다시 시도</button></div>
            </div>}
            {error && <div className="error" role="alert">{error} <Link className="inline-link" href="/appointments">내 예약 확인</Link> <button className="text-button" type="button" onClick={() => setRevision(n => n + 1)}>견적·시간 다시 조회</button></div>}
            {loading ? <p role="status">예상 금액과 예약 가능한 시간을 확인하고 있습니다…</p> : !serviceIds.length ? <p className="muted">먼저 정비 항목을 선택해 주세요.</p> : availability && quote && <>
              <p className="booking-caption">{availability.closed ? "선택한 날짜는 휴무일입니다." : !availability.slots.length ? "선택한 정비를 진행할 수 있는 시간이 없습니다. 다른 날짜를 선택해 주세요." : `예상 소요 시간 ${quote.durationMinutes}분 · 방문 시간을 선택하세요.`}</p>
              <div className="time-options" role="group" aria-label="예약 가능한 방문 시간">{availability.slots.map(slot => <button key={slot.startsAt} type="button" aria-pressed={selected?.startsAt === slot.startsAt} className={`time-option ${selected?.startsAt === slot.startsAt ? "is-selected" : ""}`} onClick={() => setSelected(slot)}>{timeLabel(slot.startsAt)}</button>)}</div>
            </>}
          </fieldset>
          <fieldset className="booking-panel" disabled={busy}><legend><span className="step-number">3</span>요청사항</legend><label className="booking-field">정비소에 전달할 내용 (선택)<textarea rows={3} maxLength={500} value={notes} onChange={e => setNotes(e.target.value)} placeholder="차량에서 느껴지는 증상이나 요청사항을 남겨 주세요." /></label></fieldset>
        </div>
        <aside className="booking-summary"><h2>예약 내용 확인</h2><p>{selectedCar?.manufacturer} {selectedCar?.model}<br /><strong>{selectedCar?.plateNumber}</strong></p>
          <ul className="booking-items">{quote ? quote.items.map(item => <li key={item.serviceId}><span>{item.name}{item.quantity > 1 ? ` × ${item.quantity}` : ""}</span><span>{won(item.totalAmount)}</span></li>) : selectedItems.map(s => <li key={s.id}><span>{s.name}</span><span>{won(s.laborPrice * (quantities[s.id] ?? 1))}</span></li>)}</ul>
          <div className="booking-total"><span>예상 총액</span><strong>{won(quote?.totalPrice ?? fallbackLabor)}</strong></div>
          <p className="booking-caption">공임 {won(quote?.totalLaborPrice ?? fallbackLabor)} · 예상 부품비 {quote ? won(quote.totalPartsPrice) : "계산 중"}</p>
          <p className="booking-caption"><Clock3 size={15} />예상 {quote?.durationMinutes ?? availability?.durationMinutes ?? fallbackDuration}분 · 실제 사용 부품 및 차량 규격에 따라 최종 금액이 달라질 수 있습니다.</p>
          <div className="selected-visit">{selected ? <><strong>{dayLabel(date)}</strong><span>{timeLabel(selected.startsAt)} – {timeLabel(selected.endsAt)}</span></> : <span>방문 시간을 선택해 주세요.</span>}</div>
          <p className="booking-caption">신청 후 정비소의 확인을 거쳐 확정됩니다. 시작 전까지 내 예약에서 취소할 수 있습니다.</p>
          <button className="button primary full" disabled={busy || loading || uncertain || !selected || !availability || !quote}>{busy ? "예약 신청 중…" : "예약 신청"}</button>
        </aside>
      </form>}
  </>;
}
