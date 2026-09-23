"use client";

import Link from "next/link";
import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { CalendarPlus, RefreshCw } from "lucide-react";
import { api, errorText } from "@/lib/api";
import {
  Appointment,
  monthRange,
  seoulToday,
} from "@/lib/appointments";
import { AppointmentCard } from "@/components/appointment-card";

export default function AppointmentsPage() {
  return <Suspense fallback={<p role="status">예약 내역을 불러오는 중입니다…</p>}>
    <AppointmentsContent />
  </Suspense>;
}

function AppointmentsContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const requestedMonth = searchParams.get("month");
  const month = requestedMonth && /^\d{4}-(0[1-9]|1[0-2])$/.test(requestedMonth)
    ? requestedMonth : seoulToday().slice(0, 7);
  const [appointments, setAppointments] =
    useState<Appointment[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    const range = monthRange(month);

    setLoading(true);
    setError("");

    api<Appointment[]>(
      `/api/appointments?from=${range.from}&to=${range.to}`,
      { signal: controller.signal },
    )
      .then((rows) => {
        if (!controller.signal.aborted) setAppointments(rows);
      })
      .catch((e) => {
        if (!controller.signal.aborted) {
          setError(errorText(e));
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setLoading(false);
        }
      });

    return () => controller.abort();
  }, [month, revision]);

  useEffect(() => {
    if (!notice) return;

    const timer = window.setTimeout(() => {
      setNotice("");
    }, 4500);

    return () => window.clearTimeout(timer);
  }, [notice]);

  async function cancel(a: Appointment) {
    if (
      !window.confirm(
        "예약을 취소하시겠습니까? 취소한 예약은 되돌릴 수 없습니다.",
      )
    ) {
      return;
    }

    setBusy(a.id);
    setError("");
    setNotice("");

    try {
      await api(`/api/appointments/${a.id}/cancel`, {
        method: "POST",
      });

      setNotice("예약을 취소했습니다.");
      setRevision((n) => n + 1);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="customer-appointments-page">
      <div className="page-heading customer-appointments-heading">
        <div>
          <span className="eyebrow">APPOINTMENTS</span>
          <h1>내 정비 예약</h1>
          <p>
            예정된 방문 일정과 예약 상태를 확인할 수 있습니다.
          </p>
        </div>

        <Link
          className="button primary"
          href="/appointments/new"
        >
          <CalendarPlus size={18} />
          새 예약
        </Link>
      </div>

      <div className="booking-toolbar customer-booking-toolbar">
        <label>
          조회 월
          <input
            type="month"
            value={month}
            disabled={!!busy}
            onChange={(e) => {
              if (/^\d{4}-(0[1-9]|1[0-2])$/.test(e.target.value)) {
                const next = new URLSearchParams(searchParams.toString());
                next.set("month", e.target.value);
                router.push(`/appointments?${next.toString()}`, { scroll: false });
              }
            }}
          />
        </label>

        <button
          className="button secondary"
          disabled={loading || !!busy}
          onClick={() => setRevision((n) => n + 1)}
        >
          <RefreshCw size={16} />
          새로고침
        </button>
      </div>

      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}

      {notice && (
        <div
          className="customer-booking-feedback"
          role="status"
        >
          <span aria-hidden />
          <strong>{notice}</strong>

          <button
            type="button"
            aria-label="알림 닫기"
            onClick={() => setNotice("")}
          >
            닫기
          </button>
        </div>
      )}

      {loading ? (
        <p role="status">예약 내역을 불러오는 중입니다…</p>
      ) : (
        !error && (
          <>
            <div className="customer-booking-list-heading">
              <h2>예약 내역</h2>
              <span>
                {appointments.length}건 · 한국 시간 기준
              </span>
            </div>

            <div className="appointment-list customer-appointment-list">
              {appointments.map((a) => (
                <AppointmentCard
                  key={a.id}
                  appointment={a}
                >
                  {a.status === "PENDING" && (
                    <p className="booking-caption">
                      정비소 확인 후 예약이 확정됩니다.
                    </p>
                  )}

                  {a.allowedStatuses.includes(
                    "CANCELLED",
                  ) && (
                    <div className="card-actions">
                      <button
                        className="text-button danger"
                        disabled={!!busy}
                        onClick={() => void cancel(a)}
                      >
                        {busy === a.id
                          ? "취소 중…"
                          : "예약 취소"}
                      </button>
                    </div>
                  )}
                </AppointmentCard>
              ))}
            </div>

            {!appointments.length && (
              <div className="empty-state customer-booking-empty">
                <CalendarPlus size={32} />
                <h2>이 달에는 예약이 없습니다</h2>
                <p>
                  차량과 정비 항목을 선택해 방문 일정을
                  예약해 보세요.
                </p>

                <Link
                  className="button primary"
                  href="/appointments/new"
                >
                  정비 예약하기
                </Link>
              </div>
            )}
          </>
        )
      )}
    </div>
  );
}
