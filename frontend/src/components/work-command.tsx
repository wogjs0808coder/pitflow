"use client";
import { useEffect, useRef, useState } from "react";
import { api, ApiError, errorText } from "@/lib/api";
import { useAuth } from "./auth-provider";

type Job = {
  key: string;
  ownerId: string;
  path: string;
  method: string;
  body: unknown;
};

const UNCERTAIN_STATUS = new Set([0, 401, 403, 408, 425, 429, 502, 503, 504]);

function storedJob(value: unknown, ownerId: string): Job | null {
  if (!value || typeof value !== "object") return null;
  const job = value as Partial<Job>;
  if (
    (job.ownerId === undefined || job.ownerId === ownerId) &&
    typeof job.key === "string" &&
    typeof job.path === "string" &&
    typeof job.method === "string" &&
    "body" in job
  )
    return {
      key: job.key,
      ownerId,
      path: job.path,
      method: job.method,
      body: job.body,
    };
  return null;
}

function isConclusiveFailure(error: unknown) {
  return (
    error instanceof ApiError &&
    error.status >= 400 &&
    error.status < 500 &&
    !UNCERTAIN_STATUS.has(error.status)
  );
}

// Preserve the same owner, key and payload when a response is uncertain.
// Session storage contains an operation request, never an auth/CSRF token.
export function useWorkCommand(reload: () => Promise<void>) {
  const { user } = useAuth();
  const storage = user ? `pitflow-operation-${user.id}` : null;
  const [pending, setPending] = useState<Job | null>(null);
  const [busy, setBusy] = useState(false);
  const guard = useRef(false);
  const [ready, setReady] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => {
    setReady(false);
    setPending(null);
    if (!storage || !user) return;
    try {
      const raw = sessionStorage.getItem(storage);
      if (!raw) {
        setReady(true);
        return;
      }
      const saved = storedJob(JSON.parse(raw) as unknown, user.id);
      if (!saved) {
        setMessage("이 계정의 이전 요청 정보를 확인할 수 없습니다.");
        return;
      }
      sessionStorage.setItem(storage, JSON.stringify(saved));
      setPending(saved);
      setReady(true);
    } catch {
      setMessage(
        "이전 요청을 복원하지 못했습니다. 저장 공간 설정을 확인해 주세요.",
      );
    }
  }, [storage, user]);

  async function execute(job: Job) {
    if (guard.current || !storage || !user) return;
    if (job.ownerId !== user.id) {
      setMessage("다른 계정에서 만든 요청은 실행할 수 없습니다.");
      return;
    }
    guard.current = true;
    setBusy(true);
    setMessage("");
    let success = false;
    try {
      sessionStorage.setItem(storage, JSON.stringify(job));
      setPending(job);
      await api(job.path, {
        method: job.method,
        headers: { "Idempotency-Key": job.key },
        body: JSON.stringify(job.body),
      });
      sessionStorage.removeItem(storage);
      setPending(null);
      success = true;
      setMessage("처리했습니다.");
    } catch (error) {
      if (isConclusiveFailure(error)) {
        sessionStorage.removeItem(storage);
        setPending(null);
        setMessage(errorText(error));
      } else {
        setMessage(
          "처리 결과를 확인하지 못했습니다. 재로그인했다면 같은 계정으로 아래 요청을 다시 확인해 주세요.",
        );
      }
    } finally {
      guard.current = false;
      setBusy(false);
    }
    if (success) await reload();
  }

  function discard() {
    if (!storage || !pending) return;
    if (
      !window.confirm(
        "서버의 목록이나 이력에서 처리 결과를 먼저 확인했나요? 보류 요청을 삭제하면 새 요청은 다른 키로 실행됩니다.",
      )
    )
      return;
    sessionStorage.removeItem(storage);
    setPending(null);
    setMessage("보류 요청을 삭제했습니다.");
  }

  return {
    busy,
    blocked: busy || !!pending || !ready,
    run: (path: string, body: unknown, method = "POST") => {
      if (pending || !ready || guard.current || !user) return Promise.resolve();
      return execute({
        key: crypto.randomUUID(),
        ownerId: user.id,
        path,
        method,
        body,
      });
    },
    feedback: (
      <>
        {message && (
          <div className="notice" role="status">
            {message}
          </div>
        )}
        {pending && (
          <div className="error" role="alert">
            <p>
              결과 확인이 필요한 요청이 있습니다. 같은 계정·요청 키·내용으로
              다시 확인합니다.
            </p>
            <div className="button-row">
              <button
                type="button"
                className="button secondary"
                disabled={busy}
                onClick={() => void execute(pending)}
              >
                동일 요청 다시 확인
              </button>
              <button
                type="button"
                className="button danger"
                disabled={busy}
                onClick={discard}
              >
                확인 후 보류 요청 삭제
              </button>
            </div>
          </div>
        )}
      </>
    ),
  };
}
