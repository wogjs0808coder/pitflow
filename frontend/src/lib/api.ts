export type User = {
  id: string;
  email: string;
  name: string;
  role: "CUSTOMER" | "ADMIN";
};
export type Vehicle = {
  id: string;
  plateNumber: string;
  manufacturer: string;
  model: string;
  modelYear: number;
  mileage: number;
};
export type ServiceItem = {
  id: string;
  name: string;
  description: string;
  laborPrice: number;
  durationMinutes: number;
  active: boolean;
};
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public fields: Record<string, string> = {},
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  const method = (options.method || "GET").toUpperCase();
  if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
    // A fresh token is required after login/logout rotates the session security context.
    const response = await fetch("/api/auth/csrf", {
      credentials: "same-origin",
      cache: "no-store",
    });
    if (!response.ok)
      throw new ApiError(
        response.status,
        "보안 정보를 불러오지 못했습니다. 다시 시도해 주세요.",
      );
    const csrf = (await response.json()) as {
      token: string;
      headerName: string;
    };
    headers.set(csrf.headerName, csrf.token);
  }
  if (options.body && !(options.body instanceof URLSearchParams))
    headers.set("Content-Type", "application/json");
  const response = await fetch(path, {
    ...options,
    headers,
    credentials: "same-origin",
    cache: "no-store",
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    if (
      response.status === 401 &&
      !path.startsWith("/api/auth/") &&
      typeof window !== "undefined"
    )
      window.location.assign("/login");
    throw new ApiError(
      response.status,
      body.message || "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
      body.fields,
    );
  }
  return response.status === 204 ? (undefined as T) : response.json();
}
export function errorText(error: unknown): string {
  if (error instanceof ApiError && Object.keys(error.fields || {}).length)
    return Object.values(error.fields).join(" ");
  if (error instanceof ApiError) return error.message;
  return "서버에 연결하지 못했습니다. 연결 상태를 확인한 후 다시 시도해 주세요.";
}
export const won = (value: number) =>
  new Intl.NumberFormat("ko-KR").format(value) + "원";
