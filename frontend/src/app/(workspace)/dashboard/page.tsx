"use client";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { ArrowUpRight, CarFront, Plus, Wrench, Clock3 } from "lucide-react";
import { api, errorText, Vehicle, ServiceItem, won } from "@/lib/api";
import { useAuth } from "@/components/auth-provider";
export default function Dashboard() {
  const { user } = useAuth();
  const [data, setData] = useState<{
    cars: Vehicle[];
    services: ServiceItem[];
  } | null>(null);
  const [error, setError] = useState("");
  const load = useCallback(async () => {
    setError("");
    try {
      const [cars, services] = await Promise.all([
        api<Vehicle[]>("/api/vehicles"),
        api<ServiceItem[]>("/api/services"),
      ]);
      setData({ cars, services });
    } catch (e) {
      setError(errorText(e));
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">MY GARAGE</span>
          <h1>{user?.name}님의 차고</h1>
          <p>차량 정보를 확인하고 필요한 정비를 살펴보세요.</p>
        </div>
        <Link className="button primary" href="/vehicles">
          <Plus size={18} /> 차량 관리
        </Link>
      </div>
      {error ? (
        <div className="error" role="alert">
          {error}{" "}
          <button className="text-button" onClick={() => void load()}>
            다시 시도
          </button>
        </div>
      ) : !data ? (
        <p role="status">차고 정보를 불러오는 중입니다…</p>
      ) : (
        <>
          <div className="overview-grid">
            <section className="garage-summary">
              <div className="summary-top">
                <span>등록된 내 차량</span>
                <CarFront size={26} />
              </div>
              <div className="summary-number">
                {data.cars.length}
                <span>대</span>
              </div>
              <Link href="/vehicles">
                내 차량 보기 <ArrowUpRight size={18} />
              </Link>
            </section>
            <section className="intro-panel">
              <span className="eyebrow">KEEP YOUR CAR IN VIEW</span>
              <h2>
                {data.cars.length
                  ? "차량 정보부터 꼼꼼하게."
                  : "첫 차량을 등록해 보세요."}
              </h2>
              <p>
                {data.cars.length
                  ? "차량번호와 주행거리를 최신 상태로 관리하세요. 내 차량 정보는 로그인한 계정에서만 확인할 수 있습니다."
                  : "차종, 연식, 주행거리를 입력하면 나만의 차고에서 차량 정보를 관리할 수 있습니다."}
              </p>
              <Link className="inline-link" href="/vehicles">
                {data.cars.length ? "차량 정보 확인" : "차량 등록하기"}
                <ArrowUpRight size={17} />
              </Link>
            </section>
          </div>
          <div className="section-heading">
            <h2>내 차량</h2>
            <Link href="/vehicles">
              전체 보기 <ArrowUpRight size={16} />
            </Link>
          </div>
          {data.cars.length ? (
            <div className="vehicle-grid">
              {data.cars.slice(0, 2).map((car) => (
                <Link
                  className="vehicle-card linked-card"
                  href="/vehicles"
                  key={car.id}
                >
                  <div className="card-top">
                    <span className="pill">{car.modelYear}년식</span>
                    <CarFront size={25} />
                  </div>
                  <span className="muted small-label">{car.manufacturer}</span>
                  <h3>{car.model}</h3>
                  <div className="plate">{car.plateNumber}</div>
                  <div className="card-bottom">
                    <span>주행거리</span>
                    <strong>{car.mileage.toLocaleString("ko-KR")} km</strong>
                  </div>
                </Link>
              ))}
            </div>
          ) : (
            <div className="empty-state">
              <CarFront size={34} />
              <h3>아직 등록된 차량이 없습니다</h3>
              <p>내 차량을 추가해 차고를 채워 보세요.</p>
              <Link className="button secondary" href="/vehicles">
                첫 차량 등록
              </Link>
            </div>
          )}
          <div className="section-heading">
            <h2>정비 항목</h2>
            <Link href="/services">
              전체 보기 <ArrowUpRight size={16} />
            </Link>
          </div>
          <div className="service-list">
            {data.services.slice(0, 3).map((s) => (
              <div className="service-row" key={s.id}>
                <span className="service-icon">
                  <Wrench size={21} />
                </span>
                <div className="service-row-name">
                  <h3>{s.name}</h3>
                  <span>
                    <Clock3 size={14} /> 예상 {s.durationMinutes}분
                  </span>
                </div>
                <div className="price">
                  <span>기본 공임</span>
                  <strong>{won(s.laborPrice)}</strong>
                </div>
              </div>
            ))}
            {!data.services.length && (
              <p className="muted">현재 안내 가능한 정비 항목이 없습니다.</p>
            )}
          </div>
        </>
      )}
    </>
  );
}
