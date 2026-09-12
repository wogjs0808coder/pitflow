"use client";
import {FormEvent,useCallback,useEffect,useState} from "react";
import {api,errorText,won} from "@/lib/api";
import {useAuth} from "@/components/auth-provider";
import {useWorkCommand} from "@/components/work-command";
import {Part,Mechanic,Movement,milli,localTime} from "@/lib/work";

export default function PartsPage(){
  const {user}=useAuth();
  const [parts,setParts]=useState<Part[]>([]);
  const [mechanics,setMechanics]=useState<Mechanic[]>([]);
  const [selected,setSelected]=useState("");
  const [history,setHistory]=useState<Movement[]>([]);
  const [historyLoading,setHistoryLoading]=useState(false);
  const [error,setError]=useState("");
  const [loading,setLoading]=useState(false);
  const [revision,setRevision]=useState(0);
  const reload=useCallback(async()=>{setRevision(n=>n+1);},[]);
  const command=useWorkCommand(reload);
  useEffect(()=>{
    if(user?.role!=="ADMIN")return;
    const c=new AbortController();setError("");setLoading(true);
    Promise.all([api<Part[]>("/api/admin/parts",{signal:c.signal}),api<Mechanic[]>("/api/admin/mechanics",{signal:c.signal})])
      .then(([p,m])=>{if(!c.signal.aborted){setParts(p);setMechanics(m);}})
      .catch(e=>{if(!c.signal.aborted)setError(errorText(e));})
      .finally(()=>{if(!c.signal.aborted)setLoading(false);});
    return()=>c.abort();
  },[user?.role,revision]);
  useEffect(()=>{
    setHistory([]);if(!selected||user?.role!=="ADMIN")return;
    const c=new AbortController();setHistoryLoading(true);
    api<Movement[]>(`/api/admin/parts/${selected}/movements`,{signal:c.signal})
      .then(m=>{if(!c.signal.aborted)setHistory(m);})
      .catch(e=>{if(!c.signal.aborted)setError(errorText(e));})
      .finally(()=>{if(!c.signal.aborted)setHistoryLoading(false);});
    return()=>c.abort();
  },[selected,revision,user?.role]);
  if(user?.role!=="ADMIN")return <p role="alert">관리자만 이용할 수 있습니다.</p>;
  const disabled=command.blocked||loading||!!error;
  const form=(e:FormEvent<HTMLFormElement>)=>{e.preventDefault();return new FormData(e.currentTarget);};
  const chosen=parts.find(p=>p.id===selected);
  return <>
    <div className="page-heading"><div><span className="eyebrow">PARTS & PEOPLE</span><h1>부품·정비사 관리</h1><p>실물 입고와 사용·반환 기록을 기준으로 재고를 관리하세요.</p></div><button className="button secondary" disabled={command.busy} onClick={()=>void reload()}>새로고침</button></div>
    {command.feedback}{error&&<div className="error" role="alert">{error}</div>}
    <details className="work-panel"><summary>부품 등록</summary><p>등록 시 재고는 0입니다. 실물 수량은 입고 기록으로 추가합니다.</p>
      <form onSubmit={e=>{const f=form(e);void command.run("/api/admin/parts",{sku:f.get("sku"),name:f.get("name"),unit:f.get("unit"),minimumQuantity:f.get("minimum"),unitPrice:f.get("price"),active:true});}}><fieldset disabled={disabled}>
        <label>부품번호<input name="sku" required maxLength={60}/></label><label>부품명<input name="name" required maxLength={120}/></label>
        <label>단위<select name="unit"><option value="EA">개 (EA)</option><option value="L">리터 (L)</option><option value="KG">킬로그램 (KG)</option><option value="M">미터 (M)</option></select></label>
        <label>안전재고<input name="minimum" required type="number" defaultValue="0" min="0" max="99999999999.999" step="0.001"/></label>
        <label>단위당 금액 (원)<input name="price" required type="number" min="0" max="999999999999" step="1"/></label><button className="button primary">부품 등록</button>
      </fieldset></form>
    </details>
    {loading?<p role="status">불러오는 중입니다…</p>:<div className="work-layout"><section className="work-list" aria-label="부품 목록">{parts.map(p=><button key={p.id} className={`work-panel work-select ${selected===p.id?"selected-work":""}`} disabled={command.busy} onClick={()=>setSelected(p.id)} aria-pressed={selected===p.id}><span className="eyebrow">{p.sku} · {p.active?"사용 가능":"비활성"}</span><strong>{p.name}</strong><span>재고 {String(p.quantity)} {p.unit} · {won(p.unit_price)}/{p.unit}</span>{p.active&&milli(p.quantity)<=milli(p.minimum_quantity)&&<span className="stock-low">안전재고 이하 · 입고 확인 필요</span>}</button>)}{!parts.length&&!error&&<p>부품을 먼저 등록해 주세요.</p>}</section>
      <section>{chosen?<div className="work-panel"><h2>{chosen.name}</h2><p>재고 {String(chosen.quantity)} {chosen.unit}</p>
        <details key={`${chosen.id}-${revision}`}><summary>부품 정보 수정</summary><p>이력의 단가·단위는 보존됩니다. 단위는 등록 후 변경할 수 없습니다.</p><form onSubmit={e=>{const f=form(e);void command.run(`/api/admin/parts/${chosen.id}`,{sku:f.get("sku"),name:f.get("name"),unit:chosen.unit,minimumQuantity:f.get("minimum"),unitPrice:f.get("price"),active:f.get("active")==="on"},"PATCH");}}><fieldset disabled={disabled}><label>부품번호<input name="sku" defaultValue={chosen.sku} required maxLength={60}/></label><label>부품명<input name="name" defaultValue={chosen.name} required maxLength={120}/></label><label>안전재고<input name="minimum" defaultValue={String(chosen.minimum_quantity)} required type="number" min="0" max="99999999999.999" step="0.001"/></label><label>단위당 금액<input name="price" defaultValue={chosen.unit_price} required type="number" min="0" max="999999999999" step="1"/></label><label><input name="active" type="checkbox" defaultChecked={chosen.active}/>사용 가능</label><button className="button secondary">정보 저장</button></fieldset></form></details>
        <form onSubmit={e=>{const f=form(e);void command.run(`/api/admin/parts/${chosen.id}/receipts`,{quantity:f.get("quantity"),reason:f.get("reason")});}}><h3>실물 입고</h3><fieldset disabled={disabled||!chosen.active}><label>입고 수량 ({chosen.unit})<input name="quantity" type="number" required min="0.001" max="99999999999.999" step="0.001"/></label><label>입고 사유<input name="reason" required maxLength={500}/></label><button className="button primary">입고 기록</button></fieldset></form>
        <h3>재고 이동 이력</h3>{historyLoading?<p role="status">이력을 불러오는 중입니다…</p>:history.map(m=><article key={m.id} className="work-movement"><strong>{{RECEIPT:"입고",USE:"사용",RETURN:"반환"}[m.kind]} · {String(m.quantity)} {m.unit}</strong><p>{localTime(m.created_at)} · 처리 후 잔량 {String(m.balance_after)}</p><p>{m.reason}</p>{m.original_use_id&&<small>원래 사용 기록: {m.original_use_id}</small>}</article>)}{!historyLoading&&!history.length&&<p>재고 이동 내역이 없습니다.</p>}
      </div>:<p>부품을 선택하면 입고 및 이력을 확인할 수 있습니다.</p>}</section>
    </div>}
    <section className="work-panel"><h2>담당 정비사</h2><p>정비사는 담당자 명부입니다. 작업 변경 권한은 기존 관리자 계정이 가집니다.</p>
      <form onSubmit={e=>{const f=form(e);void command.run("/api/admin/mechanics",{code:f.get("code"),name:f.get("name"),active:true});}}><fieldset disabled={disabled}><label>사번<input name="code" required maxLength={40}/></label><label>이름<input name="name" required maxLength={80}/></label><button className="button primary">정비사 등록</button></fieldset></form>
      {mechanics.map(m=><details key={`${m.id}-${revision}`} className="work-movement"><summary>{m.code} · {m.name} · {m.active?"활성":"비활성"}</summary><form onSubmit={e=>{const f=form(e);void command.run(`/api/admin/mechanics/${m.id}`,{code:f.get("code"),name:f.get("name"),active:f.get("active")==="on"},"PATCH");}}><fieldset disabled={disabled}><label>사번<input name="code" defaultValue={m.code} required maxLength={40}/></label><label>이름<input name="name" defaultValue={m.name} required maxLength={80}/></label><label><input name="active" type="checkbox" defaultChecked={m.active}/>배정 가능</label><button className="button secondary">정비사 저장</button></fieldset></form></details>)}
    </section>
  </>;
}
