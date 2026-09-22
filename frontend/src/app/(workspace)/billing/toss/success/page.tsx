import { TossPaymentResult } from "@/components/toss-payment-result";

export default async function TossSuccessPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const query = await searchParams;
  const value = (key: string) => typeof query[key] === "string" ? query[key] as string : "";
  return <TossPaymentResult invoiceId={value("invoiceId")} paymentKey={value("paymentKey")} orderId={value("orderId")} amount={value("amount")} admin={value("admin") === "1"} />;
}
