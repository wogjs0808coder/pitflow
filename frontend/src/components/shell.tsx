"use client";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import {
  CarFront,
  CalendarDays,
  Gauge,
  Wrench,
  Settings2,
  LogOut,
  ArrowUpRight,
} from "lucide-react";
import { useAuth } from "./auth-provider";
import { errorText } from "@/lib/api";
import { NotificationCenter } from "./notification-center";
export function Shell({ children }: { children: React.ReactNode }) {
  const { user, loading, error, refresh, logout } = useAuth();
  const [logoutError, setLogoutError] = useState("");
  const [leaving, setLeaving] = useState(false);
  const path = usePathname();
  const router = useRouter();
  useEffect(() => {
    if (!loading && !user && !error) router.replace("/login");
  }, [loading, user, error, router]);
  if (loading)
    return (
      <div className="loading-screen" role="status">
        PitFlow를 불러오는 중입니다…
      </div>
    );
  if (error && !user)
    return (
      <div className="loading-screen">
        <p role="alert">{error}</p>
        <button onClick={() => void refresh()}>다시 연결</button>
      </div>
    );
  if (!user) return null;
  const links = user.role === "MECHANIC" ? [
    { href: "/mechanic/work-orders", label: "내 정비 작업", icon: Wrench },
  ] : [
    { href: "/dashboard", label: "내 정비 홈", icon: Gauge },
    { href: "/vehicles", label: "내 차량", icon: CarFront },
    { href: "/services", label: "정비 항목", icon: Wrench },
    { href: "/appointments", label: "내 정비 예약", icon: CalendarDays },
    { href: "/work-orders", label: "내 정비 작업", icon: Wrench },
    { href: "/history", label: "정비 이력·수납", icon: Settings2 },
  ];
  if (user.role === "ADMIN")
    links.push(
      { href: "/admin/appointments", label: "예약 캘린더", icon: CalendarDays },
      { href: "/admin/work-orders", label: "정비 작업 관리", icon: Wrench },
      { href: "/admin/parts", label: "부품 관리", icon: Settings2 },
      { href: "/admin/mechanics", label: "정비사·계정 관리", icon: Settings2 },
      {
        href: "/admin/services",
        label: "정비 항목 관리",
        icon: Settings2,
      },
      { href: "/admin/billing", label: "정산·수납 관리", icon: Settings2 },
    );
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">
        본문으로 이동
      </a>
      <aside className="sidebar">
        <Link className="brand" href={user.role === "MECHANIC" ? "/mechanic/work-orders" : "/dashboard"}>
          <span className="brand-mark">
            <Wrench size={21} aria-hidden />
          </span>
          PitFlow<span className="brand-dot">.</span>
        </Link>
        <div className="nav-caption">MY GARAGE</div>
        <nav aria-label="주 메뉴">
          {links.map(({ href, label, icon: Icon }) => (
            <Link
              key={href}
              href={href}
              className={
                path === href || path.startsWith(href + "/")
                  ? "nav-item selected"
                  : "nav-item"
              }
              aria-current={
                path === href || path.startsWith(href + "/")
                  ? "page"
                  : undefined
              }
            >
              <Icon size={20} aria-hidden />
              {label}
            </Link>
          ))}
        </nav>
        {user.role !== "MECHANIC" && <div className="sidebar-note">
          <span className="small-label">차량 관리의 시작</span>
          <p>
            내 차의 정보를
            <br />
            한곳에 모아두세요.
          </p>
          <Link href="/vehicles">
            차량 관리하기 <ArrowUpRight size={16} aria-hidden />
          </Link>
        </div>}
        <div className="profile">
          <div className="avatar">{user.name.slice(0, 1)}</div>
          <div className="profile-text">
            <strong>{user.name}</strong>
            <span>{user.role === "ADMIN" ? "관리자" : user.role === "MECHANIC" ? "정비사" : "고객"}</span>
          </div>
          <button
            className="icon-button"
            aria-label="로그아웃"
            disabled={leaving}
            onClick={async () => {
              setLeaving(true);
              try {
                await logout();
                router.replace("/login");
              } catch (e) {
                setLogoutError(errorText(e));
              } finally {
                setLeaving(false);
              }
            }}
          >
            <LogOut size={19} />
          </button>
        </div>
        {logoutError && (
          <p className="error" role="alert">
            {logoutError}
          </p>
        )}
      </aside>
      <div className="workspace">
        <header className="topbar">
          <span>차량 정비 관리</span>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: "12px",
            }}
          >
            <NotificationCenter />
            <span className="topbar-account">{user.email}</span>
          </div>
        </header>
        <main id="main">{children}</main>
        <footer>© {new Date().getFullYear()} PitFlow</footer>
      </div>
    </div>
  );
}
