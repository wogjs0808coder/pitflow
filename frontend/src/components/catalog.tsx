"use client";
import { useCallback, useEffect, useState } from "react";
import { Plus, Wrench, Clock3, Pencil, X } from "lucide-react";
import { api, errorText, ServiceItem, won } from "@/lib/api";
import { useAuth } from "./auth-provider";
export function Catalog({ admin = false }: { admin?: boolean }) {
  const { user } = useAuth();
  const allowed = !admin || user?.role === "ADMIN";
  const [items, setItems] = useState<ServiceItem[] | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [editing, setEditing] = useState<ServiceItem | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => {
    if (allowed)
      setItems(
        await api<ServiceItem[]>(
          admin ? "/api/admin/services" : "/api/services",
        ),
      );
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
    const body = {
      name: String(f.get("name")).trim(),
      description: String(f.get("description")).trim(),
      laborPrice: Number(f.get("laborPrice")),
      durationMinutes: Number(f.get("durationMinutes")),
      active: f.get("active") === "on",
    };
    try {
      await api<ServiceItem>(
        editing ? `/api/admin/services/${editing.id}` : "/api/admin/services",
        { method: editing ? "PUT" : "POST", body: JSON.stringify(body) },
      );
      await load();
      setShowForm(false);
      setNotice("정비 항목을 저장했습니다.");
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
              ? "공임, 소요 시간과 안내 여부를 관리하세요."
              : "기본 공임과 예상 소요 시간을 확인하세요."}
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
        표시 금액은 기본 공임입니다. 부품 비용은 별도이며, 최종 비용은 차량
        상태와 작업 내용에 따라 달라집니다.
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
                    <span>기본 공임</span>
                    <strong>{won(item.laborPrice)}</strong>
                  </div>
                </div>
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
