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

type FeedbackTone = "success" | "error" | "warning";

const UNCERTAIN_STATUS = new Set([
  0,
  401,
  403,
  408,
  425,
  429,
  502,
  503,
  504,
]);

function storedJob(value: unknown, ownerId: string): Job | null {
  if (!value || typeof value !== "object") return null;

  const job = value as Partial<Job>;

  if (
    (job.ownerId === undefined || job.ownerId === ownerId) &&
    typeof job.key === "string" &&
    typeof job.path === "string" &&
    typeof job.method === "string" &&
    "body" in job
  ) {
    return {
      key: job.key,
      ownerId,
      path: job.path,
      method: job.method,
      body: job.body,
    };
  }

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

  const storage = user
    ? `pitflow-operation-${user.id}`
    : null;

  const [pending, setPending] = useState<Job | null>(null);
  const [busy, setBusy] = useState(false);
  const guard = useRef(false);
  const [ready, setReady] = useState(false);

  const [message, setMessage] = useState("");
  const [messageTone, setMessageTone] =
    useState<FeedbackTone>("success");

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

      const saved = storedJob(
        JSON.parse(raw) as unknown,
        user.id,
      );

      if (!saved) {
        setMessageTone("error");
        setMessage(
          "이 계정의 이전 요청 정보를 확인할 수 없습니다.",
        );
        return;
      }

      sessionStorage.setItem(
        storage,
        JSON.stringify(saved),
      );

      setPending(saved);
      setReady(true);
    } catch {
      setMessageTone("error");
      setMessage(
        "이전 요청을 복원하지 못했습니다. 저장 공간 설정을 확인해 주세요.",
      );
    }
  }, [storage, user]);

  useEffect(() => {
    if (
      !message ||
      messageTone !== "success" ||
      pending
    ) {
      return;
    }

    const timer = window.setTimeout(() => {
      setMessage("");
    }, 4500);

    return () => window.clearTimeout(timer);
  }, [message, messageTone, pending]);

  async function execute(job: Job) {
    if (guard.current || !storage || !user) return;

    if (job.ownerId !== user.id) {
      setMessageTone("error");
      setMessage(
        "다른 계정에서 만든 요청은 실행할 수 없습니다.",
      );
      return;
    }

    guard.current = true;
    setBusy(true);
    setMessage("");

    let success = false;

    try {
      sessionStorage.setItem(
        storage,
        JSON.stringify(job),
      );

      setPending(job);

      await api(job.path, {
        method: job.method,
        headers: {
          "Idempotency-Key": job.key,
        },
        body: JSON.stringify(job.body),
      });

      sessionStorage.removeItem(storage);
      setPending(null);

      success = true;
      setMessageTone("success");
      setMessage("처리가 완료되었습니다.");
    } catch (error) {
      if (isConclusiveFailure(error)) {
        sessionStorage.removeItem(storage);
        setPending(null);

        setMessageTone("error");
        setMessage(errorText(error));
      } else {
        setMessageTone("warning");
        setMessage(
          "처리 결과를 확인하지 못했습니다. 같은 계정으로 요청 결과를 다시 확인해 주세요.",
        );
      }
    } finally {
      guard.current = false;
      setBusy(false);
    }

    if (success) {
      await reload();
    }
  }

  function discard() {
    if (!storage || !pending) return;

    if (
      !window.confirm(
        "서버의 목록이나 이력에서 처리 결과를 먼저 확인했나요? 보류 요청을 삭제하면 새 요청은 다른 키로 실행됩니다.",
      )
    ) {
      return;
    }

    sessionStorage.removeItem(storage);
    setPending(null);

    setMessageTone("success");
    setMessage("보류 요청을 삭제했습니다.");
  }

  return {
    busy,

    blocked:
      busy ||
      !!pending ||
      !ready,

    run: (
      path: string,
      body: unknown,
      method = "POST",
    ) => {
      if (
        pending ||
        !ready ||
        guard.current ||
        !user
      ) {
        return Promise.resolve();
      }

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
        {!pending && message && (
          <div
            className="work-feedback-layer"
            aria-live="polite"
          >
            <div
              className={`work-feedback-toast ${messageTone}`}
              role={
                messageTone === "error"
                  ? "alert"
                  : "status"
              }
            >
              <span
                className="work-feedback-indicator"
                aria-hidden
              />

              <span className="work-feedback-message">
                {message}
              </span>

              <button
                type="button"
                className="work-feedback-close"
                aria-label="알림 닫기"
                onClick={() => setMessage("")}
              >
                닫기
              </button>
            </div>
          </div>
        )}

        {pending && (
          <div
            className="work-feedback-layer"
            aria-live="polite"
          >
            <div
              className="work-feedback-toast warning work-feedback-pending"
              role="alert"
            >
              <div>
                <strong>요청 결과 확인 필요</strong>

                <p>
                  {message ||
                    "처리 결과가 확정되지 않은 요청이 있습니다. 같은 요청을 다시 확인해 주세요."}
                </p>
              </div>

              <div className="work-feedback-actions">
                <button
                  type="button"
                  className="button secondary"
                  disabled={busy}
                  onClick={() =>
                    void execute(pending)
                  }
                >
                  다시 확인
                </button>

                <button
                  type="button"
                  className="button danger"
                  disabled={busy}
                  onClick={discard}
                >
                  확인 후 삭제
                </button>
              </div>
            </div>
          </div>
        )}
      </>
    ),
  };
}