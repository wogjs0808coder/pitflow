"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { CarFront, Plus, Pencil, Trash2, X } from "lucide-react";
import { api, errorText, Vehicle } from "@/lib/api";
export default function VehiclesPage() {
  const [cars, setCars] = useState<Vehicle[] | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [editing, setEditing] = useState<Vehicle | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [busy, setBusy] = useState(false);
  const formPanel = useRef<HTMLElement>(null);
  const load = useCallback(async () => {
    setCars(await api<Vehicle[]>("/api/vehicles"));
  }, []);
  useEffect(() => {
    load().catch((e) => setError(errorText(e)));
  }, [load]);
  useEffect(() => {
    if (showForm)
      formPanel.current?.querySelector<HTMLInputElement>("input")?.focus();
  }, [showForm, editing]);
  function open(car: Vehicle | null) {
    setEditing(car);
    setShowForm(true);
    setError("");
    setNotice("");
  }
  async function save(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setBusy(true);
    setError("");
    const body = {
      plateNumber: String(f.get("plateNumber")).trim(),
      manufacturer: String(f.get("manufacturer")).trim(),
      model: String(f.get("model")).trim(),
      modelYear: Number(f.get("modelYear")),
      mileage: Number(f.get("mileage")),
    };
    try {
      await api<Vehicle>(
        editing ? `/api/vehicles/${editing.id}` : "/api/vehicles",
        { method: editing ? "PUT" : "POST", body: JSON.stringify(body) },
      );
      await load();
      setShowForm(false);
      setNotice(editing ? "차량 정보를 수정했습니다." : "차량을 등록했습니다.");
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  async function remove(car: Vehicle) {
    if (!window.confirm(`${car.plateNumber} 차량을 삭제하시겠습니까?`)) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await api<void>(`/api/vehicles/${car.id}`, { method: "DELETE" });
      await load();
      if (editing?.id === car.id) setShowForm(false);
      setNotice("차량을 삭제했습니다.");
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
          <span className="eyebrow">VEHICLES</span>
          <h1>내 차량</h1>
          <p>차량 정보를 등록하고 주행거리를 관리하세요.</p>
        </div>
        <button
          className="button primary"
          onClick={() => open(null)}
          disabled={busy}
        >
          <Plus size={18} /> 차량 등록
        </button>
      </div>
      {error && (
        <div className="error" role="alert">
          {error}{" "}
          {!cars && (
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
        <section className="form-panel" ref={formPanel}>
          <div className="section-heading">
            <h2>{editing ? "차량 정보 수정" : "새 차량 등록"}</h2>
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
            onSubmit={save}
            className="form-grid"
          >
            <label>
              차량번호
              <input
                name="plateNumber"
                required
                maxLength={20}
                defaultValue={editing?.plateNumber}
                placeholder="예: 123가4567"
              />
            </label>
            <label>
              제조사
              <input
                name="manufacturer"
                required
                maxLength={40}
                defaultValue={editing?.manufacturer}
                placeholder="예: 기아"
              />
            </label>
            <label>
              모델
              <input
                name="model"
                required
                maxLength={60}
                defaultValue={editing?.model}
                placeholder="예: K3"
              />
            </label>
            <label>
              연식
              <input
                name="modelYear"
                type="number"
                required
                min={1900}
                max={2100}
                defaultValue={editing?.modelYear || new Date().getFullYear()}
              />
            </label>
            <label>
              주행거리 (km)
              <input
                name="mileage"
                type="number"
                required
                min={0}
                max={9999999}
                defaultValue={editing?.mileage ?? 0}
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
      {!cars && !error ? (
        <p role="status">차량 목록을 불러오는 중입니다…</p>
      ) : cars && cars.length ? (
        <>
          <p className="count-label">
            등록 차량 <strong>{cars.length}대</strong>
          </p>
          <div className="vehicle-grid">
            {cars.map((car) => (
              <article className="vehicle-card" key={car.id}>
                <div className="card-top">
                  <span className="pill">{car.modelYear}년식</span>
                  <CarFront size={26} />
                </div>
                <span className="muted small-label">{car.manufacturer}</span>
                <h2>{car.model}</h2>
                <div className="plate">{car.plateNumber}</div>
                <div className="card-bottom">
                  <span>주행거리</span>
                  <strong>{car.mileage.toLocaleString("ko-KR")} km</strong>
                </div>
                <div className="card-actions">
                  <button
                    className="text-button"
                    disabled={busy}
                    onClick={() => open(car)}
                  >
                    <Pencil size={16} /> 수정
                  </button>
                  <button
                    className="text-button danger"
                    disabled={busy}
                    onClick={() => void remove(car)}
                  >
                    <Trash2 size={16} /> 삭제
                  </button>
                </div>
              </article>
            ))}
          </div>
        </>
      ) : (
        cars && (
          <div className="empty-state">
            <CarFront size={38} />
            <h2>나만의 차고를 만들어 보세요</h2>
            <p>차량번호, 차종, 주행거리를 등록해 주세요.</p>
            <button className="button secondary" onClick={() => open(null)}>
              차량 등록하기
            </button>
          </div>
        )
      )}
    </>
  );
}
