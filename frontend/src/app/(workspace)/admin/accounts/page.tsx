"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { api, ApiError, errorText } from "@/lib/api";
import { newPasswordError } from "@/lib/password";
import { BirthDateInput } from "@/components/birth-date-input";
import { useAppToast } from "@/components/app-toast";

type AdminAccount = {
  id: string; name: string; email: string; phoneNumber: string | null;
  birthDate: string | null; createdAt: string; mainAdmin: boolean;
  active: boolean; adminNumber: number | null;
};
type AccountAudit = {
  id: string; actorName: string | null; targetName: string;
  action: string; detail: string; createdAt: string;
};

export default function AdminAccountsPage() {
  const showToast = useAppToast();
  const { user } = useAuth();
  const [accounts, setAccounts] = useState<AdminAccount[]>([]);
  const [audit, setAudit] = useState<AccountAudit[]>([]);
  const [revision, setRevision] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const reload = useCallback(() => setRevision((current) => current + 1), []);

  useEffect(() => {
    if (user?.role !== "ADMIN" || !user.mainAdmin) return;
    const controller = new AbortController();
    Promise.all([
      api<AdminAccount[]>("/api/admin/accounts", { signal: controller.signal }),
      api<AccountAudit[]>("/api/admin/accounts/audit", { signal: controller.signal }),
    ]).then(([nextAccounts, nextAudit]) => {
      if (!controller.signal.aborted) { setAccounts(nextAccounts); setAudit(nextAudit); }
    }).catch((reason) => { if (!controller.signal.aborted) setError(errorText(reason)); });
    return () => controller.abort();
  }, [user?.role, user?.mainAdmin, revision]);

  if (user?.role !== "ADMIN" || !user.mainAdmin) return <p role="alert">메인 관리자만 이용할 수 있습니다.</p>;

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError("");
    const element = event.currentTarget;
    const data = new FormData(element);
    const policyError = newPasswordError(String(data.get("password")));
    if (policyError) { setError(policyError); setBusy(false); return; }
    try {
      await api("/api/admin/accounts", { method: "POST", body: JSON.stringify({
        email: String(data.get("email")).trim(), password: String(data.get("password")),
        phoneNumber: String(data.get("phoneNumber")).trim(), birthDate: String(data.get("birthDate")),
      }) });
      element.reset(); reload(); showToast("관리자 계정을 생성했습니다.", "success");
    } catch (reason) {
      if (reason instanceof ApiError && (reason.status === 400 || reason.status === 409)) setError(errorText(reason));
      else showToast(errorText(reason), "error");
    }
    finally { setBusy(false); }
  }

  async function deactivate(account: AdminAccount) {
    if (!window.confirm(`${account.name} 계정을 비활성화할까요? 기존 업무 기록은 보존됩니다.`)) return;
    setBusy(true); setError("");
    try { await api<void>(`/api/admin/accounts/${account.id}`, { method: "DELETE" }); reload(); showToast("관리자 계정을 비활성화했습니다.", "success"); }
    catch (reason) { showToast(errorText(reason), "error"); }
    finally { setBusy(false); }
  }

  return <div className="page-content">
    <div className="page-heading"><div><span className="eyebrow">ADMIN ACCOUNTS</span><h1>관리자 정보</h1>
      <p>관리자 계정과 계정 관련 이력을 확인합니다. 삭제는 비활성화로 처리됩니다.</p></div></div>
    {error && <p className="error" role="alert">{error}</p>}
    <details className="work-panel"><summary>신규 관리자 계정 생성</summary>
      <form className="stack-form" onSubmit={create}>
        <label>이메일<input name="email" type="email" required maxLength={254} /></label>
        <label>휴대폰 번호<input name="phoneNumber" type="tel" required /></label>
        <BirthDateInput />
        <label>초기 비밀번호<input name="password" type="password" required placeholder="7~20자" autoComplete="new-password" /></label>
        <button className="button primary" disabled={busy}>관리자 생성</button>
      </form>
    </details>
    <section className="work-panel"><h2>관리자 계정</h2>
      <div className="finance-table-wrap"><table className="finance-table"><thead><tr>
        <th>이름 / 번호</th><th>이메일</th><th>휴대폰</th><th>생년월일</th><th>생성일</th><th>상태</th><th>관리</th>
      </tr></thead><tbody>{accounts.map((account) => <tr key={account.id}>
        <td>{account.name}{account.mainAdmin ? " · 메인" : account.adminNumber ? ` · ${account.adminNumber}` : ""}</td>
        <td>{account.email}</td><td>{account.phoneNumber ?? "미등록"}</td>
        <td>{account.birthDate ?? "미등록"}</td><td>{new Date(account.createdAt).toLocaleString("ko-KR")}</td>
        <td>{account.active ? "활성" : "삭제/비활성"}</td><td>
          {!account.mainAdmin && account.active && <button className="button secondary" disabled={busy} onClick={() => void deactivate(account)}>삭제</button>}
        </td>
      </tr>)}</tbody></table></div>
    </section>
    <section className="work-panel"><h2>관리자 계정 이력</h2>
      <div className="finance-table-wrap"><table className="finance-table"><thead><tr>
        <th>일시</th><th>행위</th><th>수행자</th><th>대상</th><th>설명</th>
      </tr></thead><tbody>{audit.map((entry) => <tr key={entry.id}>
        <td>{new Date(entry.createdAt).toLocaleString("ko-KR")}</td><td>{entry.action}</td>
        <td>{entry.actorName ?? "본인"}</td><td>{entry.targetName}</td><td>{entry.detail}</td>
      </tr>)}</tbody></table></div>
    </section>
  </div>;
}
