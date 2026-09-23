package com.pitflow.auth;

import com.pitflow.common.ApiException;
import com.pitflow.user.*;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final JdbcTemplate db;
  private final Validator validator;
  private final String inviteCode;

  public AccountService(UserRepository users, PasswordEncoder passwords, JdbcTemplate db, Validator validator,
      @Value("${pitflow.bootstrap.admin-invite-code:}") String inviteCode) {
    this.users = users;
    this.passwords = passwords;
    this.db = db;
    this.validator = validator;
    this.inviteCode = inviteCode;
  }

  public AppUser current(Principal principal) {
    if (principal == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
    return users.findByEmail(principal.getName())
        .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
  }

  public AppUser requireMain(Principal principal) {
    var user = current(principal);
    if (!user.isMainAdmin() || !user.isAdminActive())
      throw new ApiException(HttpStatus.FORBIDDEN, "메인 관리자만 사용할 수 있습니다.");
    return user;
  }

  public static String email(String value) { return value.strip().toLowerCase(Locale.ROOT); }

  private String validatedEmail(String value) {
    if (value == null) throw new ApiException(HttpStatus.BAD_REQUEST, "이메일을 입력해 주세요.");
    String normalized = email(value);
    if (!validator.validate(new EmailValue(normalized)).isEmpty())
      throw new ApiException(HttpStatus.BAD_REQUEST, "이메일 형식을 확인해 주세요.");
    return normalized;
  }

  private record EmailValue(@NotBlank @Email @Size(max = 254) String value) {}

  public static String phone(String value) {
    if (value == null) throw new ApiException(HttpStatus.BAD_REQUEST, "휴대폰 번호를 입력해 주세요.");
    String normalized = value.strip().replaceAll("[ -]", "");
    if (!normalized.matches("01[0-9]{8,9}"))
      throw new ApiException(HttpStatus.BAD_REQUEST, "휴대폰 번호 형식을 확인해 주세요.");
    return normalized;
  }

  public static void passwordPolicy(String value) {
    if (value == null || value.codePointCount(0, value.length()) < 7
        || value.codePointCount(0, value.length()) > 20
        || value.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ApiException(HttpStatus.BAD_REQUEST, "비밀번호는 7~20자, UTF-8 기준 72바이트 이하여야 합니다.");
  }

  public static void birthDate(LocalDate value) {
    if (value == null || value.isAfter(LocalDate.now()))
      throw new ApiException(HttpStatus.BAD_REQUEST, "생년월일을 확인해 주세요.");
  }

  public static String name(String value) {
    if (value == null || value.strip().isEmpty() || value.strip().length() > 50)
      throw new ApiException(HttpStatus.BAD_REQUEST, "이름을 확인해 주세요.");
    return value.strip();
  }

  private void available(String email, String phone) {
    if (users.existsByEmail(email)) throw new ApiException(HttpStatus.CONFLICT, "이미 등록된 이메일입니다.");
    if (users.existsByPhoneNumber(phone)) throw new ApiException(HttpStatus.CONFLICT, "이미 등록된 휴대폰 번호입니다.");
  }

  @Transactional
  public UserView registerCustomer(String email, String password, String name, String phone, LocalDate birthDate) {
    passwordPolicy(password);
    birthDate(birthDate);
    String normalizedPhone = phone(phone);
    String normalizedEmail = email(email);
    available(normalizedEmail, normalizedPhone);
    var user = new AppUser(normalizedEmail, passwords.encode(password), name(name), AppUser.Role.CUSTOMER);
    user.updateProfile(user.getName(), normalizedPhone, birthDate);
    return UserView.from(save(user));
  }

  private AppUser save(AppUser user) {
    try { return users.saveAndFlush(user); }
    catch (DataIntegrityViolationException exception) {
      throw new ApiException(HttpStatus.CONFLICT, "이메일 또는 휴대폰 번호가 이미 등록되었습니다.");
    }
  }

  public String findId(String name, String phone, LocalDate birthDate) {
    return verifiedIdentity(name, phone, birthDate).getEmail();
  }

  private AppUser verifiedIdentity(String name, String phone, LocalDate birthDate) {
    if (birthDate == null || name == null)
      throw new ApiException(HttpStatus.BAD_REQUEST, "계정 확인 정보를 입력해 주세요.");
    var user = users.findByPhoneNumber(phone(phone));
    if (user.isEmpty() || !user.get().getName().equals(name.strip())
        || !birthDate.equals(user.get().getBirthDate())
        || (user.get().getRole() == AppUser.Role.ADMIN && !user.get().isAdminActive()))
      throw new ApiException(HttpStatus.NOT_FOUND, "일치하는 계정을 찾을 수 없습니다.");
    return user.get();
  }

  @Transactional
  public void resetPassword(String name, String phone, LocalDate birthDate, String newPassword) {
    passwordPolicy(newPassword);
    var user = verifiedIdentity(name, phone, birthDate);
    user.changePassword(passwords.encode(newPassword));
    if (user.getRole() == AppUser.Role.ADMIN) audit(null, user, "PASSWORD_RESET", "계정 확인 정보로 비밀번호 재설정");
  }

  @Transactional
  public UserView completeProfile(Principal principal, String name, String phone, LocalDate birthDate) {
    var user = current(principal);
    if (user.getRole() == AppUser.Role.MECHANIC || user.isProfileComplete())
      throw new ApiException(HttpStatus.CONFLICT, "최초 정보 등록 대상이 아닙니다.");
    birthDate(birthDate);
    String normalized = phone(phone);
    if (users.findByPhoneNumber(normalized).filter(other -> !other.getId().equals(user.getId())).isPresent())
      throw new ApiException(HttpStatus.CONFLICT, "이미 등록된 휴대폰 번호입니다.");
    user.updateProfile(name(name), normalized, birthDate);
    if (user.getRole() == AppUser.Role.ADMIN) audit(user, user, "PROFILE_UPDATED", "최초 계정 정보 등록");
    return UserView.from(save(user));
  }

  @Transactional
  public UserView updateProfile(Principal principal, String currentPassword, String name,
      String phone, LocalDate birthDate, String email) {
    var user = current(principal);
    if (user.getRole() == AppUser.Role.MECHANIC)
      throw new ApiException(HttpStatus.FORBIDDEN, "정비사는 이 계정 정보를 수정할 수 없습니다.");
    verifyPassword(user, currentPassword);
    birthDate(birthDate);
    String normalized = phone(phone);
    if (users.findByPhoneNumber(normalized).filter(other -> !other.getId().equals(user.getId())).isPresent())
      throw new ApiException(HttpStatus.CONFLICT, "이미 등록된 휴대폰 번호입니다.");
    boolean emailChanged = changeEmail(user, email);
    user.updateProfile(name(name), normalized, birthDate);
    save(user);
    if (user.getRole() == AppUser.Role.ADMIN) audit(user, user, "PROFILE_UPDATED", "관리자 개인정보 변경");
    if (emailChanged && user.getRole() == AppUser.Role.ADMIN)
      audit(user, user, "EMAIL_CHANGED", "관리자 로그인 이메일 변경");
    return UserView.from(user);
  }

  @Transactional
  public UserView changeMechanicEmail(Principal principal, String currentPassword, String email) {
    var user = current(principal);
    if (user.getRole() != AppUser.Role.MECHANIC)
      throw new ApiException(HttpStatus.FORBIDDEN, "정비사 계정에서만 사용할 수 있습니다.");
    verifyPassword(user, currentPassword);
    changeEmail(user, email);
    return UserView.from(save(user));
  }

  private boolean changeEmail(AppUser user, String requested) {
    if (requested == null) return false;
    String normalized = validatedEmail(requested);
    if (normalized.equals(user.getEmail())) return false;
    if (users.existsByEmail(normalized))
      throw new ApiException(HttpStatus.CONFLICT, "이미 등록된 이메일입니다.");
    user.changeEmail(normalized);
    return true;
  }

  @Transactional
  public void changePassword(Principal principal, String currentPassword, String newPassword) {
    var user = current(principal);
    verifyPassword(user, currentPassword);
    passwordPolicy(newPassword);
    user.changePassword(passwords.encode(newPassword));
    if (user.getRole() == AppUser.Role.ADMIN) audit(user, user, "PASSWORD_CHANGED", "관리자 비밀번호 변경");
  }

  private void verifyPassword(AppUser user, String value) {
    if (value == null || !passwords.matches(value, user.getPasswordHash()))
      throw new ApiException(HttpStatus.FORBIDDEN, "현재 비밀번호가 일치하지 않습니다.");
  }

  @Transactional
  public UserView selfRegisterAdmin(String email, String password, String phone,
      LocalDate birthDate, String code) {
    if (inviteCode.isBlank() || code == null || !MessageDigest.isEqual(
        inviteCode.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8)))
      throw new ApiException(HttpStatus.FORBIDDEN, "관리자 가입을 허용할 수 없습니다.");
    return createAdmin(email, password, phone, birthDate, null, "SELF_REGISTERED");
  }

  @Transactional
  public UserView createAdminByMain(Principal principal, String email, String password,
      String phone, LocalDate birthDate) {
    return createAdmin(email, password, phone, birthDate, requireMain(principal), "CREATED");
  }

  private UserView createAdmin(String email, String password, String phone, LocalDate birthDate,
      AppUser actor, String action) {
    passwordPolicy(password);
    birthDate(birthDate);
    String normalizedEmail = email(email);
    String normalizedPhone = phone(phone);
    available(normalizedEmail, normalizedPhone);
    Long next = db.queryForObject("SELECT next_number FROM admin_account_sequence WHERE id=1 FOR UPDATE", Long.class);
    if (next == null) throw new IllegalStateException("Administrator sequence missing");
    db.update("UPDATE admin_account_sequence SET next_number=? WHERE id=1", next + 1);
    var user = new AppUser(normalizedEmail, passwords.encode(password), "관리자" + next, AppUser.Role.ADMIN);
    user.designateAdminNumber(next);
    user.updateProfile(user.getName(), normalizedPhone, birthDate);
    save(user);
    audit(actor, user, action, actor == null ? "관리자 직접 가입" : "메인 관리자가 관리자 생성");
    return UserView.from(user);
  }

  @Transactional
  public void deactivateAdmin(Principal principal, UUID targetId) {
    var actor = requireMain(principal);
    var target = users.findById(targetId)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "관리자 계정이 없습니다."));
    if (target.getRole() != AppUser.Role.ADMIN || target.isMainAdmin())
      throw new ApiException(HttpStatus.FORBIDDEN, "이 계정은 삭제할 수 없습니다.");
    if (!target.isAdminActive()) throw new ApiException(HttpStatus.CONFLICT, "이미 삭제된 계정입니다.");
    target.deactivateAdmin();
    audit(actor, target, "DEACTIVATED", "관리자 계정 비활성화");
  }

  public List<AdminAccountView> admins(Principal principal) {
    requireMain(principal);
    return users.findAllByRole(AppUser.Role.ADMIN).stream()
        .map(AdminAccountView::from)
        .sorted((a, b) -> a.mainAdmin() == b.mainAdmin() ?
            java.util.Comparator.nullsLast(Long::compareTo).compare(a.adminNumber(), b.adminNumber()) :
            a.mainAdmin() ? -1 :
            1)
        .toList();
  }

  public List<AdminAuditView> audit(Principal principal) {
    requireMain(principal);
    return db.query("""
        SELECT a.id,a.actor_id,a.target_id,a.action,a.detail,a.created_at,
               actor.name AS actor_name,target.name AS target_name
        FROM admin_account_audit a
        LEFT JOIN users actor ON actor.id=a.actor_id
        JOIN users target ON target.id=a.target_id
        ORDER BY a.created_at DESC
        """, (rs, row) -> new AdminAuditView(
        UUID.fromString(rs.getString("id")),
        rs.getString("actor_id") == null ? null : UUID.fromString(rs.getString("actor_id")),
        UUID.fromString(rs.getString("target_id")), rs.getString("actor_name"),
        rs.getString("target_name"), rs.getString("action"), rs.getString("detail"),
        rs.getTimestamp("created_at").toInstant()));
  }

  private void audit(AppUser actor, AppUser target, String action, String detail) {
    db.update("INSERT INTO admin_account_audit (id,actor_id,target_id,action,detail,created_at) VALUES (?,?,?,?,?,?)",
        UUID.randomUUID(), actor == null ? null : actor.getId(), target.getId(), action, detail,
        java.sql.Timestamp.from(Instant.now()));
  }

  public record AdminAccountView(UUID id, String name, String email, String phoneNumber,
      LocalDate birthDate, Instant createdAt, boolean mainAdmin, boolean active, Long adminNumber) {
    static AdminAccountView from(AppUser user) {
      return new AdminAccountView(user.getId(), user.getName(), user.getEmail(),
          user.getPhoneNumber(), user.getBirthDate(), user.getCreatedAt(),
          user.isMainAdmin(), user.isAdminActive(), user.getAdminNumber());
    }
  }

  public record AdminAuditView(UUID id, UUID actorId, UUID targetId, String actorName,
      String targetName, String action, String detail, Instant createdAt) {}
}
