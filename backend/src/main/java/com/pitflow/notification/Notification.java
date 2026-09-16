package com.pitflow.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

  @Id
  private UUID id;

  @Column(name = "recipient_user_id", nullable = false)
  private UUID recipientUserId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private Type type;

  @Column(nullable = false, length = 120)
  private String title;

  @Column(nullable = false, length = 500)
  private String message;

  @Column(name = "work_order_id")
  private UUID workOrderId;

  @Column(name = "read_at")
  private Instant readAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Notification() {}

  public Notification(
      UUID recipientUserId,
      Type type,
      String title,
      String message,
      UUID workOrderId) {
    this.id = UUID.randomUUID();
    this.recipientUserId = recipientUserId;
    this.type = type;
    this.title = title;
    this.message = message;
    this.workOrderId = workOrderId;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getRecipientUserId() {
    return recipientUserId;
  }

  public Type getType() {
    return type;
  }

  public String getTitle() {
    return title;
  }

  public String getMessage() {
    return message;
  }

  public UUID getWorkOrderId() {
    return workOrderId;
  }

  public Instant getReadAt() {
    return readAt;
  }

  public void markRead() {
    if (readAt == null) {
      readAt = Instant.now();
    }
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public enum Type {
    WORK_ASSIGNED,
    WORK_COMPLETED,
    PART_SHORTAGE
  }
}