"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/auth-provider";
import { api, errorText, User } from "@/lib/api";
import { newPasswordError } from "@/lib/password";
import { BirthDateInput } from "@/components/birth-date-input";

export default function AccountPage() {
  const { user, refresh } = useAuth();
  const router = useRouter();
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  if (!user) return null;
  const incomplete = user.role !== "MECHANIC" && !user.profileComplete;

  async function submitProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(""); setMessage(""); setBusy(true);
    const data = new FormData(event.currentTarget);
    const role = user?.role;
    const body = {
      name: user?.role === "ADMIN" ? user.name : String(data.get("name")).trim(),
      phoneNumber: String(data.get("phoneNumber")).trim(),
      birthDate: String(data.get("birthDate")),
      ...(incomplete ? {} : { currentPassword: String(data.get("currentPassword")) }),
    };
    try {
      await api<User>(incomplete ? "/api/auth/profile/complete" : "/api/auth/profile", {
        method: incomplete ? "POST" : "PUT", body: JSON.stringify(body),
      });
      await refresh(); setEditing(false); setMessage("계정 정보가 저장되었습니다.");
      if (incomplete) router.replace(role === "ADMIN" ? "/admin/appointments" : "/dashboard");
    } catch (reason) { setError(errorText(reason)); }
    finally { setBusy(false); }
  }

  async function submitPassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(""); setMessage("");
    const element = event.currentTarget;
    const data = new FormData(element);
    const newPassword = String(data.get("newPassword"));
    const policyError = newPasswordError(newPassword);
    if (policyError) { setError(policyError); return; }
    if (newPassword !== data.get("confirm")) { setError("새 비밀번호 확인이 일치하지 않습니다."); return; }
    setBusy(true);
    try {
      await api<void>("/api/auth/password", { method: "PUT", body: JSON.stringify({
        currentPassword: String(data.get("currentPassword")), newPassword,
      }) });
      element.reset(); setMessage("비밀번호가 변경되었습니다.");
    } catch (reason) { setError(errorText(reason)); }
    finally { setBusy(false); }
  }

  return <div className="page-content">
    <div className="page-heading"><div><span className="eyebrow">MY ACCOUNT</span><h1>내 정보</h1>
      {incomplete && <p>계정 정보 등록이 필요합니다. 등록 후 기존 업무 화면으로 이동합니다.</p>}</div></div>
    {error && <p className="error" role="alert">{error}</p>}
    {message && <p className="notice" role="status">{message}</p>}
    <section className="form-panel">
      <h2>계정 정보</h2>
      {!incomplete && !editing ? <div>
        <p>이름: {user.name}</p><p>이메일: {user.email}</p>
        <p>휴대폰 번호: {user.phoneNumber ?? "미등록"}</p>
        <p>생년월일: {user.birthDate ?? "미등록"}</p>
        {user.role !== "MECHANIC" && <button className="button secondary" onClick={() => setEditing(true)}>내 정보 수정</button>}
      </div> : <form className="stack-form" onSubmit={submitProfile}>
        <label>이름<input name="name" defaultValue={user.name} required maxLength={50} readOnly={user.role === "ADMIN"} /></label>
        <label>이메일<input value={user.email} readOnly /></label>
        <label>휴대폰 번호<input name="phoneNumber" type="tel" defaultValue={user.phoneNumber ?? ""} required autoComplete="tel" /></label>
        <BirthDateInput defaultValue={user.birthDate ?? ""} />
        {!incomplete && <label>현재 비밀번호<input name="currentPassword" type="password" required autoComplete="current-password" /></label>}
        <div className="form-actions">
          {!incomplete && <button type="button" className="button secondary" onClick={() => setEditing(false)}>취소</button>}
          <button className="button primary" disabled={busy}>적용</button>
        </div>
      </form>}
    </section>
    {!incomplete && <section className="form-panel"><h2>비밀번호 변경</h2>
      <form className="stack-form" onSubmit={submitPassword}>
        <label>현재 비밀번호<input name="currentPassword" type="password" required autoComplete="current-password" /></label>
        <label>새 비밀번호<input name="newPassword" type="password" required placeholder="7~20자" autoComplete="new-password" /></label>
        <label>새 비밀번호 확인<input name="confirm" type="password" required autoComplete="new-password" /></label>
        <button className="button primary" disabled={busy}>비밀번호 변경</button>
      </form>
    </section>}
  </div>;
}
