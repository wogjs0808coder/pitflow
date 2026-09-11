package com.pitflow.auth;

import com.pitflow.common.*;
import com.pitflow.user.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final UserRepository users;
  private final PasswordEncoder passwords;

  public AuthController(UserRepository users, PasswordEncoder passwords) {
    this.users = users;
    this.passwords = passwords;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  public UserView register(@Valid @RequestBody RegisterRequest request) {
    String email = request.email().strip().toLowerCase(Locale.ROOT);
    if (request.password().getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ApiException(HttpStatus.BAD_REQUEST, "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
    if (users.existsByEmail(email)) throw new ApiException(HttpStatus.CONFLICT, "이미 등록된 이메일입니다.");
    return UserView.from(
        users.saveAndFlush(
            new AppUser(
                email,
                passwords.encode(request.password()),
                request.name().strip(),
                AppUser.Role.CUSTOMER)));
  }

  @GetMapping("/me")
  public UserView me(Principal principal) {
    return UserView.from(
        users
            .findByEmail(principal.getName())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.")));
  }

  public record RegisterRequest(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 12, max = 64, message = "비밀번호는 12~64자로 입력해 주세요.") String password,
      @NotBlank @Size(max = 50) String name) {}
}
