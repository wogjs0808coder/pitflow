"use client";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { api, errorText, won } from "@/lib/api";
import { useAuth } from "@/components/auth-provider";
import { useWorkCommand } from "@/components/work-command";
import {
  Part,
  Mechanic,
  Movement,
  milli,
  localTime,
  movementLabel,
} from "@/lib/work";

export default function PartsPage() {
  const { user } = useAuth();
  const [tab, setTab] = useState("parts");
  const [query, setQuery] = useState("");
  const [stockFilter, setStockFilter] = useState("all");
  const [parts, setParts] = useState<Part[]>([]);
  const [mechanics, setMechanics] = useState<Mechanic[]>([]);
  const [selected, setSelected] = useState("");
  const [includeArchived, setIncludeArchived] = useState(false);
  const [history, setHistory] = useState<Movement[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  const reload = useCallback(async () => {
    setRevision((n) => n + 1);
  }, []);
  const command = useWorkCommand(reload);
  useEffect(() => {
    if (user?.role !== "ADMIN") return;
    const c = new AbortController();
    setError("");
    setLoading(true);
    Promise.all([
      api<Part[]>(`/api/admin/parts?includeArchived=${includeArchived}`, {
        signal: c.signal,
      }),
      api<Mechanic[]>("/api/admin/mechanics", { signal: c.signal }),
    ])
      .then(([p, m]) => {
        if (!c.signal.aborted) {
          setParts(p);
          setMechanics(m);
        }
      })
      .catch((e) => {
        if (!c.signal.aborted) setError(errorText(e));
      })
      .finally(() => {
        if (!c.signal.aborted) setLoading(false);
      });
    return () => c.abort();
  }, [user?.role, revision, includeArchived]);
  useEffect(() => {
    setHistory([]);
    if (!selected || user?.role !== "ADMIN") return;
    const c = new AbortController();
    setHistoryLoading(true);
    api<Movement[]>(`/api/admin/parts/${selected}/movements`, {
      signal: c.signal,
    })
      .then((m) => {
        if (!c.signal.aborted) setHistory(m);
      })
      .catch((e) => {
        if (!c.signal.aborted) setError(errorText(e));
      })
      .finally(() => {
        if (!c.signal.aborted) setHistoryLoading(false);
      });
    return () => c.abort();
  }, [selected, revision, user?.role]);
  if (user?.role !== "ADMIN")
    return <p role="alert">관리자만 이용할 수 있습니다.</p>;
  const disabled = command.blocked || loading || !!error;
  const form = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    return new FormData(e.currentTarget);
  };
  const shown = parts.filter(
    (p) =>
      `${p.name} ${p.sku} ${p.description}`
        .toLowerCase()
        .includes(query.toLowerCase()) &&
      (stockFilter === "all" ||
        (stockFilter === "low"
          ? p.active &&
            !p.archived &&
            milli(p.quantity) <= milli(p.minimum_quantity)
          : !p.active && !p.archived)),
  );
  const chosen = parts.find((p) => p.id === selected);
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">PARTS & PEOPLE</span>
          <h1>부품·정비사 관리</h1>
          <p>
            새 물품은 실물 입고로 추가하고, 실제 보유 수량과 다르면 재고
            보정으로 수정하세요. 기본 부품은 재고·단가 0으로 제공되며 규격과
            단가는 직접 확인해야 합니다.
          </p>
        </div>
        <button
          className="button secondary"
          disabled={command.busy}
          onClick={() => void reload()}
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
      <div className="management-tabs" aria-label="관리 대상">
        <button
          className={tab === "parts" ? "button primary" : "button secondary"}
          aria-pressed={tab === "parts"}
          onClick={() => setTab("parts")}
        >
          부품 재고
        </button>
        <button
          className={
            tab === "mechanics" ? "button primary" : "button secondary"
          }
          aria-pressed={tab === "mechanics"}
          onClick={() => setTab("mechanics")}
        >
          정비사 · {mechanics.length}명
        </button>
      </div>
      <div hidden={tab !== "parts"}>
        <div className="management-toolbar">
          <label>
            부품 검색
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="부품명 또는 부품번호"
            />
          </label>
          <label>
            재고 상태
            <select
              value={stockFilter}
              onChange={(e) => setStockFilter(e.target.value)}
            >
              <option value="all">전체</option>
              <option value="low">안전재고 이하</option>
              <option value="inactive">사용 중지</option>
            </select>
          </label>
          <label className="compact-check">
            <input
              type="checkbox"
              checked={includeArchived}
              disabled={command.busy}
              onChange={(e) => setIncludeArchived(e.target.checked)}
            />
            삭제한 부품도 표시
          </label>
          <span className="muted">검색 결과 {shown.length}개</span>
        </div>
        <details className="work-panel">
          <summary>부품 등록</summary>
          <p>등록 시 재고는 0입니다. 실물 수량은 입고 기록으로 추가합니다.</p>
          <form
            onSubmit={(e) => {
              const f = form(e);
              void command.run("/api/admin/parts", {
                sku: f.get("sku"),
                name: f.get("name"),
                description: f.get("description"),
                unit: f.get("unit"),
                minimumQuantity: f.get("minimum"),
                unitPrice: f.get("price"),
                active: true,
              });
            }}
          >
            <fieldset disabled={disabled}>
              <label>
                부품번호
                <input name="sku" required maxLength={60} />
              </label>
              <label>
                부품명
                <input name="name" required maxLength={120} />
              </label>
              <label>
                부품 설명
                <textarea
                  name="description"
                  maxLength={600}
                  rows={4}
                  placeholder="용도, 규격 확인 사항, 보관 메모 등을 입력하세요."
                />
                <span className="field-help">일반 텍스트로 최대 600자</span>
              </label>
              <label>
                단위
                <select name="unit">
                  <option value="EA">개 (EA)</option>
                  <option value="L">리터 (L)</option>
                  <option value="KG">킬로그램 (KG)</option>
                  <option value="M">미터 (M)</option>
                </select>
              </label>
              <label>
                안전재고
                <input
                  name="minimum"
                  required
                  type="number"
                  defaultValue="0"
                  min="0"
                  max="99999999999.999"
                  step="0.001"
                />
              </label>
              <label>
                단위당 금액 (원)
                <input
                  name="price"
                  required
                  type="number"
                  min="0"
                  max="999999999999"
                  step="1"
                />
              </label>
              <button className="button primary">부품 등록</button>
            </fieldset>
          </form>
        </details>
        {loading ? (
          <p role="status">불러오는 중입니다…</p>
        ) : (
          <div className="work-layout">
            <section
              className="work-list management-list"
              aria-label="부품 목록"
            >
              {shown.map((p) => (
                <button
                  key={p.id}
                  className={`work-panel work-select ${selected === p.id ? "selected-work" : ""}`}
                  disabled={command.busy}
                  onClick={() => setSelected(p.id)}
                  aria-pressed={selected === p.id}
                >
                  <span className="eyebrow">
                    {p.sku} ·{" "}
                    {p.archived ? "삭제됨" : p.active ? "사용 가능" : "비활성"}
                  </span>
                  <strong>{p.name}</strong>
                  {p.description && (
                    <span className="part-description part-description-list">
                      {p.description}
                    </span>
                  )}
                  <span>
                    재고 {String(p.quantity)} {p.unit} · {won(p.unit_price)}/
                    {p.unit}
                  </span>
                  {p.active &&
                    milli(p.quantity) <= milli(p.minimum_quantity) && (
                      <span className="stock-low">
                        안전재고 이하 · 입고 확인 필요
                      </span>
                    )}
                </button>
              ))}
              {!shown.length && !error && (
                <p className="empty-state">조건에 맞는 부품이 없습니다.</p>
              )}
            </section>
            <section>
              {chosen ? (
                <div className="work-panel">
                  <h2>{chosen.name}</h2>
                  <p>
                    재고 {String(chosen.quantity)} {chosen.unit}
                  </p>
                  <div className="part-description-box">
                    <span className="small-label">부품 설명</span>
                    <p className="part-description">
                      {chosen.description || "등록된 설명이 없습니다."}
                    </p>
                  </div>
                  {chosen.archived ? (
                    <div className="notice">
                      <p>
                        삭제한 부품입니다. 복원 후 정보를 수정하고 사용 가능
                        여부를 설정하세요.
                      </p>
                      <button
                        className="button secondary"
                        disabled={disabled}
                        onClick={() =>
                          void command.run(
                            `/api/admin/parts/${chosen.id}/restore`,
                            { reason: "관리자 부품 복원" },
                          )
                        }
                      >
                        부품 복원
                      </button>
                    </div>
                  ) : (
                    <div>
                      <button
                        type="button"
                        className="button secondary"
                        disabled={
                          disabled || milli(chosen.quantity) !== BigInt(0)
                        }
                        onClick={() => {
                          if (
                            window.confirm(
                              `${chosen.name}을 목록에서 삭제할까요? 과거 이력은 보존되며 복원할 수 있습니다.`,
                            )
                          )
                            void command.run(
                              `/api/admin/parts/${chosen.id}`,
                              { reason: "관리자 부품 목록 삭제" },
                              "DELETE",
                            );
                        }}
                      >
                        부품 삭제
                      </button>
                      {milli(chosen.quantity) !== BigInt(0) && (
                        <p>남은 재고를 정리한 후 삭제할 수 있습니다.</p>
                      )}
                    </div>
                  )}
                  {!!chosen.services?.length && (
                    <p>
                      연결 정비:{" "}
                      {chosen.services.map((s) => s.name).join(" · ")}
                    </p>
                  )}
                  <details key={`${chosen.id}-adjust-panel`}>
                    <summary>재고 수정 · 실사 보정</summary>
                    <form
                      key={`${chosen.id}-${revision}-adjust`}
                      onSubmit={(e) => {
                        const f = form(e);
                        const quantity = String(f.get("quantity"));
                        if (
                          !window.confirm(
                            `현재 재고 ${chosen.quantity} ${chosen.unit}를 실사 수량 ${quantity} ${chosen.unit}로 보정할까요? 증감 이력이 남습니다.`,
                          )
                        )
                          return;
                        void command.run(
                          `/api/admin/parts/${chosen.id}/adjustments`,
                          {
                            quantity,
                            expectedQuantity: String(chosen.quantity),
                            reason: f.get("reason"),
                          },
                        );
                      }}
                    >
                      <h3>재고 수정 · 실사 보정</h3>
                      <p>
                        추가할 수량이 아닌, 실제로 보유한 전체 수량을
                        입력하세요. 다른 작업이 재고를 변경하면 새로 확인해야
                        합니다.
                      </p>
                      <fieldset disabled={disabled || chosen.archived}>
                        <label>
                          실제 보유 수량 ({chosen.unit})
                          <input
                            name="quantity"
                            type="number"
                            defaultValue={String(chosen.quantity)}
                            required
                            min="0"
                            max="99999999999.999"
                            step="0.001"
                          />
                        </label>
                        <label>
                          보정 사유
                          <input
                            name="reason"
                            placeholder="예: 창고 실사 결과, 입력 오류 정정"
                            required
                            maxLength={500}
                          />
                        </label>
                        <button className="button primary">
                          실사 수량으로 재고 수정
                        </button>
                      </fieldset>
                    </form>
                  </details>
                  <details key={`${chosen.id}-${revision}`}>
                    <summary>이름·단가 등 부품 정보 수정</summary>
                    <p>
                      이력의 단가·단위는 보존됩니다. 단위는 등록 후 변경할 수
                      없습니다.
                    </p>
                    <form
                      onSubmit={(e) => {
                        const f = form(e);
                        void command.run(
                          `/api/admin/parts/${chosen.id}`,
                          {
                            sku: f.get("sku"),
                            name: f.get("name"),
                            description: f.get("description"),
                            unit: chosen.unit,
                            minimumQuantity: f.get("minimum"),
                            unitPrice: f.get("price"),
                            active: f.get("active") === "on",
                          },
                          "PATCH",
                        );
                      }}
                    >
                      <fieldset disabled={disabled || chosen.archived}>
                        <label>
                          부품번호
                          <input
                            name="sku"
                            defaultValue={chosen.sku}
                            required
                            maxLength={60}
                          />
                        </label>
                        <label>
                          부품명
                          <input
                            name="name"
                            defaultValue={chosen.name}
                            required
                            maxLength={120}
                          />
                        </label>
                        <label>
                          부품 설명
                          <textarea
                            name="description"
                            defaultValue={chosen.description}
                            maxLength={600}
                            rows={5}
                            placeholder="설명을 비우면 미등록 상태로 저장됩니다."
                          />
                          <span className="field-help">
                            용도와 확인 사항을 일반 텍스트로 입력하세요. 최대 600자
                          </span>
                        </label>
                        <label>
                          안전재고
                          <input
                            name="minimum"
                            defaultValue={String(chosen.minimum_quantity)}
                            required
                            type="number"
                            min="0"
                            max="99999999999.999"
                            step="0.001"
                          />
                        </label>
                        <label>
                          단위당 금액
                          <input
                            name="price"
                            defaultValue={chosen.unit_price}
                            required
                            type="number"
                            min="0"
                            max="999999999999"
                            step="1"
                          />
                        </label>
                        <label>
                          <input
                            name="active"
                            type="checkbox"
                            defaultChecked={chosen.active}
                          />
                          사용 가능
                        </label>
                        <button className="button secondary">정보 저장</button>
                      </fieldset>
                    </form>
                  </details>
                  <form
                    key={`${chosen.id}-${revision}-receipt`}
                    onSubmit={(e) => {
                      const f = form(e);
                      void command.run(
                        `/api/admin/parts/${chosen.id}/receipts`,
                        {
                          quantity: f.get("quantity"),
                          reason: f.get("reason"),
                        },
                      );
                    }}
                  >
                    <h3>실물 입고</h3>
                    <fieldset disabled={disabled || !chosen.active}>
                      <label>
                        입고 수량 ({chosen.unit})
                        <input
                          name="quantity"
                          type="number"
                          required
                          min="0.001"
                          max="99999999999.999"
                          step="0.001"
                        />
                      </label>
                      <label>
                        입고 사유
                        <input name="reason" required maxLength={500} />
                      </label>
                      <button className="button primary">입고 기록</button>
                    </fieldset>
                  </form>
                  <h3>재고 이동 이력</h3>
                  {historyLoading ? (
                    <p role="status">이력을 불러오는 중입니다…</p>
                  ) : (
                    history.map((m) => (
                      <article key={m.id} className="work-movement">
                        <strong>
                          {movementLabel[m.kind]} · {String(m.quantity)}{" "}
                          {m.unit}
                        </strong>
                        <p>
                          {localTime(m.created_at)} · 처리 후 잔량{" "}
                          {String(m.balance_after)}
                        </p>
                        <p>{m.reason}</p>
                        {m.original_use_id && (
                          <small>원래 사용 기록: {m.original_use_id}</small>
                        )}
                      </article>
                    ))
                  )}
                  {!historyLoading && !history.length && (
                    <p>재고 이동 내역이 없습니다.</p>
                  )}
                </div>
              ) : (
                <p>부품을 선택하면 입고 및 이력을 확인할 수 있습니다.</p>
              )}
            </section>
          </div>
        )}
      </div>
      <section className="work-panel" hidden={tab !== "mechanics"}>
        <h2>담당 정비사</h2>
        <p>
          정비사는 담당자 명부입니다. 작업 변경 권한은 기존 관리자 계정이
          가집니다.
        </p>
        <form
          onSubmit={(e) => {
            const f = form(e);
            void command.run("/api/admin/mechanics", {
              code: f.get("code"),
              name: f.get("name"),
              active: true,
            });
          }}
        >
          <fieldset disabled={disabled}>
            <label>
              사번
              <input name="code" required maxLength={40} />
            </label>
            <label>
              이름
              <input name="name" required maxLength={80} />
            </label>
            <button className="button primary">정비사 등록</button>
          </fieldset>
        </form>
        {mechanics.map((m) => (
          <details key={`${m.id}-${revision}`} className="work-movement">
            <summary>
              {m.code} · {m.name} · {m.active ? "활성" : "비활성"}
            </summary>
            <form
              onSubmit={(e) => {
                const f = form(e);
                void command.run(
                  `/api/admin/mechanics/${m.id}`,
                  {
                    code: f.get("code"),
                    name: f.get("name"),
                    active: f.get("active") === "on",
                  },
                  "PATCH",
                );
              }}
            >
              <fieldset disabled={disabled}>
                <label>
                  사번
                  <input
                    name="code"
                    defaultValue={m.code}
                    required
                    maxLength={40}
                  />
                </label>
                <label>
                  이름
                  <input
                    name="name"
                    defaultValue={m.name}
                    required
                    maxLength={80}
                  />
                </label>
                <label>
                  <input
                    name="active"
                    type="checkbox"
                    defaultChecked={m.active}
                  />
                  배정 가능
                </label>
                <button className="button secondary">정비사 저장</button>
              </fieldset>
            </form>
          </details>
        ))}
      </section>
    </>
  );
}
