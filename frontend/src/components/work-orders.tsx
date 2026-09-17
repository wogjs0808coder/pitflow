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
  workTransitions,
  workItemLabel,
  workItemTransitions,
  WorkOrderItemStatus,
  localTime,
  remaining,
} from "@/lib/work";
import { useWorkCommand } from "./work-command";

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
    ])
      .then(([w, m, p]) => {
        if (!c.signal.aborted) {
          setOrders(w);
          setMechanics(m);
          setParts(p);
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
  const eligible = bookings.filter(
    (b) =>
      b.status === "VISITED" && !orders.some((w) => w.appointment_id === b.id),
  );
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">SERVICE WORK</span>
          <h1>{admin ? "정비 작업 관리" : mechanic ? "내 담당 작업" : "내 정비 작업"}</h1>
          <p>입고부터 완료까지 작업 상태와 부품 사용 내역을 확인하세요.</p>
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
      {admin && (
        <details className="work-panel" open>
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
      <div className="management-toolbar">
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
            placeholder="차량번호, 차종, 담당 정비사"
          />
        </label>
      </div>
      {loading ? (
        <p role="status">작업 목록을 불러오는 중입니다…</p>
      ) : (
        <div className="work-layout">
          <section aria-label="작업 목록" className="work-list management-list">
            {shown.map((w) => (
              <button
                key={w.id}
                disabled={command.busy}
                aria-pressed={selected === w.id}
                className={`work-panel work-select ${selected === w.id ? "selected-work" : ""}`}
                onClick={() => setSelected(w.id)}
              >
                <span className={`work-stage stage-${category(w)}`}>
                  {w.released_at
                    ? "출고 완료"
                    : w.status === "COMPLETED"
                      ? "정비 완료 · 출고 대기"
                      : workLabel[w.status]}
                </span>
                {w.released_at && <span>출고 {localTime(w.released_at)}</span>}
                <strong>
                  {w.vehicle_label} · {w.plate_number}
                </strong>
                <span>
                  {localTime(w.received_at)} ·{" "}
                  {w.received_mileage.toLocaleString()} km
                </span>
                <span>담당 {w.mechanic_name ?? "미배정"}</span>
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
              <div className="work-panel">
                <h2>
                  {detail.plate_number} · {workLabel[detail.status]}
                </h2>
                <p>
                  입고 {detail.received_mileage.toLocaleString()} km · 담당{" "}
                  {detail.mechanic_name ?? "미배정"}
                </p>
                <p className="booking-notes">{detail.notes}</p>
                {detail.released_at ? (
                  <div className="notice">
                    <strong>출고 완료</strong> · {localTime(detail.released_at)}
                  </div>
                ) : (
                  detail.status === "COMPLETED" && (
                    <div className="info-note">
                      <strong>정비 완료 · 출고 대기</strong>
                      <p>
                        차량 인도를 확인한 후 출고 처리하세요. 수납은 정산
                        화면에서 별도로 확인합니다.
                      </p>
                      {admin && (
                        <>
                          <Link
                            className="button secondary"
                            href="/admin/billing"
                          >
                            정산·수납 확인
                          </Link>{" "}
                          <button
                            className="button primary"
                            disabled={disabled}
                            onClick={() => {
                              if (
                                window.confirm(
                                  "차량을 고객에게 인도했습니까? 출고 완료 시각을 기록합니다.",
                                )
                              )
                                void command.run(
                                  `${base}/${detail.id}/release`,
                                  {},
                                );
                            }}
                          >
                            출고 완료 처리
                          </button>
                        </>
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
                    <p className="muted">
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
                          <fieldset disabled={disabled}>
                            <label>
                              상태 변경
                              <select
                                value={itemTargets[i.id] ?? ""}
                                onChange={(e) =>
                                  setItemTargets((current) => ({
                                    ...current,
                                    [i.id]: e.target.value,
                                  }))
                                }
                              >
                                <option value="">현재: {workItemLabel[i.status]}</option>
                                {workItemTransitions[i.status].map((status) => (
                                  <option key={status} value={status}>
                                    {workItemLabel[status]}
                                  </option>
                                ))}
                              </select>
                            </label>
                            {itemTargets[i.id] === "SKIPPED" && (
                              <label>
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
                            <button className="button secondary" disabled={!itemTargets[i.id]}>
                              상태 변경
                            </button>
                          </fieldset>
                        </form>
                      )}
                    </li>
                  ))}
                </ul>
                {mechanic && detail.status !== "COMPLETED" && detail.status !== "CANCELLED" && (
                  <section>
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
                {(admin || mechanic) && workTransitions[detail.status].length > 0 && (
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
                    <form
                      onSubmit={(e) => {
                        const f = fields(e);
                        const status = f.get("status");
                        if (
                          status === "CANCELLED" &&
                          !window.confirm(
                            "작업을 취소합니다. 사용한 부품은 자동 반환되지 않습니다. 진행하시겠습니까?",
                          )
                        )
                          return;
                        void command.run(
                          `${base}/${detail.id}/status`,
                          { status, reason: f.get("reason") },
                          "PATCH",
                        );
                      }}
                    >
                      <fieldset disabled={disabled}>
                        <label>
                          변경할 상태
                          <select name="status" key={detail.status}>
                            {workTransitions[detail.status].map((s) => (
                              <option key={s} value={s}>
                                {workLabel[s]}
                              </option>
                            ))}
                          </select>
                        </label>
                        <label>
                          변경 사유 (취소 시 필수)
                          <input name="reason" maxLength={900} />
                        </label>
                        <button className="button primary">상태 변경</button>
                      </fieldset>
                    </form>
                  </>
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
