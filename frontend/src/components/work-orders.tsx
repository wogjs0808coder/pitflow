"use client";
import {FormEvent,useCallback,useEffect,useState} from "react";
import Link from "next/link";
import {useAuth} from "./auth-provider";
import {api,errorText,won} from "@/lib/api";
import {Appointment,seoulToday} from "@/lib/appointments";
import {Mechanic,Part,Work,WorkDetail,workLabel,workTransitions,localTime,remaining} from "@/lib/work";
import {useWorkCommand} from "./work-command";

export function WorkOrders({admin=false}:{admin?:boolean}) {
  const {user}=useAuth();
  const [orders,setOrders]=useState<Work[]>([]);
  const [mechanics,setMechanics]=useState<Mechanic[]>([]);
  const [parts,setParts]=useState<Part[]>([]);
  const [selected,setSelected]=useState("");
  const [detail,setDetail]=useState<WorkDetail|null>(null);
  const [error,setError]=useState("");
  const [loading,setLoading]=useState(false);
  const [date,setDate]=useState(seoulToday);
  const [bookings,setBookings]=useState<Appointment[]>([]);
  const [bookingError,setBookingError]=useState("");
  const [revision,setRevision]=useState(0);
  const base=admin?"/api/admin/work-orders":"/api/work-orders";
  const permitted=!!user&&(!admin||user.role==="ADMIN");
  const reload=useCallback(async()=>{setRevision(n=>n+1);},[]);
  const command=useWorkCommand(reload);
  useEffect(()=>{
    if(!permitted)return;
    const c=new AbortController(); setLoading(true);setError("");setDetail(null);
    Promise.all([api<Work[]>(base,{signal:c.signal}),
      admin?api<Mechanic[]>("/api/admin/mechanics",{signal:c.signal}):Promise.resolve([]),
      admin?api<Part[]>("/api/admin/parts",{signal:c.signal}):Promise.resolve([])])
      .then(([w,m,p])=>{if(!c.signal.aborted){setOrders(w);setMechanics(m);setParts(p);}})
      .catch(e=>{if(!c.signal.aborted)setError(errorText(e));})
      .finally(()=>{if(!c.signal.aborted)setLoading(false);});
    return()=>c.abort();
  },[base,admin,permitted,revision]);
  useEffect(()=>{
    setDetail(null); if(!selected||!permitted)return;
    const c=new AbortController();
    api<WorkDetail>(`${base}/${selected}`,{signal:c.signal}).then(d=>{if(!c.signal.aborted)setDetail(d);}).catch(e=>{if(!c.signal.aborted)setError(errorText(e));});
    return()=>c.abort();
  },[base,selected,permitted,revision]);
  useEffect(()=>{
    if(!admin||!permitted)return;
    const c=new AbortController();setBookings([]);setBookingError("");
    api<Appointment[]>(`/api/admin/appointments?from=${date}&to=${date}`,{signal:c.signal})
      .then(a=>{if(!c.signal.aborted)setBookings(a.filter(b=>b.status==="VISITED"));})
      .catch(e=>{if(!c.signal.aborted)setBookingError(errorText(e));});
    return()=>c.abort();
  },[date,admin,permitted,revision]);
  const fields=(e:FormEvent<HTMLFormElement>)=>{e.preventDefault();return new FormData(e.currentTarget);};
  if(!permitted)return <p role="alert">{admin?"관리자만 이용할 수 있습니다.":"로그인이 필요합니다."}</p>;
  const disabled=command.blocked||loading||!!error;
  return <>
    <div className="page-heading"><div><span className="eyebrow">SERVICE WORK</span><h1>{admin?"정비 작업 관리":"내 정비 작업"}</h1><p>입고부터 완료까지 작업 상태와 부품 사용 내역을 확인하세요.</p></div><button className="button secondary" onClick={()=>void reload()} disabled={command.busy}>새로고침</button></div>
    {command.feedback}{error&&<div className="error" role="alert">{error}</div>}
    {admin&&<details className="work-panel"><summary>예약에서 입고 처리</summary><p>예약 캘린더에서 방문 처리된 예약을 선택하세요. 입고 주행거리는 현재 차량 주행거리 이상이어야 합니다.</p>
      <label>예약 날짜<input type="date" value={date} disabled={disabled} onChange={e=>{if(e.target.value)setDate(e.target.value);}}/></label>
      {bookingError&&<p className="error">{bookingError}</p>}
      <form onSubmit={e=>{const f=fields(e);void command.run(`${base}/from-appointment`,{appointmentId:f.get("appointment"),receivedMileage:Number(f.get("mileage")),mechanicId:f.get("mechanic"),notes:f.get("notes")});}}>
        <fieldset disabled={disabled||!!bookingError}><label>방문 예약<select name="appointment" required defaultValue=""><option value="" disabled>예약 선택</option>{bookings.filter(b=>!orders.some(w=>w.appointment_id===b.id)).map(b=><option key={b.id} value={b.id}>{b.customerName} · {b.plateNumber} · {b.startsAt.slice(11,16)}</option>)}</select></label>
        <label>입고 주행거리 (km)<input name="mileage" type="number" min="0" max="9999999" step="1" required/></label>
        <label>담당 정비사<select name="mechanic" required defaultValue=""><option value="" disabled>정비사 선택</option>{mechanics.filter(m=>m.active).map(m=><option key={m.id} value={m.id}>{m.name}</option>)}</select></label>
        <label>요청사항<textarea name="notes" maxLength={1000}/></label><button className="button primary">입고 등록</button></fieldset>
      </form>{!mechanics.some(m=>m.active)&&<p><Link href="/admin/parts">부품·정비사 관리에서 담당자를 먼저 등록하세요.</Link></p>}
    </details>}
    {loading?<p role="status">작업 목록을 불러오는 중입니다…</p>:<div className="work-layout">
      <section aria-label="작업 목록" className="work-list">{orders.map(w=><button key={w.id} disabled={command.busy} aria-pressed={selected===w.id} className={`work-panel work-select ${selected===w.id?"selected-work":""}`} onClick={()=>setSelected(w.id)}><span className="eyebrow">{workLabel[w.status]}</span><strong>{w.vehicle_label} · {w.plate_number}</strong><span>{localTime(w.received_at)} · {w.received_mileage.toLocaleString()} km</span><span>담당 {w.mechanic_name}</span></button>)}{!orders.length&&!error&&<p>등록된 정비 작업이 없습니다.</p>}</section>
      <section aria-label="작업 상세">{detail?<div className="work-panel"><h2>{detail.plate_number} · {workLabel[detail.status]}</h2><p>입고 {detail.received_mileage.toLocaleString()} km · 담당 {detail.mechanic_name}</p><p className="booking-notes">{detail.notes}</p>
        <h3>정비 항목</h3><ul className="work-items">{detail.items.map(i=><li key={i.id}><span>{i.name} · 공임 {won(i.labor_price)}</span>{admin?<label><input type="checkbox" checked={i.done} disabled={disabled||detail.status!=="IN_PROGRESS"} onChange={e=>void command.run(`${base}/${detail.id}/items/${i.id}`,{done:e.target.checked},"PATCH")}/>완료</label>:<span>{i.done?"완료":"미완료"}</span>}</li>)}</ul>
        {admin&&workTransitions[detail.status].length>0&&<>
          <form onSubmit={e=>{const f=fields(e);void command.run(`${base}/${detail.id}/assignment`,{mechanicId:f.get("mechanic")},"PATCH");}}><fieldset disabled={disabled}><label>담당 변경<select name="mechanic" defaultValue={detail.mechanic_id} key={detail.mechanic_id}>{mechanics.filter(m=>m.active||m.id===detail.mechanic_id).map(m=><option value={m.id} key={m.id} disabled={!m.active}>{m.name}</option>)}</select></label><button className="button secondary">담당 배정</button></fieldset></form>
          <form onSubmit={e=>{const f=fields(e);const status=f.get("status");if(status==="CANCELLED"&&!window.confirm("작업을 취소합니다. 사용한 부품은 자동 반환되지 않습니다. 진행하시겠습니까?"))return;void command.run(`${base}/${detail.id}/status`,{status,reason:f.get("reason")},"PATCH");}}><fieldset disabled={disabled}><label>변경할 상태<select name="status" key={detail.status}>{workTransitions[detail.status].map(s=><option key={s} value={s}>{workLabel[s]}</option>)}</select></label><label>변경 사유 (취소 시 필수)<input name="reason" maxLength={900}/></label><button className="button primary">상태 변경</button></fieldset></form>
        </>}
        {admin&&detail.status==="IN_PROGRESS"&&<form onSubmit={e=>{const f=fields(e);const lines=parts.filter(p=>p.active).map(p=>({partId:p.id,quantity:String(f.get(p.id)||"")})).filter(l=>l.quantity.trim()!=="");if(!lines.length){setError("사용할 부품의 수량을 입력해 주세요.");return;}void command.run(`${base}/${detail.id}/parts/use`,{lines,reason:f.get("reason")});}}><h3>부품 사용</h3><p>여러 부품을 한 번에 사용하면 모두 처리되거나 모두 취소됩니다.</p><fieldset disabled={disabled}>{parts.filter(p=>p.active).map(p=><label key={p.id}>{p.name} · 재고 {String(p.quantity)} {p.unit}<input name={p.id} type="number" min="0.001" max="99999999999.999" step="0.001" placeholder="사용 수량"/></label>)}<label>사용 사유<input name="reason" required maxLength={500}/></label><button className="button primary" disabled={!parts.some(p=>p.active)}>선택 부품 사용</button></fieldset></form>}
        <h3>부품 사용·반환 이력</h3><p>취소 시 자동 복원되지 않습니다. 실제 회수한 미사용 부품만 반환 처리하세요.</p>
        {detail.movements.map(m=><article className="work-movement" key={m.id}><strong>{m.kind==="USE"?"사용":"반환"} · {m.part_name} · {String(m.quantity)} {m.unit}</strong><p>{localTime(m.created_at)} · {m.reason}</p>
          {admin&&m.kind==="USE"&&Number(remaining(m,detail.movements))>0&&<form onSubmit={e=>{const f=fields(e);void command.run(`${base}/${detail.id}/parts/return`,{originalUseId:m.id,quantity:f.get("quantity"),reason:f.get("reason")});}}><fieldset disabled={disabled}><label>실제 반환 수량 (최대 {remaining(m,detail.movements)} {m.unit})<input name="quantity" type="number" min="0.001" step="0.001" max={remaining(m,detail.movements)} required/></label><label>반환 사유<input name="reason" required maxLength={500}/></label><button className="button secondary">실물 반환 기록</button></fieldset></form>}</article>)}
        {!detail.movements.length&&<p>부품 사용 내역이 없습니다.</p>}
        <h3>작업 이력</h3><ol>{detail.events.map((e,i)=><li key={i}>{localTime(e.created_at)} · {e.detail}</li>)}</ol>
      </div>:<p>{selected?"작업 상세를 불러오는 중입니다…":"작업을 선택해 주세요."}</p>}</section>
    </div>}
  </>;
}
