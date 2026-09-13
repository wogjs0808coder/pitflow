"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import {
  Wrench,
  ArrowRight,
  ShieldCheck,
  CalendarDays,
  ClipboardCheck,
  CarFront,
} from "lucide-react";
import { api, errorText, User } from "@/lib/api";
import { useAuth } from "./auth-provider";
export function AuthForm({ register = false }: { register?: boolean }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const router = useRouter();
  const { refresh } = useAuth();
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const form = new FormData(e.currentTarget);
    const email = String(form.get("email")).trim();
    const password = String(form.get("password"));
    try {
      if (register) {
        if (password !== form.get("confirm")) {
          setError("비밀번호 확인이 일치하지 않습니다.");
          return;
        }
        await api<User>("/api/auth/register", {
          method: "POST",
          body: JSON.stringify({
            email,
            password,
            name: String(form.get("name")).trim(),
          }),
        });
      }
      await api<User>("/api/auth/login", {
        method: "POST",
        body: new URLSearchParams({ email, password }),
      });
      await refresh();
      router.replace("/dashboard");
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="auth-page">
      <section className="auth-story">
        <Link href="/" className="brand">
          <span className="brand-mark">
            <Wrench size={21} />
          </span>
          PitFlow.
        </Link>
        <div>
          <span className="eyebrow">PITFLOW VEHICLE SERVICE</span>
          <h1>
            내 차 정비,
            <br />예약부터 이력까지.
          </h1>
          <p>정비 예약과 작업 진행 상황을 한곳에서 확인하세요.</p>
          <div className="auth-service-list" aria-label="제공 기능">
            <span>
              <CalendarDays size={18} /> 정비 예약 관리
            </span>
            <span>
              <ClipboardCheck size={18} /> 작업·출고 상태 확인
            </span>
            <span>
              <CarFront size={18} /> 차량별 정비 이력
            </span>
          </div>
        </div>
        <span className="auth-bottom">
          <ShieldCheck size={18} /> 내 차량 정보는 내 계정에서만
        </span>
      </section>
      <section className="auth-main">
        <div className="auth-card">
          <span className="eyebrow">고객 서비스</span>
          <h2>{register ? "차량 관리 시작하기" : "PitFlow 로그인"}</h2>
          <p className="muted">
            {register
              ? "차량 관리를 시작할 계정을 만들어 주세요."
              : "예약 내역과 차량 정비 상태를 확인하세요."}
          </p>
          <form onSubmit={submit} className="stack-form">
            {register && (
              <label>
                이름
                <input
                  name="name"
                  autoComplete="name"
                  required
                  maxLength={50}
                  placeholder="이름을 입력하세요"
                />
              </label>
            )}
            <label>
              이메일
              <input
                type="email"
                name="email"
                autoComplete="email"
                required
                maxLength={254}
                placeholder="name@example.com"
              />
            </label>
            <label>
              비밀번호
              <input
                type="password"
                name="password"
                autoComplete={register ? "new-password" : "current-password"}
                required
                minLength={register ? 12 : undefined}
                maxLength={64}
                placeholder={
                  register ? "12~64자 비밀번호" : "비밀번호를 입력하세요"
                }
              />
            </label>
            {register && (
              <label>
                비밀번호 확인
                <input
                  type="password"
                  name="confirm"
                  autoComplete="new-password"
                  required
                  minLength={12}
                  maxLength={64}
                  placeholder="비밀번호를 다시 입력하세요"
                />
              </label>
            )}
            {error && (
              <div className="error" role="alert">
                {error}
              </div>
            )}
            <button className="button primary full" disabled={busy}>
              {busy ? "처리 중…" : register ? "가입하고 시작하기" : "로그인"}
              <ArrowRight size={18} />
            </button>
          </form>
          <p className="auth-switch">
            {register ? "이미 계정이 있으신가요?" : "처음 방문하셨나요?"}{" "}
            <Link href={register ? "/login" : "/register"}>
              {register ? "로그인" : "회원가입"}
            </Link>
          </p>
        </div>
      </section>
    </div>
  );
}
