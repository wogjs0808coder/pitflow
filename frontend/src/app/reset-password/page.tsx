"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { api, errorText } from "@/lib/api";
import { newPasswordError } from "@/lib/password";
import { BirthDateInput } from "@/components/birth-date-input";

export default function ResetPasswordPage() {
  const [done, setDone] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const element = event.currentTarget;
    setError(""); setDone(false);
    const form = new FormData(event.currentTarget);
    const newPassword = String(form.get("newPassword"));
    const policyError = newPasswordError(newPassword);
    if (policyError) { setError(policyError); return; }
    if (newPassword !== form.get("confirm")) { setError("새 비밀번호 확인이 일치하지 않습니다."); return; }
    setBusy(true);
    try {
      await api<void>("/api/auth/reset-password", { method: "POST", body: JSON.stringify({
        name: String(form.get("name")).trim(), birthDate: String(form.get("birthDate")),
        phoneNumber: String(form.get("phoneNumber")).trim(), newPassword,
      }) });
      setDone(true); element.reset();
    } catch (reason) { setError(errorText(reason)); }
    finally { setBusy(false); }
  }
  return <div className="auth-main" style={{ minHeight: "100vh" }}><section className="auth-card">
    <span className="eyebrow">계정 복구</span><h2>비밀번호 재설정</h2>
    <p className="muted">이름, 생년월일, 휴대폰 번호를 확인합니다. SMS 인증은 제공하지 않습니다.</p>
    <form className="stack-form" onSubmit={submit}>
      <label>이름<input name="name" required maxLength={50} autoComplete="name" /></label>
      <BirthDateInput />
      <label>휴대폰 번호<input name="phoneNumber" type="tel" required autoComplete="tel" /></label>
      <label>새 비밀번호<input name="newPassword" type="password" required placeholder="7~20자" autoComplete="new-password" /></label>
      <label>새 비밀번호 확인<input name="confirm" type="password" required autoComplete="new-password" /></label>
      {error && <p className="error" role="alert">{error}</p>}
      {done && <p className="notice" role="status">비밀번호가 변경되었습니다. 새 비밀번호로 로그인해 주세요.</p>}
      <button className="button primary full" disabled={busy}>비밀번호 변경</button>
    </form>
    <p className="auth-switch"><Link href="/login">로그인으로 돌아가기</Link></p>
  </section></div>;
}
