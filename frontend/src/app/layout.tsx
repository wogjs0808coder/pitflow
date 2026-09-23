import type { Metadata } from "next";
import { AuthProvider } from "@/components/auth-provider";
import { AppToastProvider } from "@/components/app-toast";
import "./globals.css";
export const metadata: Metadata = {
  title: "PitFlow | 차량 정비 관리",
  description: "내 차량과 정비 정보를 한곳에서 관리하세요.",
};
export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <body>
        <AppToastProvider><AuthProvider>{children}</AuthProvider></AppToastProvider>
      </body>
    </html>
  );
}
