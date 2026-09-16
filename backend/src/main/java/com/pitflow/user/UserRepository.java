package com.pitflow.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<AppUser, UUID> {
  Optional<AppUser> findByEmail(String email);

  boolean existsByEmail(String email);

  List<AppUser> findAllByRole(AppUser.Role role);
}
