"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { api, errorText } from "@/lib/api";
import { useAuth } from "@/components/auth-provider";
import { newPasswordError } from "@/lib/password";
import { BirthDateInput } from "@/components/birth-date-input";

export default function AdminRegisterPage() {
  const router = useRouter();
  const { refresh } = useAuth();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError("");
    const form = new FormData(event.currentTarget);
    const email = String(form.get("email")).trim();
    const password = String(form.get("password"));
    const policyError = newPasswordError(password);
    if (policyError) { setError(policyError); return; }
    if (password !== form.get("confirm")) { setError("비밀번호 확인이 일치하지 않습니다."); return; }
    setBusy(true);
    try {
      await api("/api/auth/admin-register", { method: "POST", body: JSON.stringify({
        email, password, phoneNumber: String(form.get("phoneNumber")).trim(),
        birthDate: String(form.get("birthDate")), inviteCode: String(form.get("inviteCode")),
      }) });
      await api("/api/auth/login", { method: "POST", body: new URLSearchParams({ email, password }) });
      await refresh();
      router.replace("/admin/appointments");
    } catch (reason) { setError(errorText(reason)); }
    finally { setBusy(false); }
  }
  return <div className="auth-main" style={{ minHeight: "100vh" }}><section className="auth-card">
    <span className="eyebrow">관리자 계정</span><h2>관리자 신규 가입</h2>
    <p className="muted">관리자 이름은 가입 순서에 따라 자동으로 정해집니다.</p>
    <form className="stack-form" onSubmit={submit}>
      <label>이메일<input name="email" type="email" required maxLength={254} autoComplete="email" /></label>
      <label>휴대폰 번호<input name="phoneNumber" type="tel" required autoComplete="tel" /></label>
      <BirthDateInput />
      <label>비밀번호<input name="password" type="password" required placeholder="7~20자" autoComplete="new-password" /></label>
      <label>비밀번호 확인<input name="confirm" type="password" required autoComplete="new-password" /></label>
      <label>관리자 가입 허용 코드<input name="inviteCode" type="password" required autoComplete="off" /></label>
      {error && <p className="error" role="alert">{error}</p>}
      <button className="button primary full" disabled={busy}>관리자 가입</button>
    </form>
    <p className="auth-switch"><Link href="/login">로그인으로 돌아가기</Link></p>
  </section></div>;
}
