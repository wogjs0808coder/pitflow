package com.pitflow.mechanic;

import com.pitflow.common.ApiException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MechanicIdentityService {
  private final JdbcTemplate db;

  public MechanicIdentityService(JdbcTemplate db) {
    this.db = db;
  }

  public UUID requireActiveProfile(String email) {
    var profiles =
        db.query(
            """
            SELECT m.id
            FROM users u
            JOIN mechanics m ON m.user_id=u.id
            WHERE u.email=? AND u.role='MECHANIC' AND m.active=TRUE
            """,
            (rs, row) -> rs.getObject("id", UUID.class),
            email.strip().toLowerCase(Locale.ROOT));
    if (profiles.size() != 1)
      throw new ApiException(HttpStatus.FORBIDDEN, "활성 정비사 계정이 필요합니다.");
    return profiles.get(0);
  }
}
