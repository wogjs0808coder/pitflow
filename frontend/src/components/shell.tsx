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
  BadgeDollarSign,
  PackageSearch,
} from "lucide-react";

import { useAuth } from "./auth-provider";
import { errorText } from "@/lib/api";
import { NotificationCenter } from "./notification-center";
import { AdminClock } from "./admin-clock";

export function Shell({ children }: { children: React.ReactNode }) {
  const { user, loading, error, refresh, logout } = useAuth();
  const [logoutError, setLogoutError] = useState("");
  const [leaving, setLeaving] = useState(false);

  const path = usePathname();
  const router = useRouter();

  useEffect(() => {
    if (!loading && !user && !error) {
      router.replace("/login");
    }
  }, [loading, user, error, router]);

  useEffect(() => {
    if (!loading && user && user.role !== "MECHANIC" && !user.profileComplete && path !== "/account") {
      router.replace("/account");
    }
  }, [loading, user, path, router]);

  if (loading) {
    return (
      <div className="loading-screen" role="status">
        PitFlow를 불러오는 중입니다…
      </div>
    );
  }

  if (error && !user) {
    return (
      <div className="loading-screen">
        <p role="alert">{error}</p>
        <button onClick={() => void refresh()}>다시 연결</button>
      </div>
    );
  }

  if (!user) {
    return null;
  }

  if (user.role !== "MECHANIC" && !user.profileComplete && path !== "/account") return null;

  const isActive = (href: string) =>
    path === href || path.startsWith(`${href}/`);

  const customerSections = [
    {
      caption: "MY GARAGE",
      links: [
        {
          href: "/dashboard",
          label: "내 정비 홈",
          icon: Gauge,
        },
        {
          href: "/vehicles",
          label: "내 차량",
          icon: CarFront,
        },
        {
          href: "/appointments",
          label: "내 정비 예약",
          icon: CalendarDays,
        },
        {
          href: "/work-orders",
          label: "내 정비 작업",
          icon: Wrench,
        },
        {
          href: "/parts-guide",
          label: "정비 부품 안내",
          icon: PackageSearch,
        },
        {
          href: "/history",
          label: "정비 이력·수납",
          icon: Settings2,
        },
        { href: "/account", label: "내 정보", icon: Settings2 },
      ],
    },
  ];

  const mechanicSections = [
    {
      caption: "WORKSPACE",
      links: [
        {
          href: "/mechanic/work-orders",
          label: "내 정비 작업",
          icon: Wrench,
        },
        { href: "/account", label: "내 정보", icon: Settings2 },
      ],
    },
  ];

  const adminSections = [
    {
      caption: "빠른 테스트",
      links: [
        {
          href: "/appointments",
          label: "고객 예약 테스트",
          icon: CalendarDays,
        },
        {
          href: "/vehicles",
          label: "테스트 차량",
          icon: CarFront,
        },
      ],
    },
    {
      caption: "운영",
      links: [
        {
          href: "/admin/appointments",
          label: "예약 캘린더",
          icon: CalendarDays,
        },
        {
          href: "/admin/work-orders",
          label: "정비 작업 관리",
          icon: Wrench,
        },
        {
          href: "/admin/shortages",
          label: "부품 부족 신고",
          icon: Settings2,
        },
        {
          href: "/admin/billing",
          label: "정산·수납 관리",
          icon: Settings2,
        },
        {
          href: "/admin/finance",
          label: "재무·원가 분석",
          icon: BadgeDollarSign,
        },
        {
          href: "/admin/parts",
          label: "부품·재고 관리",
          icon: Settings2,
        },
      ],
    },
    {
      caption: "관리",
      links: [
        {
          href: "/admin/mechanics",
          label: "정비사·계정 관리",
          icon: Settings2,
        },
        {
          href: "/admin/services",
          label: "정비 항목 관리",
          icon: Settings2,
        },
        ...(user.mainAdmin ? [{ href: "/admin/accounts", label: "관리자 정보", icon: Settings2 }] : []),
        { href: "/account", label: "내 정보", icon: Settings2 },
      ],
    },
  ];

  const adminWorkflowLinks = [
    {
      href: "/appointments",
      label: "고객 예약 테스트",
    },
    {
      href: "/admin/appointments",
      label: "예약",
    },
    {
      href: "/admin/work-orders",
      label: "정비 작업",
    },
    {
      href: "/admin/shortages",
      label: "부품 부족 신고",
    },
    {
      href: "/admin/billing",
      label: "정산·수납",
    },
    {
      href: "/admin/finance",
      label: "재무",
    },
    {
      href: "/admin/parts",
      label: "재고",
    },
  ];

  const sections =
    user.role === "ADMIN"
      ? adminSections
      : user.role === "MECHANIC"
        ? mechanicSections
        : customerSections;

  const homeHref =
    user.role === "ADMIN"
      ? "/admin/appointments"
      : user.role === "MECHANIC"
        ? "/mechanic/work-orders"
        : "/dashboard";

  const topbarTitle =
    user.role === "ADMIN"
      ? "정비소 운영 관리"
      : user.role === "MECHANIC"
        ? "정비 작업"
        : "차량 정비 관리";

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">
        본문으로 이동
      </a>

      <aside className="sidebar">
        <Link className="brand" href={homeHref}>
          <span className="brand-mark">
            <Wrench size={21} aria-hidden />
          </span>
          PitFlow<span className="brand-dot">.</span>
        </Link>

        <div className="sidebar-navigation">
          {sections.map((section, sectionIndex) => (
            <div className="nav-section" key={section.caption}>
              <div
                className="nav-caption"
                style={
                  sectionIndex === 0
                    ? undefined
                    : {
                        marginTop: "22px",
                      }
                }
              >
                {section.caption}
              </div>

              <nav aria-label={section.caption}>
                {section.links.map(({ href, label, icon: Icon }) => (
                  <Link
                    key={href}
                    href={href}
                    className={
                      isActive(href)
                        ? "nav-item selected"
                        : "nav-item"
                    }
                    aria-current={isActive(href) ? "page" : undefined}
                  >
                    <Icon size={20} aria-hidden />
                    {label}
                  </Link>
                ))}
              </nav>
            </div>
          ))}
        </div>

        {user.role === "CUSTOMER" && (
          <div className="sidebar-note">
            <span className="small-label">차량 관리의 시작</span>
            <p>
              내 차의 정보를
              <br />
              한곳에 모아두세요.
            </p>

            <Link href="/vehicles">
              차량 관리하기
              <ArrowUpRight size={16} aria-hidden />
            </Link>
          </div>
        )}

        <div className="profile">
          <div className="avatar">{user.name.slice(0, 1)}</div>

          <div className="profile-text">
            <strong>{user.name}</strong>
            <Link href="/account">
              {user.role === "ADMIN"
                ? "관리자"
                : user.role === "MECHANIC"
                  ? "정비사"
                  : "고객"} · 내 정보
            </Link>
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
          <span>{topbarTitle}</span>

          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: "12px",
            }}
          >
            <NotificationCenter />
            <Link href="/account" className="topbar-account">내 정보 · {user.email}</Link>
          </div>
        </header>

        {user.role === "ADMIN" && (
          <div className="admin-workflow-bar">
          <nav className="admin-workflow-tabs" aria-label="관리자 업무 바로가기">
            {adminWorkflowLinks.map(({ href, label }) => (
              <Link
                key={href}
                href={href}
                className={
                  isActive(href)
                    ? "admin-workflow-tab selected"
                    : "admin-workflow-tab"
                }
                aria-current={isActive(href) ? "page" : undefined}
              >
                {label}
              </Link>
            ))}
          </nav>
          <AdminClock />
          </div>
        )}

        <main id="main">{children}</main>

        <footer>© {new Date().getFullYear()} PitFlow</footer>
      </div>
    </div>
  );
}
