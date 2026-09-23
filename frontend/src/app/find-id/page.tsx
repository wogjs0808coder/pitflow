"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { api, errorText } from "@/lib/api";
import { BirthDateInput } from "@/components/birth-date-input";

export default function FindIdPage() {
  const [email, setEmail] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setEmail(""); setError(""); setBusy(true);
    const form = new FormData(event.currentTarget);
    try {
      const result = await api<{ email: string }>("/api/auth/find-id", {
        method: "POST",
        body: JSON.stringify({
          name: String(form.get("name")).trim(),
          birthDate: String(form.get("birthDate")),
          phoneNumber: String(form.get("phoneNumber")).trim(),
        }),
      });
      setEmail(result.email);
    } catch (reason) { setError(errorText(reason)); }
    finally { setBusy(false); }
  }
  return <div className="auth-main" style={{ minHeight: "100vh" }}><section className="auth-card">
    <span className="eyebrow">계정 복구</span><h2>아이디 찾기</h2>
    <p className="muted">가입할 때 등록한 정보를 입력해 주세요.</p>
    <form className="stack-form" onSubmit={submit}>
      <label>이름<input name="name" required maxLength={50} autoComplete="name" /></label>
      <BirthDateInput />
      <label>휴대폰 번호<input name="phoneNumber" type="tel" required autoComplete="tel" /></label>
      {error && <p className="error" role="alert">{error}</p>}
      {email && <p className="notice" role="status">등록된 이메일: <strong>{email}</strong></p>}
      <button className="button primary full" disabled={busy}>아이디 확인</button>
    </form>
    <p className="auth-switch"><Link href="/login">로그인으로 돌아가기</Link></p>
  </section></div>;
}
