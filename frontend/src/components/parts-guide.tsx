"use client";

import Image from "next/image";
import { useEffect, useMemo, useState } from "react";
import { api, errorText } from "@/lib/api";
import { useAuth } from "./auth-provider";

type GuidePart = {
  id: string;
  sku: string;
  name: string;
  description: string;
  unit: string;
  services: string[];
};

const partImages: Partial<Record<string, string>> = {
  "AUTO-COST-001": "/images/parts/oil-filter.jpg",
  "PF-OIL": "/images/parts/engine-oil.jpg",
  "PF-OIL-FILTER": "/images/parts/oil-filter.jpg",
  "PF-AIR-FILTER": "/images/parts/engine-air-filter.jpg",
  "PF-CABIN-FILTER": "/images/parts/cabin-air-filter.jpg",
  "PF-TIRE": "/images/parts/tire.jpg",
  "PF-BATTERY": "/images/parts/battery.jpg",
  "PF-FRONT-PAD": "/images/parts/brake-pad.jpg",
  "PF-REAR-PAD": "/images/parts/brake-pad.jpg",
  "PF-BRAKE-FLUID": "/images/parts/brake-fluid.jpg",
  "PF-COOLANT": "/images/parts/coolant.jpg",
  "PF-SPARK-PLUG": "/images/parts/spark-plug.jpg",
  "PF-WIPER": "/images/parts/wiper-blades.jpg",
  "PF-TRANS-FLUID": "/images/parts/transmission-fluid.jpg",
  "PF-BELT": "/images/parts/accessory-belt.jpg",
  "PF-BULB": "/images/parts/automotive-bulb.jpg",
  "PF-WASHER": "/images/parts/washer-fluid.jpg",
  "PF-DRAIN-WASHER": "/images/parts/drain-plug-washer.jpg",
};

export function PartsGuide() {
  const { user } = useAuth();
  const [parts, setParts] = useState<GuidePart[]>([]);
  const [query, setQuery] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [failedImages, setFailedImages] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (user?.role !== "CUSTOMER") return;
    const controller = new AbortController();
    api<GuidePart[]>("/api/parts-guide", { signal: controller.signal })
      .then(setParts)
      .catch((reason) => {
        if (!controller.signal.aborted) setError(errorText(reason));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [user?.role]);

  const shown = useMemo(() => {
    const keyword = query.trim().toLocaleLowerCase("ko-KR");
    if (!keyword) return parts;
    return parts.filter((part) =>
      [part.name, part.sku, part.description, ...part.services]
        .join(" ")
        .toLocaleLowerCase("ko-KR")
        .includes(keyword),
    );
  }, [parts, query]);

  if (user?.role !== "CUSTOMER") return <p role="alert">고객 계정으로 이용해 주세요.</p>;

  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">PARTS GUIDE</span>
          <h1>정비 부품 안내</h1>
          <p>정비 과정에서 사용하는 주요 부품의 역할을 확인하세요. 차량별 규격은 작업 전에 다시 확인합니다.</p>
        </div>
        <label className="parts-guide-search">
          부품 검색
          <input type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="부품명 또는 정비 항목" />
        </label>
      </div>
      {error && <div className="error" role="alert">{error}</div>}
      {loading ? <p role="status">부품 안내를 불러오는 중입니다…</p> : (
        <section className="parts-guide-grid" aria-label="정비 부품 목록">
          {shown.map((part) => (
            <article className="work-panel parts-guide-card" key={part.id}>
              <div className="parts-guide-image">
                {partImages[part.sku] && !failedImages.has(part.sku) ? (
                  <Image
                    src={partImages[part.sku]!}
                    alt={`${part.name} 실제 부품 사진`}
                    width={960}
                    height={720}
                    sizes="(max-width: 760px) 100vw, (max-width: 1200px) 50vw, 33vw"
                    loading="lazy"
                    onError={() => setFailedImages((current) => new Set(current).add(part.sku))}
                  />
                ) : <span>사진 준비 중</span>}
              </div>
              <span className="eyebrow">{part.sku} · 단위 {part.unit}</span>
              <h2>{part.name}</h2>
              <p>{part.description || "정비 작업에 사용되는 부품입니다."}</p>
              <div className="parts-guide-services">
                <strong>관련 정비</strong>
                {part.services.length ? <ul>{part.services.map((service) => <li key={service}>{service}</li>)}</ul> : <p>차량 상태에 따라 작업 시 확인</p>}
              </div>
              <p className="muted">차량별 규격 확인이 필요한 부품입니다.</p>
            </article>
          ))}
          {!shown.length && !error && <p className="empty-state">검색 조건에 맞는 부품이 없습니다.</p>}
        </section>
      )}
    </>
  );
}
