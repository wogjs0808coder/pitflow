"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/auth-provider";
import { useAppToast } from "@/components/app-toast";
import { api, ApiError, errorText, User } from "@/lib/api";
import { newPasswordError } from "@/lib/password";
import { BirthDateInput } from "@/components/birth-date-input";

function isFieldError(reason: unknown) {
  return reason instanceof ApiError && (reason.status === 400 || reason.status === 409
    || (reason.status === 403 && reason.message.includes("현재 비밀번호")));
}

export default function AccountPage() {
  const { user, refresh, logout, forgetUser } = useAuth();
  const showToast = useAppToast();
  const router = useRouter();
  const [editing, setEditing] = useState(false);
  const [profileError, setProfileError] = useState("");
  const [passwordError, setPasswordError] = useState("");
  const [busy, setBusy] = useState(false);
  if (!user) return null;
  const currentUser = user;
  const incomplete = user.role !== "MECHANIC" && !user.profileComplete;
  const mechanic = user.role === "MECHANIC";

  async function submitProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setProfileError("");
    setBusy(true);
    const data = new FormData(event.currentTarget);
    const role = currentUser.role;
    const currentPassword = String(data.get("currentPassword") ?? "");
    try {
      let updated: User;
      if (mechanic) {
        updated = await api<User>("/api/auth/profile/email", {
          method: "PUT",
          body: JSON.stringify({ email: String(data.get("email")), currentPassword }),
        });
      } else {
        updated = await api<User>(incomplete ? "/api/auth/profile/complete" : "/api/auth/profile", {
          method: incomplete ? "POST" : "PUT",
          body: JSON.stringify({
            name: role === "ADMIN" ? currentUser.name : String(data.get("name")).trim(),
            phoneNumber: String(data.get("phoneNumber")).trim(),
            birthDate: String(data.get("birthDate")),
            ...(!incomplete && { email: String(data.get("email")), currentPassword }),
          }),
        });
      }

      if (updated.email !== currentUser.email) {
        showToast("이메일이 변경되었습니다. 새 이메일로 다시 로그인해 주세요.", "success");
        try {
          await logout();
        } catch {
          // The backend also invalidates the old session after a committed email change.
          forgetUser();
          showToast("이메일은 저장되었습니다. 로그아웃 확인에 실패했습니다. 새 이메일로 다시 로그인해 주세요.", "error");
        }
        router.replace("/login");
        return;
      }

      await refresh();
      setEditing(false);
      showToast("계정 정보가 저장되었습니다.", "success");
      if (incomplete) router.replace(role === "ADMIN" ? "/admin/appointments" : "/dashboard");
    } catch (reason) {
      if (isFieldError(reason)) setProfileError(errorText(reason));
      else showToast(errorText(reason), "error");
    } finally {
      setBusy(false);
    }
  }

  async function submitPassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPasswordError("");
    const element = event.currentTarget;
    const data = new FormData(element);
    const newPassword = String(data.get("newPassword"));
    const policyError = newPasswordError(newPassword);
    if (policyError) { setPasswordError(policyError); return; }
    if (newPassword !== data.get("confirm")) {
      setPasswordError("새 비밀번호 확인이 일치하지 않습니다.");
      return;
    }
    setBusy(true);
    try {
      await api<void>("/api/auth/password", { method: "PUT", body: JSON.stringify({
        currentPassword: String(data.get("currentPassword")), newPassword,
      }) });
      element.reset();
      showToast("비밀번호가 변경되었습니다.", "success");
    } catch (reason) {
      if (isFieldError(reason)) setPasswordError(errorText(reason));
      else showToast(errorText(reason), "error");
    } finally {
      setBusy(false);
    }
  }

  return <div className="page-content">
    <div className="page-heading"><div><span className="eyebrow">MY ACCOUNT</span><h1>내 정보</h1>
      {incomplete && <p>계정 정보 등록이 필요합니다. 등록 후 기존 업무 화면으로 이동합니다.</p>}</div></div>
    <section className="form-panel">
      <h2>계정 정보</h2>
      {!incomplete && !editing ? <div>
        <p>이름: {user.name}</p><p>이메일(ID): {user.email}</p>
        <p>휴대폰 번호: {user.phoneNumber ?? "미등록"}</p>
        <p>생년월일: {user.birthDate ?? "미등록"}</p>
        <button className="button secondary" onClick={() => { setProfileError(""); setEditing(true); }}>
          {mechanic ? "이메일(ID) 변경" : "내 정보 수정"}
        </button>
      </div> : <form className="stack-form" onSubmit={submitProfile}>
        {profileError && <p className="error" role="alert">{profileError}</p>}
        {!mechanic && <label>이름<input name="name" defaultValue={user.name} required maxLength={50} readOnly={user.role === "ADMIN"} /></label>}
        <label>이메일(ID)
          <input name="email" type="email" defaultValue={user.email} readOnly={incomplete}
            required maxLength={254} autoComplete="email" />
          {!incomplete && <span className="muted">변경하면 새 이메일로 다시 로그인해야 합니다.</span>}
        </label>
        {!mechanic && <>
          <label>휴대폰 번호<input name="phoneNumber" type="tel" defaultValue={user.phoneNumber ?? ""} required autoComplete="tel" /></label>
          <BirthDateInput defaultValue={user.birthDate ?? ""} />
        </>}
        {!incomplete && <label>현재 비밀번호<input name="currentPassword" type="password" required autoComplete="current-password" /></label>}
        <div className="form-actions">
          {!incomplete && <button type="button" className="button secondary" disabled={busy}
            onClick={() => { setProfileError(""); setEditing(false); }}>취소</button>}
          <button className="button primary" disabled={busy}>{busy ? "저장 중…" : "적용"}</button>
        </div>
      </form>}
    </section>
    {!incomplete && <section className="form-panel"><h2>비밀번호 변경</h2>
      <form className="stack-form" onSubmit={submitPassword}>
        {passwordError && <p className="error" role="alert">{passwordError}</p>}
        <label>현재 비밀번호<input name="currentPassword" type="password" required autoComplete="current-password" /></label>
        <label>새 비밀번호<input name="newPassword" type="password" required placeholder="7~20자" autoComplete="new-password" /></label>
        <label>새 비밀번호 확인<input name="confirm" type="password" required autoComplete="new-password" /></label>
        <button className="button primary" disabled={busy}>{busy ? "변경 중…" : "비밀번호 변경"}</button>
      </form>
    </section>}
  </div>;
}
