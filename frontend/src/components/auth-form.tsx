"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Wrench, ArrowRight, ShieldCheck } from "lucide-react";
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
          <span className="eyebrow">YOUR CAR, WELL CARED FOR.</span>
          <h1>
            내 차를 위한
            <br />더 편한 관리.
          </h1>
          <p>
            차량 정보를 등록하고
            <br />
            필요한 정비 항목을 확인하세요.
          </p>
          <div className="auth-rule" />
          <span className="small-label">
            차량 정보 · 정비 항목 · 나만의 차고
          </span>
        </div>
        <span className="auth-bottom">
          <ShieldCheck size={18} /> 내 차량 정보는 내 계정에서만
        </span>
      </section>
      <section className="auth-main">
        <div className="auth-card">
          <span className="eyebrow">PITFLOW ACCOUNT</span>
          <h2>{register ? "계정 만들기" : "다시 오셨네요"}</h2>
          <p className="muted">
            {register
              ? "차량 관리를 시작할 계정을 만들어 주세요."
              : "로그인하고 내 차량을 확인하세요."}
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
