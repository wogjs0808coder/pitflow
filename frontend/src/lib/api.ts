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
export type ServicePartRequirement = {
  partId: string;
  name: string;
  unit: string;
  quantity: number | null;
  unitPrice: number;
  amount: number;
  active: boolean;
  archived: boolean;
};
export type ServiceItem = {
  id: string;
  name: string;
  description: string;
  laborPrice: number;
  durationMinutes: number;
  active: boolean;
  requirementsConfirmed: boolean;
  parts: ServicePartRequirement[];
  estimatedPartsPrice: number;
  estimatedTotalPrice: number;
};
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public fields: Record<string, string> = {},
  ) {
    super(message);
    this.name = "ApiError";
  }
}

const RETRYABLE_STATUS = new Set([502, 503, 504]);
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);
const RETRY_DELAYS_MS = [1500, 3000, 6000];
const REQUEST_TIMEOUT_MS = 20000;

function wait(ms: number, signal?: AbortSignal | null) {
  return new Promise<void>((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason ?? new DOMException("Aborted", "AbortError"));
      return;
    }
    const aborted = () => {
      clearTimeout(timer);
      reject(signal?.reason ?? new DOMException("Aborted", "AbortError"));
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", aborted);
      resolve();
    }, ms);
    signal?.addEventListener("abort", aborted, { once: true });
  });
}

async function fetchApi(
  path: string,
  options: RequestInit,
  method: string,
): Promise<Response> {
  const safe = SAFE_METHODS.has(method);
  const attempts = safe ? RETRY_DELAYS_MS.length + 1 : 1;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      const timeout = AbortSignal.timeout(REQUEST_TIMEOUT_MS);
      const signal = options.signal
        ? AbortSignal.any([options.signal, timeout])
        : timeout;
      const response = await fetch(path, { ...options, signal });
      const contentType = response.headers.get("content-type") ?? "";
      const unexpectedSuccess =
        response.ok &&
        response.status !== 204 &&
        !contentType.toLowerCase().includes("application/json");
      const retryable = RETRYABLE_STATUS.has(response.status) || unexpectedSuccess;
      if (retryable && safe && attempt < attempts - 1) {
        await wait(RETRY_DELAYS_MS[attempt], options.signal);
        continue;
      }
      if (unexpectedSuccess) {
        throw new ApiError(
          503,
          "서버를 준비하는 중입니다. 잠시 후 다시 시도해 주세요.",
        );
      }
      return response;
    } catch (error) {
      if (options.signal?.aborted) throw error;
      if (error instanceof ApiError) throw error;
      if (safe && attempt < attempts - 1) {
        await wait(RETRY_DELAYS_MS[attempt], options.signal);
        continue;
      }
      throw new ApiError(
        0,
        "서버를 준비 중이거나 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.",
      );
    }
  }
  throw new ApiError(0, "서버에 연결하지 못했습니다.");
}

async function errorBody(response: Response) {
  if (!(response.headers.get("content-type") ?? "").includes("application/json"))
    return {} as { message?: string; fields?: Record<string, string> };
  return response.json().catch(() => ({})) as Promise<{
    message?: string;
    fields?: Record<string, string>;
  }>;
}

export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  const method = (options.method || "GET").toUpperCase();
  if (!SAFE_METHODS.has(method)) {
    const response = await fetchApi(
      "/api/auth/csrf",
      {
        credentials: "same-origin",
        cache: "no-store",
        signal: options.signal,
      },
      "GET",
    );
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
  const response = await fetchApi(
    path,
    {
      ...options,
      headers,
      credentials: "same-origin",
      cache: "no-store",
    },
    method,
  );
  if (!response.ok) {
    const body = await errorBody(response);
    if (
      response.status === 401 &&
      !path.startsWith("/api/auth/") &&
      typeof window !== "undefined"
    )
      window.location.assign("/login");
    throw new ApiError(
      response.status,
      body.message ||
        (RETRYABLE_STATUS.has(response.status)
          ? "서버를 준비하는 중입니다. 잠시 후 다시 시도해 주세요."
          : "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
      body.fields ?? {},
    );
  }
  if (response.status === 204) return undefined as T;
  return response.json().catch(() => {
    throw new ApiError(502, "서버 응답 형식을 확인할 수 없습니다.");
  });
}
export function errorText(error: unknown): string {
  if (error instanceof ApiError && Object.keys(error.fields || {}).length)
    return Object.values(error.fields).join(" ");
  if (error instanceof ApiError) return error.message;
  return "서버에 연결하지 못했습니다. 연결 상태를 확인한 후 다시 시도해 주세요.";
}
export const won = (value: number) =>
  new Intl.NumberFormat("ko-KR").format(value) + "원";
