"use client";
import { useCallback, useEffect, useState } from "react";
import { Plus, Wrench, Clock3, Pencil, X } from "lucide-react";
import { api, errorText, ServiceItem, won } from "@/lib/api";
import { useAuth } from "./auth-provider";

type AdminPart = {
  id: string;
  name: string;
  unit: string;
  unit_price: number;
  active: boolean;
  archived: boolean;
};

export function Catalog({ admin = false }: { admin?: boolean }) {
  const { user } = useAuth();
  const allowed = !admin || user?.role === "ADMIN";
  const [items, setItems] = useState<ServiceItem[] | null>(null);
  const [parts, setParts] = useState<AdminPart[]>([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [editing, setEditing] = useState<ServiceItem | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!allowed) return;
    if (admin) {
      const [services, availableParts] = await Promise.all([
        api<ServiceItem[]>("/api/admin/services"),
        api<AdminPart[]>("/api/admin/parts?includeArchived=false"),
      ]);
      setItems(services);
      setParts(availableParts.filter((part) => part.active && !part.archived));
    } else {
      setItems(await api<ServiceItem[]>("/api/services"));
    }
  }, [allowed, admin]);

  useEffect(() => {
    load().catch((e) => setError(errorText(e)));
  }, [load]);

  if (!allowed)
    return (
      <div className="empty-state">
        <h1>관리자 전용 화면입니다</h1>
        <p>정비 항목 조회는 왼쪽 메뉴를 이용해 주세요.</p>
      </div>
    );

  function open(item: ServiceItem | null) {
    setEditing(item);
    setShowForm(true);
    setError("");
    setNotice("");
  }

  async function save(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const f = new FormData(e.currentTarget);
    const selectedParts = parts
      .filter((part) => f.get(`part-${part.id}`) === "on")
      .map((part) => ({
        partId: part.id,
        quantity: String(f.get(`quantity-${part.id}`) ?? "1"),
      }));
    const body = {
      name: String(f.get("name")).trim(),
      description: String(f.get("description")).trim(),
      laborPrice: Number(f.get("laborPrice")),
      durationMinutes: Number(f.get("durationMinutes")),
      active: f.get("active") === "on",
      parts: selectedParts,
    };
    try {
      await api<ServiceItem>(
        editing ? `/api/admin/services/${editing.id}` : "/api/admin/services",
        { method: editing ? "PUT" : "POST", body: JSON.stringify(body) },
      );
      await load();
      setShowForm(false);
      setNotice("정비 항목과 예상 부품 구성을 저장했습니다.");
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">
            {admin ? "CATALOG MANAGEMENT" : "SERVICE CATALOG"}
          </span>
          <h1>{admin ? "정비 항목 관리" : "정비 항목"}</h1>
          <p>
            {admin
              ? "공임, 소요 시간과 예약 시 사용할 예상 부품 구성을 관리하세요."
              : "예약 전 공임과 예상 부품비를 함께 확인하세요."}
          </p>
        </div>
        {admin && (
          <button
            className="button primary"
            disabled={busy}
            onClick={() => open(null)}
          >
            <Plus size={18} /> 항목 추가
          </button>
        )}
      </div>
      {error && (
        <div className="error" role="alert">
          {error}{" "}
          {!items && (
            <button
              className="text-button"
              onClick={() => {
                setError("");
                load().catch((e) => setError(errorText(e)));
              }}
            >
              다시 시도
            </button>
          )}
        </div>
      )}
      {notice && (
        <div className="notice" role="status">
          {notice}
        </div>
      )}
      {showForm && (
        <section className="form-panel">
          <div className="section-heading">
            <h2>{editing ? "정비 항목 수정" : "정비 항목 추가"}</h2>
            <button
              className="icon-button"
              aria-label="입력 닫기"
              disabled={busy}
              onClick={() => setShowForm(false)}
            >
              <X size={20} />
            </button>
          </div>
          <form
            key={editing?.id || "new"}
            className="form-grid"
            onSubmit={save}
          >
            <label>
              정비명
              <input
                name="name"
                required
                maxLength={80}
                defaultValue={editing?.name}
              />
            </label>
            <label>
              기본 공임 (원)
              <input
                name="laborPrice"
                type="number"
                min={0}
                max={999999999999}
                step={1}
                required
                defaultValue={editing?.laborPrice ?? 0}
              />
            </label>
            <label>
              예상 소요 시간 (분)
              <input
                name="durationMinutes"
                type="number"
                min={30}
                max={480}
                step={30}
                required
                defaultValue={editing?.durationMinutes ?? 30}
              />
            </label>
            <label className="check-label">
              <input
                name="active"
                type="checkbox"
                defaultChecked={editing?.active ?? true}
              />{" "}
              고객에게 안내
            </label>
            <label className="span-all">
              설명
              <textarea
                name="description"
                required
                maxLength={500}
                rows={3}
                defaultValue={editing?.description}
              />
            </label>
            <div className="span-all work-panel">
              <h3>예약 예상 부품</h3>
              <p className="muted">
                실제 정비 시 사용량은 재고 사용 기록으로 확정됩니다. 여기의 수량은 예약 견적용입니다.
              </p>
              {!parts.length ? (
                <p className="muted">현재 연결할 수 있는 활성 부품이 없습니다. 부품 없이 저장할 수 있습니다.</p>
              ) : (
                <div className="service-options">
                  {parts.map((part) => {
                    const current = editing?.parts.find((value) => value.partId === part.id);
                    return (
                      <label className="service-option" key={part.id}>
                        <input
                          type="checkbox"
                          name={`part-${part.id}`}
                          defaultChecked={Boolean(current)}
                        />
                        <span>
                          <strong>{part.name}</strong>
                          <small>{won(part.unit_price)}/{part.unit}</small>
                          <input
                            name={`quantity-${part.id}`}
                            aria-label={`${part.name} 필요 수량`}
                            type="number"
                            min="0.001"
                            max="99999999999.999"
                            step="0.001"
                            defaultValue={current?.quantity ?? 1}
                          />
                          <small>필요 수량 ({part.unit})</small>
                        </span>
                      </label>
                    );
                  })}
                </div>
              )}
            </div>
            <div className="form-actions">
              <button
                type="button"
                className="button secondary"
                disabled={busy}
                onClick={() => setShowForm(false)}
              >
                취소
              </button>
              <button className="button primary" disabled={busy}>
                {busy ? "저장 중…" : "저장"}
              </button>
            </div>
          </form>
        </section>
      )}
      <div className="info-note">
        확정된 예상 금액은 기본 공임과 현재 부품 판매단가를 기준으로 계산됩니다. 최종 결제 금액은 실제 사용·반환된 부품 수량을 기준으로 다시 정산됩니다.
      </div>
      {!items && !error ? (
        <p role="status">정비 항목을 불러오는 중입니다…</p>
      ) : (
        items && (
          <div className="catalog-grid">
            {items.map((item) => (
              <article className="catalog-card" key={item.id}>
                <div className="card-top">
                  <span className="service-icon">
                    <Wrench size={22} />
                  </span>
                  {admin && (
                    <span className={item.active ? "pill" : "pill inactive"}>
                      {item.active ? "안내 중" : "비활성"}
                    </span>
                  )}
                </div>
                <h2>{item.name}</h2>
                <p>{item.description}</p>
                <div className="catalog-meta">
                  <span>
                    <Clock3 size={16} /> 예상 {item.durationMinutes}분
                  </span>
                  <div className="price">
                    <span>{item.requirementsConfirmed ? "예상 총액" : "기본 공임"}</span>
                    <strong>
                      {won(
                        item.requirementsConfirmed
                          ? item.estimatedTotalPrice
                          : item.laborPrice,
                      )}
                    </strong>
                  </div>
                </div>
                {item.requirementsConfirmed ? (
                  <p className="muted">
                    공임 {won(item.laborPrice)} · 예상 부품비 {won(item.estimatedPartsPrice)}
                  </p>
                ) : (
                  <p className="muted">예상 부품 구성이 아직 확정되지 않았습니다.</p>
                )}
                {admin && item.parts.length > 0 && (
                  <p className="muted">
                    연결 부품: {item.parts.map((part) => `${part.name} ${part.quantity ?? "미확정"}${part.unit}`).join(" · ")}
                  </p>
                )}
                {admin && (
                  <div className="card-actions">
                    <button
                      className="text-button"
                      disabled={busy}
                      onClick={() => open(item)}
                    >
                      <Pencil size={16} /> 항목 수정
                    </button>
                  </div>
                )}
              </article>
            ))}
            {!items.length && (
              <div className="empty-state">
                <h2>등록된 정비 항목이 없습니다</h2>
                <p>
                  {admin
                    ? "첫 정비 항목을 추가해 주세요."
                    : "정비소에서 항목을 준비 중입니다."}
                </p>
              </div>
            )}
          </div>
        )
      )}
    </>
  );
}
