"use client";
import { FormEvent, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "./auth-provider";
import { api, errorText, won } from "@/lib/api";
import { Appointment, seoulToday, statusLabel } from "@/lib/appointments";
import {
  Mechanic,
  Part,
  Work,
  WorkDetail,
  workLabel,
  workItemLabel,
  workItemTransitions,
  workItemActionLabel,
  WorkOrderItemStatus,
  localTime,
  remaining,
} from "@/lib/work";
import { useWorkCommand } from "./work-command";
import { Invoice, decimalNumber } from "@/lib/billing";
import { requestTossPayment, tossPaymentError } from "@/lib/toss-payment";

type PickerPart = Pick<Part, "id" | "sku" | "name" | "unit"> & {
  active?: boolean;
  quantity?: Part["quantity"];
};

export function WorkOrders({
  admin = false,
  mechanic = false,
}: {
  admin?: boolean;
  mechanic?: boolean;
}) {
  const { user } = useAuth();
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState("active");
  const [orders, setOrders] = useState<Work[]>([]);
  const [mechanics, setMechanics] = useState<Mechanic[]>([]);
  const [parts, setParts] = useState<PickerPart[]>([]);
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [selected, setSelected] = useState("");
  const [requestedWorkOrderId, setRequestedWorkOrderId] = useState("");
  const [detail, setDetail] = useState<WorkDetail | null>(null);
  const [error, setError] = useState("");
  const [formError, setFormError] = useState("");
  const [loading, setLoading] = useState(false);
  const [date, setDate] = useState(seoulToday);
  const [bookings, setBookings] = useState<Appointment[]>([]);
  const [bookingError, setBookingError] = useState("");
  const [bookingLoading, setBookingLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  const [itemTargets, setItemTargets] = useState<Record<string, string>>({});
  const [itemReasons, setItemReasons] = useState<Record<string, string>>({});
  const [showCancelForm, setShowCancelForm] = useState(false);
  const [payingInvoice, setPayingInvoice] = useState("");
  const base = admin
    ? "/api/admin/work-orders"
    : mechanic
      ? "/api/mechanic/work-orders"
      : "/api/work-orders";
  const permitted =
    !!user &&
    (admin
      ? user.role === "ADMIN"
      : mechanic
        ? user.role === "MECHANIC"
        : user.role === "CUSTOMER" || user.role === "ADMIN");
  const reload = useCallback(async () => {
    setRevision((n) => n + 1);
  }, []);
  const command = useWorkCommand(reload);

  useEffect(() => {
    const id = new URLSearchParams(window.location.search).get("workOrderId");
    if (id) setRequestedWorkOrderId(id);
  }, []);
  useEffect(() => {
    if (!permitted) return;
    const c = new AbortController();
    setLoading(true);
    setError("");
    setDetail(null);
    Promise.all([
      api<Work[]>(base, { signal: c.signal }),
      admin
        ? api<Mechanic[]>("/api/admin/mechanics", { signal: c.signal })
        : Promise.resolve([]),
      admin
        ? api<PickerPart[]>("/api/admin/parts", { signal: c.signal })
        : mechanic
          ? api<PickerPart[]>("/api/mechanic/parts", { signal: c.signal })
          : Promise.resolve([]),
      admin
        ? api<Invoice[]>("/api/admin/billing/invoices", { signal: c.signal })
        : Promise.resolve<Invoice[]>([]),
    ])
      .then(([w, m, p, invoiceRows]) => {
        if (!c.signal.aborted) {
          setOrders(w);
          setMechanics(m);
          setParts(p);
          setInvoices(invoiceRows);
        }
      })
      .catch((e) => {
        if (!c.signal.aborted) setError(errorText(e));
      })
      .finally(() => {
        if (!c.signal.aborted) setLoading(false);
      });
    return () => c.abort();
  }, [base, admin, mechanic, permitted, revision]);
  useEffect(() => {
    if (!requestedWorkOrderId || !permitted) return;

    if (orders.some((order) => order.id === requestedWorkOrderId)) {
      setQuery("");
      setFilter("all");
      setSelected(requestedWorkOrderId);
    }
  }, [requestedWorkOrderId, orders, permitted]);

  useEffect(() => {
    setDetail(null);
    if (!selected || !permitted) return;
    const c = new AbortController();
    api<WorkDetail>(`${base}/${selected}`, { signal: c.signal })
      .then((d) => {
        if (!c.signal.aborted) {
          setDetail(d);
          setItemTargets({});
          setItemReasons({});
          setShowCancelForm(false);
        }
      })
      .catch((e) => {
        if (!c.signal.aborted) setError(errorText(e));
      });
    return () => c.abort();
  }, [base, selected, permitted, revision]);
  useEffect(() => {
    if (!admin || !permitted) return;
    const c = new AbortController();
    setBookings([]);
    setBookingError("");
    setBookingLoading(true);
    api<Appointment[]>(`/api/admin/appointments?from=${date}&to=${date}`, {
      signal: c.signal,
    })
      .then((a) => {
        if (!c.signal.aborted) setBookings(a);
      })
      .catch((e) => {
        if (!c.signal.aborted) setBookingError(errorText(e));
      })
      .finally(() => {
        if (!c.signal.aborted) setBookingLoading(false);
      });
    return () => c.abort();
  }, [date, admin, permitted, revision]);
  const fields = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    return new FormData(e.currentTarget);
  };
  if (!permitted)
    return (
      <p role="alert">
        {admin || mechanic ? "이 화면을 이용할 권한이 없습니다." : "로그인이 필요합니다."}
      </p>
    );
  const category = (w: Work) =>
    w.released_at
      ? "released"
      : w.status === "COMPLETED"
        ? "completed"
        : w.status === "CANCELLED"
          ? "cancelled"
          : "active";
  const stages = [
    ["active", "진행 중"],
    ["completed", "정비 완료·출고 대기"],
    ["released", "출고 완료"],
    ["cancelled", "취소"],
    ["all", "전체"],
  ];
  const shown = orders.filter(
    (w) =>
      (filter === "all" || category(w) === filter) &&
      `${w.plate_number} ${w.vehicle_label} ${w.mechanic_name ?? ""}`
        .toLowerCase()
        .includes(query.toLowerCase()),
  );
  const disabled = command.blocked || loading || !!error;
  const activeInvoice = detail
    ? invoices.find(
        (invoice) =>
          invoice.work_order_id === detail.id && invoice.status === "OPEN",
      )
    : undefined;
  const outstanding = activeInvoice
    ? decimalNumber(activeInvoice.total) - decimalNumber(activeInvoice.paid)
    : 0;

  async function payWithToss(invoiceId: string) {
    setPayingInvoice(invoiceId);
    setError("");
    try {
      await requestTossPayment(invoiceId, true);
    } catch (reason) {
      setError(tossPaymentError(reason));
      setPayingInvoice("");
    }
  }
  const eligible = bookings.filter(
    (b) =>
      b.status === "VISITED" && !orders.some((w) => w.appointment_id === b.id),
  );
  const listLabel = (w: Work) => {
    if (w.released_at) return "출고 완료";
    if (w.status === "RECEIVED") return "입고 완료 · 작업 시작 필요";
    if (w.status === "IN_PROGRESS") return "작업 중";
    if (w.status === "WAITING_PARTS") return "부품 대기 · 작업 재개 필요";
    if (w.status === "COMPLETED") return "정비 완료 · 출고 대기";
    return "취소됨";
  };
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">SERVICE WORK</span>
          <h1>{admin ? "정비 작업 관리" : mechanic ? "내 담당 작업" : "내 정비 작업"}</h1>
          <p>
            {admin
              ? "입고부터 정비 진행, 부품 대기, 완료와 출고까지 현재 작업 상황을 한눈에 확인하세요."
              : mechanic
                ? "배정된 차량과 정비 항목을 확인하고 작업을 처리하세요."
                : "입고부터 완료까지 작업 상태와 부품 사용 내역을 확인하세요."}
          </p>
        </div>
        <button
          className="button secondary"
          onClick={() => void reload()}
          disabled={command.busy}
        >
          새로고침
        </button>
      </div>
      {command.feedback}
      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}
      {formError && (
        <div className="error" role="alert">
          {formError}
        </div>
      )}

      {mechanic && (
        <section
          className="mechanic-work-overview"
          aria-label="내 작업 현황"
        >
          <div>
            <span>내 작업</span>
            <strong>{orders.length}</strong>
          </div>

          <div>
            <span>입고</span>
            <strong>
              {orders.filter(
                (w) => !w.released_at && w.status === "RECEIVED",
              ).length}
            </strong>
          </div>

          <div>
            <span>작업 중</span>
            <strong>
              {orders.filter(
                (w) => !w.released_at && w.status === "IN_PROGRESS",
              ).length}
            </strong>
          </div>

          <div>
            <span>부품 대기</span>
            <strong>
              {orders.filter(
                (w) => !w.released_at && w.status === "WAITING_PARTS",
              ).length}
            </strong>
          </div>
        </section>
      )}

      {admin && (
        <section
          className="admin-work-overview"
          aria-label="현재 정비 작업 현황"
        >
          <div>
            <span>입고 대기</span>
            <strong>
              {orders.filter((w) => !w.released_at && w.status === "RECEIVED").length}
            </strong>
          </div>

          <div>
            <span>작업 중</span>
            <strong>
              {orders.filter(
                (w) => !w.released_at && w.status === "IN_PROGRESS",
              ).length}
            </strong>
          </div>

          <div className="attention">
            <span>부품 대기</span>
            <strong>
              {orders.filter(
                (w) => !w.released_at && w.status === "WAITING_PARTS",
              ).length}
            </strong>
          </div>

          <div>
            <span>출고 대기</span>
            <strong>
              {orders.filter(
                (w) => !w.released_at && w.status === "COMPLETED",
              ).length}
            </strong>
          </div>

          <div>
            <span>출고 완료</span>
            <strong>{orders.filter((w) => !!w.released_at).length}</strong>
          </div>

          <div>
            <span>전체 작업</span>
            <strong>{orders.length}</strong>
          </div>
        </section>
      )}

      {admin && (
        <details className="work-panel admin-intake-panel" open>
          <summary>예약 확인 및 입고 처리</summary>
          <p>
            날짜를 선택하면 대기·확정 예약도 표시됩니다. 예약 확정 → 방문 처리 →
            입고 등록 순으로 진행하세요. 확정 예약은 예약일 전에도 방문 처리할
            수 있으며 종료 시각 전까지 가능합니다.
          </p>
          <label>
            예약 날짜
            <input
              type="date"
              value={date}
              disabled={disabled}
              onChange={(e) => {
                if (e.target.value) setDate(e.target.value);
              }}
            />
          </label>
          {bookingError && <p className="error">{bookingError}</p>}
          {bookingLoading ? (
            <p role="status">예약을 불러오는 중입니다…</p>
          ) : (
            !bookingError && (
              <>
                <p>
                  선택 날짜 예약 {bookings.length}건 · 입고 가능{" "}
                  {eligible.length}건
                </p>
                {!bookings.length && (
                  <p>
                    해당 날짜에 등록된 예약이 없습니다.{" "}
                    <Link href="/admin/appointments">
                      예약 캘린더에서 날짜와 예약을 확인하세요.
                    </Link>
                  </p>
                )}
                {bookings.map((b) => {
                  const received = orders.some(
                    (w) => w.appointment_id === b.id,
                  );
                  return (
                    <article className="work-movement" key={b.id}>
                      <strong>
                        {b.customerName} · {b.plateNumber} ·{" "}
                        {b.startsAt.slice(11, 16)}
                      </strong>
                      <p>
                        {statusLabel[b.status]}
                        {received ? " · 입고 등록 완료" : ""}
                      </p>
                      {!received && b.status === "PENDING" && (
                        <p>입고 전에 예약 확정이 필요합니다.</p>
                      )}
                      {!received &&
                        b.status === "CONFIRMED" &&
                        !b.allowedStatuses.includes("VISITED") && (
                          <p>
                            예약 종료 시간이 지났습니다. 방문 처리 가능 상태를
                            확인해 주세요.
                          </p>
                        )}
                      {!received && b.status === "VISITED" && (
                        <p>아래 입고 등록에서 선택할 수 있습니다.</p>
                      )}
                      {!received && b.allowedStatuses.includes("CONFIRMED") && (
                        <button
                          type="button"
                          className="button secondary"
                          disabled={disabled}
                          onClick={() =>
                            void command.run(
                              `/api/admin/appointments/${b.id}/status`,
                              { status: "CONFIRMED" },
                              "PATCH",
                            )
                          }
                        >
                          예약 확정
                        </button>
                      )}
                      {!received && b.allowedStatuses.includes("VISITED") && (
                        <button
                          type="button"
                          className="button primary"
                          disabled={disabled}
                          onClick={() =>
                            void command.run(
                              `/api/admin/appointments/${b.id}/status`,
                              { status: "VISITED" },
                              "PATCH",
                            )
                          }
                        >
                          방문 처리
                        </button>
                      )}
                    </article>
                  );
                })}
              </>
            )
          )}
          <form
            onSubmit={(e) => {
              const f = fields(e);
              void command.run(`${base}/from-appointment`, {
                appointmentId: f.get("appointment"),
                receivedMileage: Number(f.get("mileage")),
                mechanicId: f.get("mechanic") || null,
                notes: f.get("notes"),
              });
            }}
          >
            <fieldset
              disabled={
                disabled || !!bookingError || bookingLoading || !eligible.length
              }
            >
              <label>
                입고 가능한 방문 예약
                <select
                  key={`${date}-${revision}`}
                  name="appointment"
                  required
                  defaultValue=""
                >
                  <option value="" disabled>
                    {eligible.length
                      ? "예약 선택"
                      : "방문 처리된 미입고 예약이 없습니다"}
                  </option>
                  {eligible.map((b) => (
                    <option key={b.id} value={b.id}>
                      {b.customerName} · {b.plateNumber} ·{" "}
                      {b.startsAt.slice(11, 16)}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                입고 주행거리 (km)
                <input
                  name="mileage"
                  type="number"
                  min="0"
                  max="9999999"
                  step="1"
                  required
                />
              </label>
              <label>
                담당 정비사
                <select name="mechanic" defaultValue="">
                  <option value="">
                    미배정으로 입고
                  </option>
                  {mechanics
                    .filter((m) => m.active)
                    .map((m) => (
                      <option key={m.id} value={m.id}>
                        {m.name}
                      </option>
                    ))}
                </select>
              </label>
              <label>
                요청사항
                <textarea name="notes" maxLength={1000} />
              </label>
              <button className="button primary">입고 등록</button>
            </fieldset>
          </form>
          {!mechanics.some((m) => m.active) && (
            <p>
              활성 정비사가 없어도 미배정으로 입고할 수 있습니다. <Link href="/admin/mechanics">
                정비사·계정 관리
              </Link>
            </p>
          )}
        </details>
      )}
      <div
        className={
          admin
            ? "management-toolbar admin-management-toolbar"
            : mechanic
              ? "management-toolbar mechanic-management-toolbar"
              : "management-toolbar"
        }
      >
        <div className="management-tabs" aria-label="작업 상태 필터">
          {stages.map(([key, label]) => (
            <button
              className={filter === key ? "button primary" : "button secondary"}
              key={key}
              aria-pressed={filter === key}
              onClick={() => setFilter(key)}
            >
              {label} ·{" "}
              {
                orders.filter((w) => key === "all" || category(w) === key)
                  .length
              }
            </button>
          ))}
        </div>
        <label>
          작업 검색
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={
              mechanic
                ? "차량번호 또는 차종"
                : "차량번호, 차종, 담당 정비사"
            }
          />
        </label>
      </div>
      {loading ? (
        <p role="status">작업 목록을 불러오는 중입니다…</p>
      ) : (
        <div
          className={
            admin
              ? "work-layout admin-work-layout"
              : mechanic
                ? "work-layout mechanic-work-layout"
                : "work-layout"
          }
        >
          <section
            aria-label="작업 목록"
            className={
              admin
                ? "work-list management-list admin-management-list"
                : mechanic
                  ? "work-list management-list mechanic-management-list"
                  : "work-list management-list"
            }
          >
            {shown.map((w) => (
              <button
                key={w.id}
                disabled={command.busy}
                aria-pressed={selected === w.id}
                className={`work-panel work-select ${selected === w.id ? "selected-work" : ""}`}
                onClick={() => setSelected(w.id)}
              >
                <span className={`work-stage stage-${category(w)}`}>
                  {listLabel(w)}
                </span>
                {w.released_at && (
                  <span className="work-release-time">
                    출고 {localTime(w.released_at)}
                  </span>
                )}

                <strong className="work-list-vehicle">
                  {w.vehicle_label}
                </strong>

                <span className="work-list-plate">
                  {w.plate_number}
                </span>

                <span className="work-list-received">
                  입고 {localTime(w.received_at)} ·{" "}
                  {w.received_mileage.toLocaleString()} km
                </span>

                <span className="work-list-mechanic">
                  담당 {w.mechanic_name ?? "미배정"}
                </span>
              </button>
            ))}
            {!shown.length && !error && (
              <p className="empty-state">
                선택한 조건의 작업이 없습니다. 다른 상태나 전체 목록을
                확인하세요.
              </p>
            )}
          </section>
          <section aria-label="작업 상세">
            {detail ? (
              <div
                className={
                  admin
                    ? "work-panel admin-work-detail"
                    : mechanic
                      ? "work-panel mechanic-work-detail"
                      : "work-panel"
                }
              >
                <h2 className="work-detail-title">
                  {detail.plate_number} · {workLabel[detail.status]}
                </h2>
                <p>
                  입고 {detail.received_mileage.toLocaleString()} km · 담당{" "}
                  {detail.mechanic_name ?? "미배정"}
                </p>
                <p className="booking-notes">{detail.notes}</p>

                {detail.status !== "CANCELLED" ? (
                  <div className="workflow-rail" aria-label="작업 진행 단계">
                    {[
                      ["RECEIVED", "입고 완료"],
                      ["IN_PROGRESS", "작업 진행"],
                      ["COMPLETED", "정비 완료"],
                      ["RELEASED", "출고"],
                    ].map(([step, label], index) => {
                      const current = detail.released_at
                        ? 3
                        : detail.status === "RECEIVED"
                          ? 0
                          : detail.status === "COMPLETED"
                            ? 2
                            : 1;
                      return (
                        <div
                          className={`workflow-rail-step ${
                            index < current ? "complete" : ""
                          } ${index === current ? "current" : ""} ${
                            detail.status === "WAITING_PARTS" && index === 1
                              ? "paused"
                              : ""
                          }`}
                          key={step}
                        >
                          <span className="workflow-rail-dot" />
                          <strong>{label}</strong>
                          {detail.status === "WAITING_PARTS" && index === 1 && (
                            <small>부품 대기</small>
                          )}
                        </div>
                      );
                    })}
                  </div>
                ) : (
                  <div className="workflow-cancelled-banner">
                    <strong>취소된 작업</strong>
                    <span>정상 작업 흐름과 분리되어 있습니다.</span>
                  </div>
                )}

                {admin && (
                  <div className="admin-work-facts">
                    <div>
                      <span>차량</span>
                      <strong>{detail.vehicle_label}</strong>
                    </div>

                    <div>
                      <span>차량번호</span>
                      <strong>{detail.plate_number}</strong>
                    </div>

                    <div>
                      <span>담당 정비사</span>
                      <strong>{detail.mechanic_name ?? "미배정"}</strong>
                    </div>

                    <div>
                      <span>입고 주행거리</span>
                      <strong>
                        {detail.received_mileage.toLocaleString()} km
                      </strong>
                    </div>

                    <div>
                      <span>입고 시각</span>
                      <strong>{localTime(detail.received_at)}</strong>
                    </div>

                    <div>
                      <span>정비 항목</span>
                      <strong>{detail.items.length}건</strong>
                    </div>
                  </div>
                )}

                {mechanic && (
                  <div className="mechanic-work-facts">
                    <div>
                      <span>차량</span>
                      <strong>{detail.vehicle_label}</strong>
                    </div>

                    <div>
                      <span>차량번호</span>
                      <strong>{detail.plate_number}</strong>
                    </div>

                    <div>
                      <span>주행거리</span>
                      <strong>
                        {detail.received_mileage.toLocaleString()} km
                      </strong>
                    </div>

                    <div>
                      <span>정비 항목</span>
                      <strong>{detail.items.length}건</strong>
                    </div>
                  </div>
                )}

                {detail.status === "RECEIVED" && (admin || mechanic) && (
                  <section className="workflow-cta-panel">
                    <div>
                      <strong>입고가 완료되었습니다.</strong>
                      <p>정비 항목 {detail.items.length}건을 확인하고 작업을 시작하세요.</p>
                    </div>
                    <button
                      className="button primary"
                      disabled={disabled}
                      onClick={() =>
                        void command.run(
                          `${base}/${detail.id}/status`,
                          { status: "IN_PROGRESS" },
                          "PATCH",
                        )
                      }
                    >
                      작업 시작 →
                    </button>
                  </section>
                )}

                {detail.status === "IN_PROGRESS" && (
                  <section className="workflow-cta-panel">
                    <div>
                      <strong>작업 진행 중</strong>
                      <p>
                        항목 진행률 {detail.items.filter((i) => i.status === "COMPLETED" || i.status === "SKIPPED").length} / {detail.items.length}
                      </p>
                    </div>
                    {(admin || mechanic) && (
                      <div className="workflow-cta-actions">
                        <button
                          className="button primary"
                          disabled={
                            disabled ||
                            detail.items.some(
                              (i) => i.status !== "COMPLETED" && i.status !== "SKIPPED",
                            )
                          }
                          title={
                            detail.items.some(
                              (i) => i.status !== "COMPLETED" && i.status !== "SKIPPED",
                            )
                              ? `남은 작업 ${detail.items.filter((i) => i.status !== "COMPLETED" && i.status !== "SKIPPED").length}개를 완료하거나 건너뜀 처리해 주세요.`
                              : undefined
                          }
                          onClick={() =>
                            void command.run(
                              `${base}/${detail.id}/status`,
                              { status: "COMPLETED" },
                              "PATCH",
                            )
                          }
                        >
                          정비 작업 완료 →
                        </button>
                        <button
                          className="button secondary"
                          disabled={disabled}
                          onClick={() =>
                            void command.run(
                              `${base}/${detail.id}/status`,
                              { status: "WAITING_PARTS" },
                              "PATCH",
                            )
                          }
                        >
                          작업 일시정지
                        </button>
                      </div>
                    )}
                    {detail.items.some(
                      (i) => i.status !== "COMPLETED" && i.status !== "SKIPPED",
                    ) && (
                      <p className="workflow-cta-help">
                        남은 작업 {detail.items.filter((i) => i.status !== "COMPLETED" && i.status !== "SKIPPED").length}개를 완료하거나 건너뜀 처리해 주세요.
                      </p>
                    )}
                  </section>
                )}

                {detail.status === "WAITING_PARTS" && (
                  <section className="workflow-waiting-banner">
                    <div>
                      <strong>부품 대기로 작업이 일시정지되었습니다.</strong>
                      <p>부품 준비가 끝나면 작업을 재개하세요.</p>
                    </div>
                    {(admin || mechanic) && (
                      <button
                        className="button primary"
                        disabled={disabled}
                        onClick={() =>
                          void command.run(
                            `${base}/${detail.id}/status`,
                            { status: "IN_PROGRESS" },
                            "PATCH",
                          )
                        }
                      >
                        작업 재개
                      </button>
                    )}
                  </section>
                )}

                {detail.released_at ? (
                  <div className="notice">
                    <strong>출고 완료</strong> · {localTime(detail.released_at)}
                  </div>
                ) : (
                  detail.status === "COMPLETED" && (
                    <div className="info-note">
                      <strong>정비 완료 · 출고 대기</strong>
                      {mechanic ? (
                        <p>정비 작업이 완료되었습니다. 정산·결제·출고는 관리자가 처리합니다.</p>
                      ) : admin ? (
                        !activeInvoice ? (
                          <div className="workflow-billing-actions">
                            <p>정산 명세를 발행한 뒤 결제와 출고를 진행하세요.</p>
                            <Link
                              className="button primary"
                              href={`/admin/billing?workOrderId=${detail.id}`}
                            >
                              정산 명세 발행
                            </Link>
                          </div>
                        ) : outstanding > 0 ? (
                          <div className="workflow-billing-actions">
                            <p>
                              정산금액 {won(decimalNumber(activeInvoice.total))} · 미수금{" "}
                              {won(outstanding)}
                            </p>
                            <div className="workflow-cta-actions">
                              <button
                                className="button primary"
                                disabled={disabled || !!payingInvoice}
                                onClick={() => void payWithToss(activeInvoice.id)}
                              >
                                {payingInvoice === activeInvoice.id
                                  ? "결제창 준비 중…"
                                  : "Toss 결제"}
                              </button>
                              <Link
                                className="button secondary"
                                href={`/admin/billing?invoiceId=${activeInvoice.id}`}
                              >
                                현장 수납·정산 화면
                              </Link>
                            </div>
                            <p className="workflow-cta-help">
                              미수금 결제를 완료해야 차량을 출고할 수 있습니다.
                            </p>
                          </div>
                        ) : (
                          <div className="workflow-billing-actions">
                            <p>
                              <strong>결제 완료</strong> · {won(decimalNumber(activeInvoice.total))}
                            </p>
                            <div className="workflow-cta-actions">
                              <Link
                                className="button secondary"
                                href={`/admin/billing?invoiceId=${activeInvoice.id}`}
                              >
                                정산 내역 확인
                              </Link>
                              <button
                                className="button primary"
                                disabled={disabled}
                                onClick={() => {
                                  if (
                                    window.confirm(
                                      "결제 완료를 확인했습니다. 차량을 고객에게 인도하고 출고 완료 시각을 기록할까요?",
                                    )
                                  )
                                    void command.run(
                                      `${base}/${detail.id}/release`,
                                      {},
                                    );
                                }}
                              >
                                차량 출고 처리
                              </button>
                            </div>
                          </div>
                        )
                      ) : (
                        <p>관리자가 정산 명세와 결제를 확인한 뒤 차량을 출고합니다.</p>
                      )}
                    </div>
                  )
                )}
                {(() => {
                  const completed = detail.items.filter(
                    (i) => i.status === "COMPLETED",
                  ).length;
                  const skipped = detail.items.filter(
                    (i) => i.status === "SKIPPED",
                  ).length;
                  const waitingParts = detail.items.filter(
                    (i) => i.status === "WAITING_PARTS",
                  ).length;
                  const active = detail.items.length - completed - skipped - waitingParts;
                  return (
                    <p
                      className={
                        admin
                          ? "admin-item-summary"
                          : mechanic
                            ? "mechanic-item-summary"
                            : "muted"
                      }
                    >
                      정비 항목 · 완료 {completed} · 건너뜀 {skipped} · 부품 대기 {waitingParts} · 진행/대기 {active}
                    </p>
                  );
                })()}
                <h3>정비 항목</h3>
                <ul className="work-items">
                  {detail.items.map((i) => (
                    <li key={i.id}>
                      <span>
                        {i.name}
                        {i.quantity > 1 ? ` × ${i.quantity}` : ""}
                        {" · "}
                        단위 공임 {won(i.labor_price)}
                        {i.quantity > 1
                          ? ` · 공임 합계 ${won(i.labor_price * i.quantity)}`
                          : ""}
                      </span>
                      <span>
                        {workItemLabel[i.status]}
                        {i.status === "SKIPPED" && i.skip_reason
                          ? ` · ${i.skip_reason}`
                          : ""}
                      </span>
                      {(admin || mechanic) && detail.status === "IN_PROGRESS" && (
                        <form
                          className="work-item-action"
                          onSubmit={(e) => {
                            e.preventDefault();
                            const target = itemTargets[i.id] as WorkOrderItemStatus | undefined;
                            if (!target) return;
                            const reason = itemReasons[i.id] ?? "";
                            if (target === "SKIPPED" && !reason.trim()) {
                              setFormError("건너뛴 사유를 입력해 주세요.");
                              return;
                            }
                            setFormError("");
                            void command.run(
                              `${base}/${detail.id}/items/${i.id}`,
                              target === "SKIPPED"
                                ? { status: target, reason: reason.trim() }
                                : { status: target },
                              "PATCH",
                            );
                          }}
                        >
                          <fieldset
                            className="work-item-action-fields"
                            disabled={disabled}
                          >
                            <label>
                              다음 처리
                              <select
                                value={itemTargets[i.id] ?? ""}
                                onChange={(e) =>
                                  setItemTargets((current) => ({
                                    ...current,
                                    [i.id]: e.target.value,
                                  }))
                                }
                              >
                                <option value="">
                                  처리 선택
                                </option>
                                {workItemTransitions[i.status].map((status) => (
                                  <option key={status} value={status}>
                                    {workItemActionLabel(i.status, status)}
                                  </option>
                                ))}
                              </select>
                            </label>
                            {itemTargets[i.id] === "SKIPPED" && (
                              <label className="work-skip-reason">
                                건너뜀 사유
                                <input
                                  value={itemReasons[i.id] ?? ""}
                                  maxLength={900}
                                  onChange={(e) =>
                                    setItemReasons((current) => ({
                                      ...current,
                                      [i.id]: e.target.value,
                                    }))
                                  }
                                />
                              </label>
                            )}
                            <button
                              className="button secondary work-apply-button"
                              disabled={!itemTargets[i.id]}
                            >
                              적용
                            </button>
                          </fieldset>
                        </form>
                      )}
                    </li>
                  ))}
                </ul>
                {mechanic && detail.status !== "COMPLETED" && detail.status !== "CANCELLED" && (
                  <section className="mechanic-shortage-section">
                    <h3>부품 부족 신고</h3>
                    <p>재고가 부족해 작업을 진행할 수 없을 때 관리자에게 신고합니다.</p>
                    <form
                      onSubmit={(e) => {
                        const f = fields(e);
                        void command.run(`${base}/${detail.id}/shortages`, {
                          workOrderItemId: f.get("workOrderItemId"),
                          partId: f.get("partId"),
                          requestedQuantity: f.get("requestedQuantity"),
                          reason: f.get("shortageReason") || null,
                        });
                      }}
                    >
                      <fieldset disabled={disabled}>
                        <label>
                          정비 항목
                          <select name="workOrderItemId" required defaultValue="">
                            <option value="" disabled>항목 선택</option>
                            {detail.items.map((item) => (
                              <option key={item.id} value={item.id}>{item.name}</option>
                            ))}
                          </select>
                        </label>
                        <label>
                          부족 부품
                          <select name="partId" required defaultValue="">
                            <option value="" disabled>부품 선택</option>
                            {parts.filter((part) => part.active !== false).map((part) => (
                              <option key={part.id} value={part.id}>{part.name} · {part.sku} · {part.unit}</option>
                            ))}
                          </select>
                        </label>
                        <label>
                          필요한 수량
                          <input name="requestedQuantity" type="number" min="0.001" max="99999999999.999" step="0.001" required />
                        </label>
                        <label>
                          신고 사유 (선택)
                          <input name="shortageReason" maxLength={500} />
                        </label>
                        <button className="button secondary">부품 부족 신고</button>
                      </fieldset>
                    </form>
                  </section>
                )}
                {(admin || mechanic) &&
                  ["RECEIVED", "IN_PROGRESS", "WAITING_PARTS"].includes(detail.status) && (
                  <>
                    {admin && <form
                      onSubmit={(e) => {
                        const f = fields(e);
                        void command.run(
                          `${base}/${detail.id}/assignment`,
                          { mechanicId: f.get("mechanic") || null },
                          "PATCH",
                        );
                      }}
                    >
                      <fieldset disabled={disabled}>
                        <label>
                          담당 변경
                          <select
                            name="mechanic"
                            defaultValue={detail.mechanic_id ?? undefined}
                            key={detail.mechanic_id ?? "unassigned"}
                          >
                            <option value="">미배정</option>
                            {mechanics
                              .filter(
                                (m) => m.active || m.id === detail.mechanic_id,
                              )
                              .map((m) => (
                                <option
                                  value={m.id}
                                  key={m.id}
                                  disabled={!m.active}
                                >
                                  {m.name}
                                </option>
                              ))}
                          </select>
                        </label>
                        <button className="button secondary">담당 배정</button>
                      </fieldset>
                    </form>}
                  </>
                )}
                {(admin || mechanic) &&
                  !["COMPLETED", "CANCELLED"].includes(detail.status) && (
                  <section className="workflow-other-actions">
                    <h3>기타 작업</h3>
                    {!showCancelForm ? (
                      <button
                        className="button danger"
                        disabled={disabled}
                        onClick={() => setShowCancelForm(true)}
                      >
                        작업 취소
                      </button>
                    ) : (
                      <form
                        className="work-order-progress-form"
                        onSubmit={(e) => {
                          e.preventDefault();
                          const f = fields(e);
                          const reason = String(f.get("reason") ?? "").trim();
                          if (!reason) {
                            setFormError("취소 사유를 입력해 주세요.");
                            return;
                          }
                          setFormError("");
                          void command.run(
                            `${base}/${detail.id}/status`,
                            { status: "CANCELLED", reason },
                            "PATCH",
                          );
                        }}
                      >
                        <fieldset className="work-order-progress-fields" disabled={disabled}>
                          <label>
                            취소 사유
                            <input name="reason" maxLength={900} required />
                          </label>
                          <button className="button danger">작업 취소</button>
                        </fieldset>
                      </form>
                    )}
                  </section>
                )}
                {admin && (
                  <section>
                    <h3>정비 항목별 준비 부품</h3>
                    <p>
                      연결된 부품 종류입니다. 차종별 규격·실제 사용량을
                      확인하세요. 자동으로 사용 처리하지 않습니다.
                    </p>
                    {detail.suggested_parts?.length ? (
                      <ul>
                        {detail.suggested_parts.map((p) => (
                          <li key={p.id}>
                            {p.name} · 재고 {String(p.quantity)} {p.unit}
                            {!p.active
                              ? " · 비활성"
                              : Number(p.quantity) === 0
                                ? " · 입고 필요"
                                : ""}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p>
                        연결된 부품이 없거나 부품이 필요 없는 점검 항목입니다.
                      </p>
                    )}
                  </section>
                )}
                {(admin || mechanic) && detail.status === "IN_PROGRESS" && (
                  <form
                    className={mechanic ? "mechanic-parts-use" : undefined}
                    key={`${detail.id}-${revision}-use`}
                    onSubmit={(e) => {
                      const f = fields(e);
                      setFormError("");
                      const lines = parts
                        .filter((p) => p.active !== false)
                        .map((p) => ({
                          partId: p.id,
                          quantity: String(f.get(p.id) || ""),
                        }))
                        .filter((l) => l.quantity.trim() !== "");
                      if (!lines.length) {
                        setFormError("사용할 부품의 수량을 입력해 주세요.");
                        return;
                      }
                      void command.run(`${base}/${detail.id}/parts/use`, {
                        lines,
                        reason: f.get("reason"),
                      });
                    }}
                  >
                    <h3>부품 사용</h3>
                    <p>
                      여러 부품을 한 번에 사용하면 모두 처리되거나 모두
                      취소됩니다. 정비 항목과 연결된 부품을 먼저 표시합니다.
                    </p>
                    <fieldset disabled={disabled}>
                      {parts
                        .filter((p) => p.active !== false)
                        .sort(
                          (a, b) =>
                            Number(
                              detail.suggested_parts?.some(
                                (p) => p.id === b.id,
                              ),
                            ) -
                            Number(
                              detail.suggested_parts?.some(
                                (p) => p.id === a.id,
                              ),
                            ),
                        )
                        .map((p) => (
                          <label key={p.id}>
                            {detail.suggested_parts?.some((s) => s.id === p.id)
                              ? "[준비 부품] "
                              : ""}
                            {p.name} · {admin ? `재고 ${String(p.quantity)} ${p.unit}` : `${p.sku} · ${p.unit}`}
                            <input
                            name={p.id}
                            type="number"
                            min={p.unit === "EA" ? "1" : "0.001"}
                            max="99999999999.999"
                            step={p.unit === "EA" ? "1" : "0.001"}
                            placeholder="사용 수량"
                          />
                          </label>
                        ))}
                      <label>
                        사용 사유
                        <input name="reason" required maxLength={500} />
                      </label>
                      <button
                        className="button primary"
                        disabled={!parts.some((p) => p.active !== false)}
                      >
                        선택 부품 사용
                      </button>
                    </fieldset>
                  </form>
                )}
                <h3>부품 사용·반환 이력</h3>
                <p>
                  취소 시 자동 복원되지 않습니다. 실제 회수한 미사용 부품만 반환
                  처리하세요.
                </p>
                {detail.movements.map((m) => (
                  <article className="work-movement" key={m.id}>
                    <strong>
                      {m.kind === "USE" ? "사용" : "반환"} · {m.part_name} ·{" "}
                      {String(m.quantity)} {m.unit}
                    </strong>
                    <p>
                      {localTime(m.created_at)} · {m.reason}
                    </p>
                    {(admin || mechanic) &&
                      m.kind === "USE" &&
                      Number(remaining(m, detail.movements)) > 0 && (
                        <form
                          onSubmit={(e) => {
                            const f = fields(e);
                            void command.run(
                              `${base}/${detail.id}/parts/return`,
                              {
                                originalUseId: m.id,
                                quantity: f.get("quantity"),
                                reason: f.get("reason"),
                              },
                            );
                          }}
                        >
                          <fieldset disabled={disabled}>
                            <label>
                              실제 반환 수량 (최대{" "}
                              {remaining(m, detail.movements)} {m.unit})
                              <input
                                name="quantity"
                                type="number"
                                min={m.unit === "EA" ? "1" : "0.001"}
                                step={m.unit === "EA" ? "1" : "0.001"}
                                max={remaining(m, detail.movements)}
                                required
                              />
                            </label>
                            <label>
                              반환 사유
                              <input name="reason" required maxLength={500} />
                            </label>
                            <button className="button secondary">
                              실물 반환 기록
                            </button>
                          </fieldset>
                        </form>
                      )}
                  </article>
                ))}
                {!detail.movements.length && <p>부품 사용 내역이 없습니다.</p>}
                <h3>작업 이력</h3>
                <ol>
                  {detail.events.map((e, i) => (
                    <li key={i}>
                      {localTime(e.created_at)} · {e.detail}
                    </li>
                  ))}
                </ol>
              </div>
            ) : (
              <p>
                {selected
                  ? "작업 상세를 불러오는 중입니다…"
                  : "작업을 선택해 주세요."}
              </p>
            )}
          </section>
        </div>
      )}
    </>
  );
}
