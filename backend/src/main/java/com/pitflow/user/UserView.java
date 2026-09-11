package com.pitflow.user;

import java.util.UUID;

public record UserView(UUID id, String email, String name, AppUser.Role role) {
  public static UserView from(AppUser user) {
    return new UserView(user.getId(), user.getEmail(), user.getName(), user.getRole());
  }
}
