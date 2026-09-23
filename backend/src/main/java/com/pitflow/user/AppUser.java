package com.pitflow.user;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "users")
public class AppUser {
  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 254)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Column(nullable = false, length = 50)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Role role;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(name = "phone_number", unique = true, length = 20)
  private String phoneNumber;

  @Column(name = "birth_date")
  private LocalDate birthDate;

  @Column(name = "main_admin", nullable = false)
  private boolean mainAdmin;

  @Column(name = "admin_number", unique = true)
  private Long adminNumber;

  @Column(name = "admin_active", nullable = false)
  private boolean adminActive = true;

  protected AppUser() {}

  public AppUser(String email, String passwordHash, String name, Role role) {
    this.id = UUID.randomUUID();
    this.email = email;
    this.passwordHash = passwordHash;
    this.name = name;
    this.role = role;
    this.createdAt = Instant.now();
    this.adminActive = true;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getName() {
    return name;
  }

  public Role getRole() {
    return role;
  }

  public Instant getCreatedAt() { return createdAt; }

  public String getPhoneNumber() { return phoneNumber; }

  public LocalDate getBirthDate() { return birthDate; }

  public boolean isMainAdmin() { return mainAdmin; }

  public Long getAdminNumber() { return adminNumber; }

  public boolean isAdminActive() { return adminActive; }

  public boolean isProfileComplete() {
    return role == Role.MECHANIC || (phoneNumber != null && birthDate != null);
  }

  public void updateProfile(String name, String phoneNumber, LocalDate birthDate) {
    if (role == Role.CUSTOMER) this.name = name;
    this.phoneNumber = phoneNumber;
    this.birthDate = birthDate;
  }

  public void changePassword(String passwordHash) { this.passwordHash = passwordHash; }

  public void designateMainAdmin() {
    if (role != Role.ADMIN || adminNumber != null) throw new IllegalStateException("Not a bootstrap administrator");
    this.mainAdmin = true;
    this.name = "메인 관리자";
  }

  public void designateAdminNumber(long number) {
    if (role != Role.ADMIN || mainAdmin) throw new IllegalStateException("Not a numbered administrator");
    this.adminNumber = number;
    this.name = "관리자" + number;
  }

  public void deactivateAdmin() {
    if (role != Role.ADMIN || mainAdmin) throw new IllegalStateException("Main administrator cannot be deactivated");
    this.adminActive = false;
  }

  public enum Role {
    CUSTOMER,
    ADMIN,
    MECHANIC
  }
}
