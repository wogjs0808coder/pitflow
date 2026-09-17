"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { api, errorText } from "@/lib/api";
import { localTime } from "@/lib/work";
import { useWorkCommand } from "@/components/work-command";

type Shortage = {
  id: string;
  work_order_id: string;
  vehicle_label: string;
  plate_number: string;
  item_name: string;
  part_name: string;
  sku: string;
  requested_quantity: string | number;
  available_quantity_snapshot: string | number;
  reporter_name: string;
  reason: string;
  status: "OPEN" | "RESOLVED";
  created_at: string;
  resolved_at: string | null;
  resolver_name: string | null;
};

export default function PartShortagesPage() {
  const [openOnly, setOpenOnly] = useState(true);
  const [items, setItems] = useState<Shortage[]>([]);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  const reload = useCallback(async () => setRevision((value) => value + 1), []);
  const command = useWorkCommand(reload);

  useEffect(() => {
    const controller = new AbortController();
    setError("");
    api<Shortage[]>(`/api/admin/part-shortages?openOnly=${openOnly}`, { signal: controller.signal })
      .then(setItems)
      .catch((e) => { if (!controller.signal.aborted) setError(errorText(e)); });
    return () => controller.abort();
  }, [openOnly, revision]);

  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">PART SHORTAGE</span>
          <h1>부품 부족 신고</h1>
          <p>정비사가 신고한 부족 부품을 확인하고 처리합니다.</p>
        </div>
        <label>
          <input type="checkbox" checked={openOnly} onChange={(e) => setOpenOnly(e.target.checked)} /> OPEN만 보기
        </label>
      </div>
      {command.feedback}
      {error && <p className="error" role="alert">{error}</p>}
      {!error && !items.length && <p className="empty-state">접수된 부품 부족 신고가 없습니다.</p>}
      <section className="management-list">
        {items.map((item) => (
          <article className="work-panel" key={item.id}>
            <span className={`work-stage ${item.status === "OPEN" ? "stage-active" : "stage-completed"}`}>
              {item.status === "OPEN" ? "접수" : "해결"}
            </span>
            <h2>{item.part_name} · {item.sku}</h2>
            <p>{item.vehicle_label} · {item.plate_number} · {item.item_name}</p>
            <p>필요 {String(item.requested_quantity)} · 신고 당시 재고 {String(item.available_quantity_snapshot)}</p>
            <p>신고자 {item.reporter_name} · {localTime(item.created_at)}</p>
            {item.reason && <p>{item.reason}</p>}
            {item.resolved_at && <p>처리자 {item.resolver_name ?? "관리자"} · {localTime(item.resolved_at)}</p>}
            <Link className="button secondary" href={`/admin/work-orders?workOrderId=${encodeURIComponent(item.work_order_id)}`}>작업 보기</Link>{" "}
            {item.status === "OPEN" && (
              <button className="button primary" disabled={command.blocked} onClick={() => void command.run(`/api/admin/part-shortages/${item.id}/resolve`, {}, "PATCH")}>해결 처리</button>
            )}
          </article>
        ))}
      </section>
    </>
  );
}
