package com.pitflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pitflow.user.AppUser;
import com.pitflow.user.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired UserRepository users;

  final String ownerEmail = "notification-owner@example.com";
  final String otherEmail = "notification-other@example.com";

  UUID ownerId;
  UUID otherId;
  UUID oldNotificationId;
  UUID newNotificationId;
  UUID otherNotificationId;

  @BeforeEach
  void setup() {
    db.update("DELETE FROM notifications");

    ownerId =
        users
            .saveAndFlush(
                new AppUser(ownerEmail, "test-hash", "Owner", AppUser.Role.CUSTOMER))
            .getId();

    otherId =
        users
            .saveAndFlush(
                new AppUser(otherEmail, "test-hash", "Other", AppUser.Role.CUSTOMER))
            .getId();

    oldNotificationId = UUID.randomUUID();
    newNotificationId = UUID.randomUUID();
    otherNotificationId = UUID.randomUUID();

    insertNotification(
        oldNotificationId,
        ownerId,
        "WORK_ASSIGNED",
        "Older",
        Instant.parse("2026-09-16T10:00:00Z"));

    insertNotification(
        newNotificationId,
        ownerId,
        "WORK_COMPLETED",
        "Newest",
        Instant.parse("2026-09-16T11:00:00Z"));

    insertNotification(
        otherNotificationId,
        otherId,
        "WORK_ASSIGNED",
        "Other",
        Instant.parse("2026-09-16T12:00:00Z"));
  }

  @AfterEach
  void clean() {
    db.update("DELETE FROM notifications");
    db.update(
        "DELETE FROM users WHERE email IN (?, ?)",
        ownerEmail,
        otherEmail);
  }

  @Test
  void listsOnlyOwnNotificationsAndCountsUnread() throws Exception {
    mvc.perform(get("/api/notifications").with(user(ownerEmail).roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(newNotificationId.toString()))
        .andExpect(jsonPath("$[0].title").value("Newest"))
        .andExpect(jsonPath("$[1].id").value(oldNotificationId.toString()))
        .andExpect(jsonPath("$[1].title").value("Older"));

    mvc.perform(
            get("/api/notifications/unread-count")
                .with(user(ownerEmail).roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(2));
  }

  @Test
  void readsOwnNotificationRejectsOthersAndReadsAll() throws Exception {
    mvc.perform(
            patch("/api/notifications/" + newNotificationId + "/read")
                .with(user(ownerEmail).roles("CUSTOMER"))
                .with(csrf()))
        .andExpect(status().isNoContent());

    assertThat(
            db.queryForObject(
                "SELECT read_at IS NOT NULL FROM notifications WHERE id=?",
                Boolean.class,
                newNotificationId))
        .isTrue();

    mvc.perform(
            get("/api/notifications/unread-count")
                .with(user(ownerEmail).roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1));

    mvc.perform(
            patch("/api/notifications/" + otherNotificationId + "/read")
                .with(user(ownerEmail).roles("CUSTOMER"))
                .with(csrf()))
        .andExpect(status().isNotFound());

    assertThat(
            db.queryForObject(
                "SELECT read_at IS NULL FROM notifications WHERE id=?",
                Boolean.class,
                otherNotificationId))
        .isTrue();

    mvc.perform(
            patch("/api/notifications/read-all")
                .with(user(ownerEmail).roles("CUSTOMER"))
                .with(csrf()))
        .andExpect(status().isNoContent());

    mvc.perform(
            get("/api/notifications/unread-count")
                .with(user(ownerEmail).roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0));
  }

  private void insertNotification(
      UUID id,
      UUID recipientUserId,
      String type,
      String title,
      Instant createdAt) {
    db.update(
        """
        INSERT INTO notifications
        (id, recipient_user_id, type, title, message, work_order_id, read_at, created_at)
        VALUES (?, ?, ?, ?, ?, NULL, NULL, ?)
        """,
        id,
        recipientUserId,
        type,
        title,
        "Test notification",
        Timestamp.from(createdAt));
  }
}