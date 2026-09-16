package com.pitflow.notification;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

  private final NotificationService notifications;

  public NotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping
  public Object list(Principal principal) {
    return notifications.list(principal.getName());
  }

  @GetMapping("/unread-count")
  public Map<String, Long> unreadCount(Principal principal) {
    return Map.of("count", notifications.unreadCount(principal.getName()));
  }

  @PatchMapping("/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markRead(Principal principal, @PathVariable UUID id) {
    notifications.markRead(principal.getName(), id);
  }

  @PatchMapping("/read-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markAllRead(Principal principal) {
    notifications.markAllRead(principal.getName());
  }
}