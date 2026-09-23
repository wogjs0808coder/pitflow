"use client";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
} from "react";
import { api, ApiError, errorText, User } from "@/lib/api";
type Auth = {
  user: User | null;
  loading: boolean;
  error: string;
  refresh: () => Promise<User | null>;
  logout: () => Promise<void>;
  forgetUser: () => void;
};
const Context = createContext<Auth | null>(null);
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const value = await api<User>("/api/auth/me");
      setUser(value);
      setError("");
      return value;
    } catch (e) {
      setUser(null);
      if (!(e instanceof ApiError && e.status === 401)) setError(errorText(e));
      else setError("");
      return null;
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void refresh();
  }, [refresh]);
  async function logout() {
    await api<void>("/api/auth/logout", { method: "POST" });
    setUser(null);
    setError("");
  }
  function forgetUser() {
    setUser(null);
    setError("");
  }
  return (
    <Context.Provider value={{ user, loading, error, refresh, logout, forgetUser }}>
      {children}
    </Context.Provider>
  );
}
export function useAuth() {
  const value = useContext(Context);
  if (!value) throw new Error("Missing AuthProvider");
  return value;
}
