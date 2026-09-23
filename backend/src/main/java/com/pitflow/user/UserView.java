package com.pitflow.user;

import java.util.UUID;
import java.time.LocalDate;

public record UserView(UUID id, String email, String name, AppUser.Role role,
                       String phoneNumber, LocalDate birthDate, boolean profileComplete,
                       boolean mainAdmin) {
  public static UserView from(AppUser user) {
    return new UserView(user.getId(), user.getEmail(), user.getName(), user.getRole(),
        user.getPhoneNumber(), user.getBirthDate(), user.isProfileComplete(), user.isMainAdmin());
  }
}
