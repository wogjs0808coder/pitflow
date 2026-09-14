import type { NextConfig } from "next";

function apiBaseUrl() {
  const raw = process.env.API_BASE_URL?.trim();
  if (!raw) {
    if (process.env.VERCEL === "1") {
      throw new Error(
        "API_BASE_URL is required for Vercel builds. Set the production Render HTTPS URL.",
      );
    }
    return "http://127.0.0.1:8081";
  }
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    throw new Error("API_BASE_URL must be an absolute HTTP(S) URL.");
  }
  if (!["http:", "https:"].includes(parsed.protocol)) {
    throw new Error("API_BASE_URL must use HTTP or HTTPS.");
  }
  if (process.env.VERCEL === "1" && parsed.protocol !== "https:") {
    throw new Error("API_BASE_URL must use HTTPS for Vercel builds.");
  }
  return raw.replace(/\/+$/, "");
}

const config: NextConfig = {
  output: process.env.VERCEL === "1" ? undefined : "standalone",
  poweredByHeader: false,
  async headers() {
    return [
      {
        source: "/api/:path*",
        headers: [{ key: "Cache-Control", value: "private, no-store" }],
      },
    ];
  },
  async rewrites() {
    const base = apiBaseUrl();
    return [{ source: "/api/:path*", destination: `${base}/api/:path*` }];
  },
};
export default config;
