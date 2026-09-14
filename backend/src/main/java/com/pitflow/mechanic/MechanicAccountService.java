package com.pitflow.mechanic;

import com.pitflow.common.ApiException;
import com.pitflow.mechanic.MechanicAccountRequests.Create;
import com.pitflow.user.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MechanicAccountService {
  private final JdbcTemplate db;
  private final UserRepository users;
  private final PasswordEncoder passwords;

  public MechanicAccountService(
      JdbcTemplate db, UserRepository users, PasswordEncoder passwords) {
    this.db = db;
    this.users = users;
    this.passwords = passwords;
  }

  public List<MechanicAccountView> list() {
    return db.query(
        """
        SELECT m.id,m.user_id,m.code,m.name,u.email,m.active
        FROM mechanics m LEFT JOIN users u ON u.id=m.user_id
        ORDER BY m.code
        """,
        (rs, row) ->
            new MechanicAccountView(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getBoolean("active")));
  }

  @Transactional
  public MechanicAccountView create(Create request) {
    String email = request.email().strip().toLowerCase(Locale.ROOT);
    if (request.password().getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ApiException(HttpStatus.BAD_REQUEST, "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
    if (users.existsByEmail(email))
      throw new ApiException(HttpStatus.CONFLICT, "이미 등록된 이메일입니다.");

    String code = request.code().strip();
    if (Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT COUNT(*) > 0 FROM mechanics WHERE code=?", Boolean.class, code)))
      throw new ApiException(HttpStatus.CONFLICT, "이미 등록된 정비사 코드입니다.");

    String name = request.name().strip();
    AppUser account =
        users.saveAndFlush(
            new AppUser(email, passwords.encode(request.password()), name, AppUser.Role.MECHANIC));
    UUID mechanic = UUID.randomUUID();
    db.update(
        "INSERT INTO mechanics (id,user_id,code,name,active) VALUES (?,?,?,?,?)",
        mechanic,
        account.getId(),
        code,
        name,
        request.active());
    return find(mechanic);
  }

  @Transactional
  public MechanicAccountView setActive(UUID mechanic, boolean active) {
    var rows = db.queryForList("SELECT id FROM mechanics WHERE id=? FOR UPDATE", mechanic);
    if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "정비사를 찾을 수 없습니다.");
    db.update("UPDATE mechanics SET active=? WHERE id=?", active, mechanic);
    return find(mechanic);
  }

  private MechanicAccountView find(UUID mechanic) {
    var rows =
        db.query(
            """
            SELECT m.id,m.user_id,m.code,m.name,u.email,m.active
            FROM mechanics m LEFT JOIN users u ON u.id=m.user_id
            WHERE m.id=?
            """,
            (rs, row) ->
                new MechanicAccountView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("email"),
                    rs.getBoolean("active")),
            mechanic);
    if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "정비사를 찾을 수 없습니다.");
    return rows.get(0);
  }
}
