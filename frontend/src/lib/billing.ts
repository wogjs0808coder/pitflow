import { Decimal, WorkStatus } from "./work";

export type BillingItem = {
  kind: "LABOR" | "PART";
  source_id: string;
  name: string;
  quantity: Decimal;
  unit: string;
  unit_price: Decimal;
  amount: Decimal;
};

export type Quote = {
  work_order_id: string;
  items: BillingItem[];
  total: Decimal;
  fingerprint: string;
  has_zero_prices: boolean;
};

export type Invoice = {
  id: string;
  work_order_id: string;
  status: "OPEN" | "VOID";
  total: Decimal;
  issued_at: string;
  plate_number: string;
  vehicle_label: string;
  paid: Decimal;
};

export type Payment = {
  id: string;
  kind: "PAYMENT" | "REVERSAL";
  original_payment_id: string | null;
  amount: Decimal;
  method: "CASH" | "CARD" | "TRANSFER";
  reference: string;
  reason: string;
  created_at: string;
};

export type InvoiceDetail = {
  id: string;
  work_order_id: string;
  status: "OPEN" | "VOID";
  total: Decimal;
  issued_at: string;
  voided_at: string | null;
  void_reason: string | null;
  vehicle_id: string;
  vehicle_label: string;
  plate_number: string;
  received_mileage: number;
  mechanic_name: string;
  completed_at: string | null;
  items: BillingItem[];
  payments: Payment[];
  paid: Decimal;
  balance: Decimal;
  stale?: boolean;
};

export type HistoryWork = {
  id: string;
  vehicle_id: string;
  vehicle_label: string;
  plate_number: string;
  received_mileage: number;
  mechanic_name: string;
  status: WorkStatus;
  notes: string;
  received_at: string;
  completed_at: string | null;
  items: { name: string; labor_price: Decimal; done: boolean }[];
  invoices: {
    id: string;
    status: "OPEN" | "VOID";
    total: Decimal;
    issued_at: string;
  }[];
};

export type Summary = {
  from: string;
  to: string;
  completed_count: number;
  received: Decimal;
  reversed: Decimal;
  net: Decimal;
  daily: { date: string; net: Decimal }[];
};

export const billingMethodLabel: Record<Payment["method"], string> = {
  CASH: "현금",
  CARD: "카드",
  TRANSFER: "계좌이체",
};

export const billingKindLabel: Record<Payment["kind"], string> = {
  PAYMENT: "수납",
  REVERSAL: "수납 취소",
};

export const decimalNumber = (value: Decimal) => Number(value);
