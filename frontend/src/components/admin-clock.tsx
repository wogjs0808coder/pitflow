"use client";

import { useEffect, useState } from "react";

export function AdminClock() {
  const [now, setNow] = useState<Date | null>(null);

  useEffect(() => {
    setNow(new Date());
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const parts = now ? new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit",
    weekday: "short", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
  }).formatToParts(now) : [];
  const part = (type: string) => parts.find((value) => value.type === type)?.value ?? "--";
  const clock = `${part("hour")}:${part("minute")}:${part("second")}`;

  return <time className="admin-clock" dateTime={now?.toISOString()} aria-label={now ? `한국 시간 ${part("year")}년 ${part("month")}월 ${part("day")}일 ${part("weekday")}요일 ${clock}` : "한국 시간 불러오는 중"}>
    <span className="admin-clock-full">{part("year")}.{part("month")}.{part("day")} ({part("weekday")}) {clock}</span>
    <span className="admin-clock-medium">{part("month")}.{part("day")} {clock}</span>
    <span className="admin-clock-compact">{part("hour")}:{part("minute")}</span>
  </time>;
}
