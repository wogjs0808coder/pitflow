"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import { api, errorText } from "@/lib/api";
import { useAuth } from "@/components/auth-provider";
import { useWorkCommand } from "@/components/work-command";
import { FinanceSettings } from "@/lib/finance";
import { newPasswordError } from "@/lib/password";

type MechanicAccount = {
  id: string;
  accountId: string | null;
  code: string;
  name: string;
  email: string | null;
  active: boolean;
  hourlyCost: number | null;
  monthlyBaseSalary: number | null;
  monthlyStandardHours: number;
  derivedHourlyCost: number | null;
};

export default function MechanicsPage() {
  const { user } = useAuth();
  const [mechanics, setMechanics] = useState<MechanicAccount[]>([]);
  const [error, setError] = useState("");
  const [settings, setSettings] = useState<FinanceSettings | null>(null);
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const reload = useCallback(async () => setRevision((value) => value + 1), []);
  const command = useWorkCommand(reload);

  useEffect(() => {
    if (user?.role !== "ADMIN") return;
    const controller = new AbortController();
    Promise.all([
      api<MechanicAccount[]>("/api/admin/mechanic-accounts", { signal: controller.signal }),
      api<FinanceSettings>("/api/admin/finance/settings", { signal: controller.signal }),
    ])
      .then(([value, reference]) => {
        if (!controller.signal.aborted) {
          setMechanics(value);
          setSettings(reference);
        }
      })
      .catch((reason) => !controller.signal.aborted && setError(errorText(reason)));
    return () => controller.abort();
  }, [user?.role, revision]);

  async function submit(
    event: FormEvent<HTMLFormElement>,
    path: string,
    body: (data: FormData) => object,
    method = "POST",
  ) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const enteredPassword = data.get("password");
    if (typeof enteredPassword === "string") {
      const policyError = newPasswordError(enteredPassword);
      if (policyError) { setError(policyError); return; }
    }
    setBusy(true);
    setError("");
    try {
      await api(path, {
        method,
        body: JSON.stringify(body(data)),
      });
      form.reset();
      reload();
    } catch (reason) {
      setError(errorText(reason));
    } finally {
      setBusy(false);
    }
  }

  if (user?.role !== "ADMIN") return <p role="alert">관리자만 이용할 수 있습니다.</p>;
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">MECHANIC ACCOUNTS</span>
          <h1>정비사·계정 관리</h1>
          <p>정비사 명부의 활성 상태와 로그인 계정 연결 여부를 관리합니다.</p>
        </div>
        <button className="button secondary" disabled={busy} onClick={reload}>새로고침</button>
      </div>
      {error && <div className="error" role="alert">{error}</div>}
      <details className="work-panel">
        <summary>새 정비사와 로그인 계정 등록</summary>
        <form onSubmit={(event) => void submit(event, "/api/admin/mechanic-accounts", (data) => ({
          code: data.get("code"), name: data.get("name"), email: data.get("email"),
          password: data.get("password"), active: data.get("active") === "on",
          hourlyCost: data.get("hourlyCost") === "" ? null : data.get("hourlyCost"),
        }))}>
          <fieldset disabled={busy}>
            <label>사번<input name="code" required maxLength={40} /></label>
            <label>이름<input name="name" required maxLength={50} /></label>
            <label>이메일<input name="email" type="email" required maxLength={254} /></label>
            <label>초기 비밀번호<input name="password" type="password" required placeholder="7~20자" autoComplete="new-password" /></label>
            <label>시간당 원가 (원, 선택)<input name="hourlyCost" type="number" min="0" step="1" placeholder="미입력 시 미확정" /></label>
            <label><input name="active" type="checkbox" defaultChecked /> 활성</label>
            <button className="button primary">등록</button>
          </fieldset>
        </form>
      </details>
      {mechanics.map((mechanic) => (
        <article className="work-panel" key={mechanic.id}>
          <h2>{mechanic.code} · {mechanic.name}</h2>
          <p>{mechanic.active ? "활성" : "비활성"} · {mechanic.accountId ? `계정 연결됨 (${mechanic.email})` : "로그인 계정 미연결"}</p>
          <p>시간당 원가: {mechanic.hourlyCost === null ? "미확정" : `${mechanic.hourlyCost.toLocaleString("ko-KR")}원`}</p>
          <p>{mechanic.monthlyBaseSalary === null ? "기존 시간당 원가 방식" : `월급 기준 · ${mechanic.monthlyBaseSalary.toLocaleString("ko-KR")}원 / ${mechanic.monthlyStandardHours}시간 · 계산 시간당 ${mechanic.derivedHourlyCost?.toLocaleString("ko-KR")}원`}</p>
          <form onSubmit={(event) => {
            event.preventDefault();
            const data = new FormData(event.currentTarget);
            void command.run(`/api/admin/mechanic-accounts/${mechanic.id}/salary-cost`, {
              monthlyBaseSalary: data.get("monthlyBaseSalary") === "" ? null : Number(data.get("monthlyBaseSalary")),
              monthlyStandardHours: Number(data.get("monthlyStandardHours")),
            }, "PATCH");
          }}>
            <fieldset disabled={busy || command.blocked}>
              <label>기준 월급 (원)<input name="monthlyBaseSalary" type="number" min="0" step="1" defaultValue={mechanic.monthlyBaseSalary ?? ""} placeholder={`참고 ${settings?.default_monthly_base_salary?.toLocaleString("ko-KR") ?? "3,500,000"}원`} /></label>
              <label>기준 월 근로시간<input name="monthlyStandardHours" type="number" min="0.01" step="0.01" required defaultValue={mechanic.monthlyStandardHours ?? settings?.default_monthly_standard_hours ?? 209} /></label>
              <span className="field-help">참고값은 계획 편의를 위한 값이며 저장 전에는 실제 급여로 사용되지 않습니다.</span>
              <button className="button secondary">월급 기준 저장</button>
            </fieldset>
          </form>
          <form onSubmit={(event) => void submit(event, `/api/admin/mechanic-accounts/${mechanic.id}/hourly-cost`, (data) => ({
            hourlyCost: data.get("hourlyCost") === "" ? null : data.get("hourlyCost"),
          }), "PATCH")}>
            <fieldset disabled={busy}>
              <label>시간당 원가 (원)<input name="hourlyCost" type="number" min="0" step="1" defaultValue={mechanic.hourlyCost ?? ""} placeholder="비우면 미확정" /></label>
              <button className="button secondary">원가 저장</button>
            </fieldset>
          </form>
          {!mechanic.accountId && (
            <form onSubmit={(event) => void submit(event, `/api/admin/mechanic-accounts/${mechanic.id}/account`, (data) => ({
              email: data.get("email"), password: data.get("password"),
            }))}>
              <fieldset disabled={busy}>
                <label>이메일<input name="email" type="email" required maxLength={254} /></label>
                <label>초기 비밀번호<input name="password" type="password" required placeholder="7~20자" autoComplete="new-password" /></label>
                <button className="button secondary">기존 정비사에 계정 연결</button>
              </fieldset>
            </form>
          )}
          <button className="button secondary" disabled={busy} onClick={async () => {
            setBusy(true); setError("");
            try {
              await api(`/api/admin/mechanic-accounts/${mechanic.id}/active`, {
                method: "PATCH", body: JSON.stringify({ active: !mechanic.active }),
              });
              reload();
            } catch (reason) { setError(errorText(reason)); } finally { setBusy(false); }
          }}>{mechanic.active ? "비활성화" : "활성화"}</button>
        </article>
      ))}
      {!mechanics.length && !error && <p className="empty-state">등록된 정비사가 없습니다.</p>}
      {command.feedback}
    </>
  );
}
