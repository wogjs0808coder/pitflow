import type { NextConfig } from "next";
const config: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  async rewrites() {
    const base = process.env.API_BASE_URL || "http://127.0.0.1:8081";
    return [{ source: "/api/:path*", destination: `${base}/api/:path*` }];
  },
};
export default config;
