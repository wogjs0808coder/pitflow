"use client";

import { Bell } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { errorText } from "@/lib/api";
import { useAuth } from "./auth-provider";
import {
  getNotifications,
  getUnreadNotificationCount,
  markAllNotificationsRead,
  markNotificationRead,
  type Notification,
} from "@/lib/notifications";

const POLL_INTERVAL_MS = 30_000;

function localTime(value: string) {
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(value));
}

export function NotificationCenter() {
  const { user } = useAuth();
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<Notification[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const refreshCount = useCallback(async () => {
    try {
      const count = await getUnreadNotificationCount();
      setUnreadCount(count);
    } catch {
      // 알림 polling 실패가 전체 화면을 방해하지 않게 한다.
    }
  }, []);

  const refreshList = useCallback(async () => {
    setLoading(true);
    setError("");

    try {
      const notifications = await getNotifications();
      setItems(notifications);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refreshCount();

    const timer = window.setInterval(() => {
      void refreshCount();
    }, POLL_INTERVAL_MS);

    return () => window.clearInterval(timer);
  }, [refreshCount]);

  useEffect(() => {
    if (!open) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [open]);

  async function toggleOpen() {
    const next = !open;
    setOpen(next);

    if (next) {
      await refreshList();
      await refreshCount();
    }
  }

  async function readOne(notification: Notification) {
    if (notification.readAt === null) {
      try {
        await markNotificationRead(notification.id);

        setItems((current) =>
          current.map((item) =>
            item.id === notification.id
              ? { ...item, readAt: new Date().toISOString() }
              : item,
          ),
        );

        setUnreadCount((count) => Math.max(0, count - 1));
      } catch (e) {
        setError(errorText(e));
        return;
      }
    }

    if (notification.type === "PART_SHORTAGE" && user?.role === "ADMIN") {
      setOpen(false);
      window.location.assign("/admin/shortages");
    } else if (notification.workOrderId && user) {
      const base =
        notification.type === "PART_SHORTAGE_RESOLVED"
          ? "/mechanic/work-orders"
          : user.role === "ADMIN"
          ? "/admin/work-orders"
          : user.role === "MECHANIC"
            ? "/mechanic/work-orders"
            : "/work-orders";

      setOpen(false);
      window.location.assign(
        `${base}?workOrderId=${encodeURIComponent(notification.workOrderId)}`,
      );
    }
  }

  async function readAll() {
    try {
      await markAllNotificationsRead();

      const readAt = new Date().toISOString();

      setItems((current) =>
        current.map((item) =>
          item.readAt === null ? { ...item, readAt } : item,
        ),
      );

      setUnreadCount(0);
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <div className="notification-center">
      <button
        type="button"
        className="icon-button"
        aria-label={
          unreadCount > 0
            ? `알림 ${unreadCount}개 읽지 않음`
            : "알림"
        }
        aria-expanded={open}
        aria-controls={open ? "notification-popup" : undefined}
        onClick={() => void toggleOpen()}
        style={{ position: "relative" }}
      >
        <Bell size={19} aria-hidden />

        {unreadCount > 0 && (
          <span
            aria-hidden
            style={{
              position: "absolute",
              top: "-7px",
              right: "-7px",
              minWidth: "18px",
              height: "18px",
              padding: "0 5px",
              borderRadius: "999px",
              background: "#dc2626",
              color: "#fff",
              fontSize: "11px",
              lineHeight: "18px",
              textAlign: "center",
              fontWeight: 700,
            }}
          >
            {unreadCount > 99 ? "99+" : unreadCount}
          </span>
        )}
      </button>

      {open && (
        <section id="notification-popup" aria-label="알림 목록" className="notification-popup">
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              gap: "12px",
              marginBottom: "12px",
            }}
          >
            <strong>알림</strong>

            <div className="notification-popup-actions">
              {unreadCount > 0 && <button type="button" onClick={() => void readAll()}>모두 읽음</button>}
              <button type="button" onClick={() => setOpen(false)} aria-label="알림 목록 닫기">닫기</button>
            </div>
          </div>

          {error && (
            <p className="error" role="alert">
              {error}
            </p>
          )}

          {loading ? (
            <p>알림을 불러오는 중입니다.</p>
          ) : items.length === 0 ? (
            <p>새로운 알림이 없습니다.</p>
          ) : (
            <div style={{ display: "grid", gap: "8px" }}>
              {items.map((notification) => (
                <button
                  key={notification.id}
                  type="button"
                  onClick={() => void readOne(notification)}
                  style={{
                    display: "block",
                    width: "100%",
                    padding: "12px",
                    border: "1px solid #e5e7eb",
                    borderRadius: "10px",
                    background:
                      notification.readAt === null ? "#f8fafc" : "#fff",
                    textAlign: "left",
                    cursor: "pointer",
                  }}
                >
                  <span
                    style={{
                      display: "block",
                      fontWeight:
                        notification.readAt === null ? 700 : 500,
                    }}
                  >
                    {notification.title}
                  </span>

                  <span
                    style={{
                      display: "block",
                      marginTop: "4px",
                      fontSize: "13px",
                    }}
                  >
                    {notification.message}
                  </span>

                  <span
                    style={{
                      display: "block",
                      marginTop: "6px",
                      fontSize: "12px",
                      color: "#6b7280",
                    }}
                  >
                    {localTime(notification.createdAt)}
                  </span>
                </button>
              ))}
            </div>
          )}
        </section>
      )}
    </div>
  );
}
