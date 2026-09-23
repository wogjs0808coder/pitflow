"use client";

import { createContext, useCallback, useContext, useEffect, useState } from "react";

type Toast = { id: number; message: string; kind: "success" | "error" };
type ToastContextValue = { showToast: (message: string, kind: Toast["kind"]) => void };
const ToastContext = createContext<ToastContextValue | null>(null);
let nextToastId = 0;

export function AppToastProvider({ children }: { children: React.ReactNode }) {
  const [toast, setToast] = useState<Toast | null>(null);
  const showToast = useCallback((message: string, kind: Toast["kind"]) => {
    setToast({ id: ++nextToastId, message, kind });
  }, []);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 5000);
    return () => window.clearTimeout(timer);
  }, [toast]);

  return <ToastContext.Provider value={{ showToast }}>
    {children}
    {toast && <div className="app-toast-layer">
      <div className={`app-toast ${toast.kind}`} role={toast.kind === "error" ? "alert" : "status"}>
        <span className="app-toast-indicator" aria-hidden="true" />
        <span>{toast.message}</span>
        <button type="button" aria-label="알림 닫기" onClick={() => setToast(null)}>×</button>
      </div>
    </div>}
  </ToastContext.Provider>;
}

export function useAppToast() {
  const context = useContext(ToastContext);
  if (!context) throw new Error("Missing AppToastProvider");
  return context.showToast;
}
