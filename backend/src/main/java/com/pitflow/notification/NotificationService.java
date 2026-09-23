package com.pitflow.notification;

import com.pitflow.common.ApiException;
import com.pitflow.user.AppUser;
import com.pitflow.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

  private final NotificationRepository notifications;
  private final UserRepository users;

  public NotificationService(
      NotificationRepository notifications,
      UserRepository users) {
    this.notifications = notifications;
    this.users = users;
  }

  @Transactional(readOnly = true)
  public List<Notification> list(String email) {
    UUID userId = requireUserId(email);
    return notifications.findTop50ByRecipientUserIdOrderByCreatedAtDesc(userId);
  }

  @Transactional(readOnly = true)
  public long unreadCount(String email) {
    UUID userId = requireUserId(email);
    return notifications.countByRecipientUserIdAndReadAtIsNull(userId);
  }

  @Transactional
  public void markRead(String email, UUID notificationId) {
    UUID userId = requireUserId(email);

    Notification notification =
        notifications
            .findByIdAndRecipientUserId(notificationId, userId)
            .orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."));

    notification.markRead();
  }

  @Transactional
  public void markAllRead(String email) {
    UUID userId = requireUserId(email);

    notifications
        .findByRecipientUserIdAndReadAtIsNull(userId)
        .forEach(Notification::markRead);
  }

  @Transactional
  public void notifyWorkAssigned(UUID recipientUserId, UUID workOrderId) {
    notifications.save(
        new Notification(
            recipientUserId,
            Notification.Type.WORK_ASSIGNED,
            "새 정비 작업이 배정되었습니다.",
            "담당 정비 작업이 배정되었습니다.",
            workOrderId));
  }

  @Transactional
  public void notifyWorkCompletedToAdmins(UUID workOrderId) {
    users.findAllByRole(AppUser.Role.ADMIN)
        .forEach(
            admin ->
                notifications.save(
                    new Notification(
                        admin.getId(),
                        Notification.Type.WORK_COMPLETED,
                        "정비 작업이 완료되었습니다.",
                        "정비사가 담당 작업을 완료했습니다.",
                        workOrderId)));
  }

  @Transactional
  public void notifyPartShortage(UUID workOrderId) {
    users.findAllByRole(AppUser.Role.ADMIN)
        .forEach(
            admin ->
                notifications.save(
                    new Notification(
                        admin.getId(),
                        Notification.Type.PART_SHORTAGE,
                        "부품 부족 신고가 접수되었습니다.",
                        "정비사가 작업에 필요한 부품 부족을 신고했습니다.",
                        workOrderId)));
  }

  @Transactional
  public void notifyPartShortageResolved(UUID recipientUserId, UUID workOrderId) {
    notifications.save(
        new Notification(
            recipientUserId,
            Notification.Type.PART_SHORTAGE_RESOLVED,
            "부품 부족 문제가 해결되었습니다.",
            "담당 정비 작업의 부품 부족 신고가 해결되었습니다.",
            workOrderId));
  }

  private UUID requireUserId(String email) {
    return users
        .findByEmail(email)
        .map(AppUser::getId)
        .orElseThrow(
            () -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
  }
}
