export type AppointmentStatus =
  | "PENDING"
  | "CONFIRMED"
  | "CANCELLED"
  | "VISITED"
  | "NO_SHOW";
export const statusLabel: Record<AppointmentStatus, string> = {
  PENDING: "확인 대기",
  CONFIRMED: "예약 확정",
  CANCELLED: "취소",
  VISITED: "방문 완료",
  NO_SHOW: "미방문",
};
export const actionLabel: Record<AppointmentStatus, string> = {
  PENDING: "대기로 변경",
  CONFIRMED: "예약 확정",
  CANCELLED: "예약 취소",
  VISITED: "방문 처리",
  NO_SHOW: "미방문 처리",
};
export type WorkBay = { id: string; name: string };
export type BookingPolicy = {
  timezone: string;
  opensAt: string;
  closesAt: string;
  closedDays: string[];
  slotMinutes: number;
  earliestDate: string;
  latestDate: string;
};
export type AvailableSlot = {
  startsAt: string;
  endsAt: string;
  availableBays: WorkBay[];
};
export type Availability = {
  date: string;
  policy: BookingPolicy;
  closed: boolean;
  durationMinutes: number;
  totalLaborPrice: number;
  slots: AvailableSlot[];
};
export type Appointment = {
  id: string;
  vehicleId: string;
  plateNumber: string;
  vehicleLabel: string;
  workBayId: string;
  workBayName: string;
  customerName: string;
  customerEmail: string;
  startsAt: string;
  endsAt: string;
  status: AppointmentStatus;
  notes: string;
  totalLaborPrice: number;
  durationMinutes: number;
  items: {
    serviceId: string;
    name: string;
    laborPrice: number;
    durationMinutes: number;
  }[];
  allowedStatuses: AppointmentStatus[];
};
export function seoulToday() {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  const part = (type: string) => parts.find((p) => p.type === type)!.value;
  return `${part("year")}-${part("month")}-${part("day")}`;
}
export function addDays(date: string, days: number) {
  const value = new Date(`${date}T12:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}
export function monthRange(month: string) {
  const from = `${month}-01`;
  const value = new Date(`${from}T12:00:00Z`);
  value.setUTCMonth(value.getUTCMonth() + 1, 0);
  return { from, to: value.toISOString().slice(0, 10) };
}
export function dayLabel(date: string) {
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "UTC",
    month: "long",
    day: "numeric",
    weekday: "short",
  }).format(new Date(`${date.slice(0, 10)}T12:00:00Z`));
}
export const timeLabel = (dateTime: string) => dateTime.slice(11, 16);
