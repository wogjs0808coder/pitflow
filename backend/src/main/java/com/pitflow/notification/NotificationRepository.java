package com.pitflow.notification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  List<Notification> findTop50ByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId);

  long countByRecipientUserIdAndReadAtIsNull(UUID recipientUserId);

  Optional<Notification> findByIdAndRecipientUserId(UUID id, UUID recipientUserId);

  List<Notification> findByRecipientUserIdAndReadAtIsNull(UUID recipientUserId);
}