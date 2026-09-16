import { api } from "./api";

export type NotificationType =
  | "WORK_ASSIGNED"
  | "WORK_COMPLETED"
  | "PART_SHORTAGE";

export type Notification = {
  id: string;
  type: NotificationType;
  title: string;
  message: string;
  workOrderId: string | null;
  readAt: string | null;
  createdAt: string;
};

export async function getNotifications(): Promise<Notification[]> {
  return api<Notification[]>("/api/notifications");
}

export async function getUnreadNotificationCount(): Promise<number> {
  const result = await api<{ count: number }>("/api/notifications/unread-count");
  return result.count;
}

export async function markNotificationRead(id: string): Promise<void> {
  await api<void>(`/api/notifications/${id}/read`, {
    method: "PATCH",
  });
}

export async function markAllNotificationsRead(): Promise<void> {
  await api<void>("/api/notifications/read-all", {
    method: "PATCH",
  });
}