import { TossPaymentResult } from "@/components/toss-payment-result";

export default async function TossFailPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const query = await searchParams;
  const value = (key: string) => typeof query[key] === "string" ? query[key] as string : "";
  return <TossPaymentResult invoiceId={value("invoiceId")} failure={value("message") || "결제가 완료되지 않았습니다."} admin={value("admin") === "1"} />;
}
