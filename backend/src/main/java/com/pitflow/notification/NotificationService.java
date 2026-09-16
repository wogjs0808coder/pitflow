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

  private UUID requireUserId(String email) {
    return users
        .findByEmail(email)
        .map(AppUser::getId)
        .orElseThrow(
            () -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
  }
}