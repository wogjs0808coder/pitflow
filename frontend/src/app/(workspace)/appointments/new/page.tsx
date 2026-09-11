"use client";
import Link from "next/link";
import { FormEvent, useEffect, useRef, useState } from "react";
import { ArrowLeft, CalendarCheck, Clock3 } from "lucide-react";
import { api, ApiError, errorText, ServiceItem, Vehicle, won } from "@/lib/api";
import { Appointment, Availability, BookingPolicy, AvailableSlot, dayLabel, timeLabel } from "@/lib/appointments";
import { AppointmentCard } from "@/components/appointment-card";

type Setup = { cars: Vehicle[]; services: ServiceItem[]; policy: BookingPolicy };
export default function NewAppointmentPage() {
  const [setup, setSetup] = useState<Setup | null>(null);
  const [initError, setInitError] = useState("");
  const [vehicle, setVehicle] = useState("");
  const [serviceIds, setServiceIds] = useState<string[]>([]);
  const [date, setDate] = useState("");
  const [notes, setNotes] = useState("");
  const [availability, setAvailability] = useState<Availability | null>(null);
  const [selected, setSelected] = useState<AvailableSlot | null>(null);
  const [error, setError] = useState("");
  const [feedback, setFeedback] = useState("");
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
        setSetup({ cars, services, policy }); setVehicle(cars[0]?.id ?? ""); setDate(policy.earliestDate);
      }).catch(e => { if (!controller.signal.aborted) setInitError(errorText(e)); });
    return () => controller.abort();
  }, [setupRevision]);

  const selectionKey = serviceIds.join(",");
  useEffect(() => {
    const controller = new AbortController();
    setAvailability(null); setSelected(null); setError("");
    if (!vehicle || !date || !selectionKey || created) { setLoading(false); return () => controller.abort(); }
    setLoading(true);
    const params = new URLSearchParams({ vehicleId: vehicle, date, serviceIds: selectionKey });
    api<Availability>(`/api/appointments/availability?${params}`, { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setAvailability(result); })
      .catch(e => { if (!controller.signal.aborted) setError(errorText(e)); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [vehicle, date, selectionKey, revision, created]);

  // Invalidate the old quote immediately, before effects run, whenever an input changes.
  function invalidate() { setAvailability(null); setSelected(null); }
  function toggleService(id: string) {
    invalidate(); setServiceIds(ids => ids.includes(id) ? ids.filter(value => value !== id) : [...ids, id]);
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!selected || !availability || submitting.current) return;
    submitting.current = true; setBusy(true); setError("");
    try {
      const result = await api<Appointment>("/api/appointments", {
        method: "POST", body: JSON.stringify({ vehicleId: vehicle, workBayId: selected.availableBays[0].id, serviceIds, startsAt: selected.startsAt, notes }),
      });
      setCreated(result);
    } catch (e) {
      // A timeout may have occurred after commit; don't encourage a blind resubmit.
      if (!(e instanceof ApiError)) {
        invalidate(); setError("예약 결과를 확인하지 못했습니다. 다시 신청하기 전에 내 예약 내역에서 등록 여부를 확인해 주세요.");
      } else if (e.status === 409) {
        invalidate(); setRevision(n => n + 1);
        setFeedback("선택한 시간에 다른 예약이 있습니다. 새로 조회된 시간에서 다시 선택해 주세요.");
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
  const total = selectedItems.reduce((sum, s) => sum + s.laborPrice, 0);
  const duration = selectedItems.reduce((sum, s) => sum + s.durationMinutes, 0);
  const dayNames: Record<string, string> = { MONDAY: "월", TUESDAY: "화", WEDNESDAY: "수", THURSDAY: "목", FRIDAY: "금", SATURDAY: "토", SUNDAY: "일" };
  return <>
    <Link className="inline-link" href="/appointments"><ArrowLeft size={16} />내 예약 내역</Link>
    <div className="page-heading booking-page-heading"><div><span className="eyebrow">BOOK A SERVICE</span><h1>정비 예약하기</h1><p>차량과 정비 항목을 선택하면 방문 가능한 시간이 표시됩니다.</p></div></div>
    {!setup.cars.length ? <div className="empty-state"><h2>먼저 차량을 등록해 주세요</h2><p>내 차량을 등록한 후 정비를 예약할 수 있습니다.</p><Link className="button primary" href="/vehicles">차량 등록하기</Link></div> : !setup.services.length ? <div className="empty-state"><h2>현재 예약 가능한 정비 항목이 없습니다</h2><p>정비소에서 항목을 준비 중입니다.</p></div> :
      <form className="booking-layout" onSubmit={submit}>
        <div className="booking-steps">
          <fieldset className="booking-panel" disabled={busy}><legend><span className="step-number">1</span>차량과 정비 항목</legend>
            <label className="booking-field">내 차량<select value={vehicle} onChange={e => { invalidate(); setVehicle(e.target.value); }} required>{setup.cars.map(c => <option value={c.id} key={c.id}>{c.manufacturer} {c.model} · {c.plateNumber}</option>)}</select></label>
            <div className="service-options">{setup.services.map(s => <label className={`service-option ${serviceIds.includes(s.id) ? "is-selected" : ""}`} key={s.id}><input type="checkbox" checked={serviceIds.includes(s.id)} onChange={() => toggleService(s.id)} /><span><strong>{s.name}</strong><small>{s.durationMinutes}분 · 공임 {won(s.laborPrice)}</small></span></label>)}</div>
          </fieldset>
          <fieldset className="booking-panel" disabled={busy}><legend><span className="step-number">2</span>방문 날짜와 시간</legend>
            <p className="booking-caption">{setup.policy.opensAt.slice(0, 5)}~{setup.policy.closesAt.slice(0, 5)} · {setup.policy.closedDays.map(d => dayNames[d]).join("·")}요일 휴무 · 한국 시간</p>
            <label className="booking-field">방문 날짜<input type="date" required min={setup.policy.earliestDate} max={setup.policy.latestDate} value={date} onChange={e => { invalidate(); setDate(e.target.value); }} /></label>
            {feedback && <div className="notice" role="status">{feedback}</div>}
            {error && <div className="error" role="alert">{error} <Link className="inline-link" href="/appointments">내 예약 확인</Link> <button className="text-button" type="button" onClick={() => setRevision(n => n + 1)}>시간 다시 조회</button></div>}
            {loading ? <p role="status">예약 가능한 시간을 확인하고 있습니다…</p> : !serviceIds.length ? <p className="muted">먼저 정비 항목을 선택해 주세요.</p> : availability && <>
              <p className="booking-caption">{availability.closed ? "선택한 날짜는 휴무일입니다." : !availability.slots.length ? "선택한 정비를 진행할 수 있는 시간이 없습니다. 다른 날짜를 선택해 주세요." : `예상 소요 시간 ${availability.durationMinutes}분 · 방문 시간을 선택하세요.`}</p>
              <div className="time-options" role="group" aria-label="예약 가능한 방문 시간">{availability.slots.map(slot => <button key={slot.startsAt} type="button" aria-pressed={selected?.startsAt === slot.startsAt} className={`time-option ${selected?.startsAt === slot.startsAt ? "is-selected" : ""}`} onClick={() => setSelected(slot)}>{timeLabel(slot.startsAt)}</button>)}</div>
            </>}
          </fieldset>
          <fieldset className="booking-panel" disabled={busy}><legend><span className="step-number">3</span>요청사항</legend><label className="booking-field">정비소에 전달할 내용 (선택)<textarea rows={3} maxLength={500} value={notes} onChange={e => setNotes(e.target.value)} placeholder="차량에서 느껴지는 증상이나 요청사항을 남겨 주세요." /></label></fieldset>
        </div>
        <aside className="booking-summary"><h2>예약 내용 확인</h2><p>{selectedCar?.manufacturer} {selectedCar?.model}<br /><strong>{selectedCar?.plateNumber}</strong></p>
          <ul className="booking-items">{selectedItems.map(s => <li key={s.id}><span>{s.name}</span><span>{won(s.laborPrice)}</span></li>)}</ul>
          <div className="booking-total"><span>예상 공임</span><strong>{won(availability?.totalLaborPrice ?? total)}</strong></div>
          <p className="booking-caption"><Clock3 size={15} />예상 {availability?.durationMinutes ?? duration}분 · 부품 비용 별도</p>
          <div className="selected-visit">{selected ? <><strong>{dayLabel(date)}</strong><span>{timeLabel(selected.startsAt)} – {timeLabel(selected.endsAt)}</span></> : <span>방문 시간을 선택해 주세요.</span>}</div>
          <p className="booking-caption">신청 후 정비소의 확인을 거쳐 확정됩니다. 시작 전까지 내 예약에서 취소할 수 있습니다.</p>
          <button className="button primary full" disabled={busy || loading || !selected || !availability}>{busy ? "예약 신청 중…" : "예약 신청"}</button>
        </aside>
      </form>}
  </>;
}
