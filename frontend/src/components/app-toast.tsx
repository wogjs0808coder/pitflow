"use client";

import { createContext, useCallback, useContext, useEffect, useState } from "react";

type Toast = { id: number; message: string; kind: "success" | "error" };
type ToastContextValue = { showToast: (message: string, kind: Toast["kind"]) => void };
const ToastContext = createContext<ToastContextValue | null>(null);
let nextToastId = 0;

export function AppToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const showToast = useCallback((message: string, kind: Toast["kind"]) => {
    const toast = { id: ++nextToastId, message, kind };
    setToasts((current) => [...current, toast].slice(-4));
  }, []);
  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  return <ToastContext.Provider value={{ showToast }}>
    {children}
    {toasts.length > 0 && <div className="app-toast-layer">
      {toasts.map((toast) => <ToastItem key={toast.id} toast={toast} dismiss={dismiss} />)}
    </div>}
  </ToastContext.Provider>;
}

function ToastItem({ toast, dismiss }: { toast: Toast; dismiss: (id: number) => void }) {
  useEffect(() => {
    const timer = window.setTimeout(() => dismiss(toast.id), 5000);
    return () => window.clearTimeout(timer);
  }, [toast.id, dismiss]);

  return <div className={`app-toast ${toast.kind}`} role={toast.kind === "error" ? "alert" : "status"}>
    <span className="app-toast-indicator" aria-hidden="true" />
    <span>{toast.message}</span>
    <button type="button" aria-label="알림 닫기" onClick={() => dismiss(toast.id)}>×</button>
  </div>;
}

export function useAppToast() {
  const context = useContext(ToastContext);
  if (!context) throw new Error("Missing AppToastProvider");
  return context.showToast;
}
