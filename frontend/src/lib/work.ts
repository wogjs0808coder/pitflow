export type WorkStatus =
  | "RECEIVED"
  | "IN_PROGRESS"
  | "WAITING_PARTS"
  | "COMPLETED"
  | "CANCELLED";

export const workLabel: Record<WorkStatus, string> = {
  RECEIVED: "입고",
  IN_PROGRESS: "작업 중",
  WAITING_PARTS: "부품 대기",
  COMPLETED: "완료",
  CANCELLED: "취소",
};

export const workTransitions: Record<WorkStatus, WorkStatus[]> = {
  RECEIVED: ["IN_PROGRESS", "CANCELLED"],
  IN_PROGRESS: ["WAITING_PARTS", "COMPLETED", "CANCELLED"],
  WAITING_PARTS: ["IN_PROGRESS", "CANCELLED"],
  COMPLETED: [],
  CANCELLED: [],
};

export type WorkOrderItemStatus =
  | "PENDING"
  | "IN_PROGRESS"
  | "COMPLETED"
  | "WAITING_PARTS"
  | "SKIPPED";

export const workItemLabel: Record<WorkOrderItemStatus, string> = {
  PENDING: "대기",
  IN_PROGRESS: "작업 중",
  COMPLETED: "완료",
  WAITING_PARTS: "부품 대기",
  SKIPPED: "건너뜀",
};

export const workItemTransitions: Record<
  WorkOrderItemStatus,
  WorkOrderItemStatus[]
> = {
  PENDING: ["IN_PROGRESS", "COMPLETED", "WAITING_PARTS", "SKIPPED"],
  IN_PROGRESS: ["PENDING", "COMPLETED", "WAITING_PARTS", "SKIPPED"],
  WAITING_PARTS: ["IN_PROGRESS", "COMPLETED", "SKIPPED"],
  COMPLETED: ["IN_PROGRESS"],
  SKIPPED: ["IN_PROGRESS"],
};

export function workItemActionLabel(
  current: WorkOrderItemStatus,
  next: WorkOrderItemStatus,
): string {
  if (current === "PENDING" && next === "IN_PROGRESS") return "작업 시작";
  if (current === "WAITING_PARTS" && next === "IN_PROGRESS") return "작업 재개";
  if (current === "IN_PROGRESS" && next === "PENDING") return "대기로 되돌리기";
  if ((current === "COMPLETED" || current === "SKIPPED") && next === "IN_PROGRESS")
    return "작업 다시 열기";
  return workItemLabel[next];
}

export type Decimal = string | number;

export type Mechanic = {
  id: string;
  code: string;
  name: string;
  active: boolean;
};

export type Part = {
  id: string;
  sku: string;
  name: string;
  description: string;
  unit: string;
  quantity: Decimal;
  minimum_quantity: Decimal;
  unit_price: number;
  active: boolean;
  archived: boolean;
  services?: { service_id: string; name: string }[];
};

export type Movement = {
  id: string;
  part_id?: string;
  original_use_id: string | null;
  kind: "RECEIPT" | "USE" | "RETURN" | "ADJUST_IN" | "ADJUST_OUT";
  quantity: Decimal;
  balance_after?: Decimal;
  part_name: string;
  unit: string;
  unit_price: number;
  reason: string;
  created_at: string;
};

export const movementLabel: Record<Movement["kind"], string> = {
  RECEIPT: "입고",
  USE: "사용",
  RETURN: "반환",
  ADJUST_IN: "실사 보정 증가",
  ADJUST_OUT: "실사 보정 감소",
};

export type Work = {
  id: string;
  appointment_id: string;
  vehicle_label: string;
  plate_number: string;
  received_mileage: number;
  mechanic_id: string | null;
  mechanic_name: string | null;
  status: WorkStatus;
  notes: string;
  received_at: string;
  completed_at?: string | null;
  released_at: string | null;
};

export type WorkDetail = Work & {
  items: {
    id: string;
    name: string;
    labor_price: number;
    quantity: number;
    status: WorkOrderItemStatus;
    skip_reason: string | null;
    done: boolean;
  }[];

  events: {
    event_type: string;
    detail: string;
    created_at: string;
  }[];

  movements: Movement[];

  suggested_parts?: (Pick<
    Part,
    "id" | "name" | "quantity" | "unit" | "active"
  > & { required_quantity?: Decimal | null })[];
};

export const localTime = (value: string) =>
  new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(value));

export function milli(value: Decimal): bigint {
  const [whole, fraction = ""] = String(value).split(".");

  return (
    BigInt(whole) * BigInt(1000) +
    BigInt(fraction.padEnd(3, "0").slice(0, 3))
  );
}

export function remaining(
  use: Movement,
  movements: Movement[],
): string {
  const value =
    milli(use.quantity) -
    movements
      .filter((m) => m.original_use_id === use.id)
      .reduce((n, m) => n + milli(m.quantity), BigInt(0));

  return `${value / BigInt(1000)}.${String(
    value % BigInt(1000),
  ).padStart(3, "0")}`;
}
