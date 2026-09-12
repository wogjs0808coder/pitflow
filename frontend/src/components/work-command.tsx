"use client";
import {useEffect,useRef,useState} from "react";
import {api,ApiError,errorText} from "@/lib/api";
import {useAuth} from "./auth-provider";
type Job={key:string;path:string;method:string;body:unknown};
// Keep the SAME key and payload across uncertain responses and page reloads.
// Session storage contains an operation request, never an auth/CSRF token.
export function useWorkCommand(reload:()=>Promise<void>) {
  const {user}=useAuth();
  const storage=`pitflow-operation-${user?.id}`;
  const [pending,setPending]=useState<Job|null>(null);
  const [busy,setBusy]=useState(false);
  const guard=useRef(false);
  const [ready,setReady]=useState(false);
  const [message,setMessage]=useState("");
  useEffect(()=>{
    setReady(false);
    try {const saved=sessionStorage.getItem(storage);setPending(saved?JSON.parse(saved):null);}
    catch {setMessage("이전 요청을 복원하지 못했습니다. 저장 공간 설정을 확인해 주세요.");return;}
    setReady(true);
  },[storage]);
  async function execute(job:Job) {
    if(guard.current)return;
    guard.current=true;setBusy(true);setMessage("");
    let success=false;
    try {
      sessionStorage.setItem(storage,JSON.stringify(job));setPending(job);
      await api(job.path,{method:job.method,headers:{"Idempotency-Key":job.key},body:JSON.stringify(job.body)});
      sessionStorage.removeItem(storage);setPending(null);success=true;
      setMessage("처리했습니다.");
    } catch(e) {
      if(e instanceof ApiError && e.status>=400 && e.status<500) {
        sessionStorage.removeItem(storage);setPending(null);setMessage(errorText(e));
      } else setMessage("처리 결과를 확인하지 못했습니다. 아래 ‘동일 요청 다시 확인’으로 결과를 확인해 주세요.");
    } finally {guard.current=false;setBusy(false);}
    if(success) await reload();
  }
  return {busy,blocked:busy||!!pending||!ready,
    run:(path:string,body:unknown,method="POST")=>{
      if(pending||!ready||guard.current)return Promise.resolve();
      return execute({key:crypto.randomUUID(),path,method,body});
    },
    feedback:<>{message&&<div className="notice" role="status">{message}</div>}{pending&&<div className="error" role="alert">결과 확인이 필요한 요청이 있습니다. 중복 처리 없이 같은 요청을 다시 확인합니다. <button type="button" className="button secondary" disabled={busy} onClick={()=>void execute(pending)}>동일 요청 다시 확인</button></div>}</>};
}
