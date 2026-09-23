package com.pitflow.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitflow.common.ApiError;
import com.pitflow.user.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService userDetailsService(UserRepository users, JdbcTemplate db) {
    return email -> {
      var u =
          users
              .findByEmail(email.strip().toLowerCase(Locale.ROOT))
              .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
      boolean enabled =
          (u.getRole() != AppUser.Role.ADMIN || u.isAdminActive())
              && (u.getRole() != AppUser.Role.MECHANIC
              || Boolean.TRUE.equals(
                  db.queryForObject(
                      "SELECT COUNT(*) > 0 FROM mechanics WHERE user_id=? AND active=TRUE",
                      Boolean.class,
                      u.getId())));
      return new SessionAccount(u, enabled);
    };
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, ObjectMapper json, UserRepository users, JdbcTemplate db)
      throws Exception {
    http.authorizeHttpRequests(
        a ->
            a.requestMatchers(
                    "/api/auth/csrf",
                    "/api/auth/register",
                    "/api/auth/find-id",
                    "/api/auth/reset-password",
                    "/api/auth/admin-register",
                    "/api/auth/login",
                    "/api/health",
                    "/api/health/ready",
                    "/error")
                .permitAll()
                .requestMatchers("/api/admin/**")
                .hasRole("ADMIN")
                .requestMatchers("/api/mechanic/**")
                .hasRole("MECHANIC")
                .anyRequest()
                .authenticated());
    http.formLogin(
        f ->
            f.loginProcessingUrl("/api/auth/login")
                .usernameParameter("email")
                .successHandler(
                    (req, res, auth) -> {
                      res.setContentType("application/json;charset=UTF-8");
                      json.writeValue(
                          res.getWriter(),
                          UserView.from(users.findByEmail(auth.getName()).orElseThrow()));
                    })
                .failureHandler(
                    (req, res, ex) -> {
                      res.setStatus(401);
                      res.setContentType("application/json;charset=UTF-8");
                      json.writeValue(res.getWriter(), new ApiError("이메일 또는 비밀번호를 확인해 주세요."));
                    }));
    http.logout(
        l ->
            l.logoutUrl("/api/auth/logout")
                .deleteCookies("PITFLOW_SESSION")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)));
    http.exceptionHandling(
        e ->
            e.authenticationEntryPoint(
                    (req, res, ex) -> {
                      res.setStatus(401);
                      res.setContentType("application/json;charset=UTF-8");
                      json.writeValue(res.getWriter(), new ApiError("로그인이 필요합니다."));
                    })
                .accessDeniedHandler(
                    (req, res, ex) -> {
                      res.setStatus(403);
                      res.setContentType("application/json;charset=UTF-8");
                      json.writeValue(res.getWriter(), new ApiError("접근 권한 또는 보안 토큰을 확인해 주세요."));
                    }));
    http.requestCache(c -> c.disable());
    http.addFilterAfter(new AccountSessionFilter(users, db, json), AnonymousAuthenticationFilter.class);
    // Keep the default session-based CSRF protection and session-fixation protection.
    return http.build();
  }

  private static final class SessionAccount extends User {
    private final UUID id;
    private final AppUser.Role role;
    private final String passwordHashAtLogin;

    private SessionAccount(AppUser account, boolean enabled) {
      super(account.getEmail(), account.getPasswordHash(), enabled, true, true, true,
          AuthorityUtils.createAuthorityList("ROLE_" + account.getRole().name()));
      id = account.getId();
      role = account.getRole();
      passwordHashAtLogin = account.getPasswordHash();
    }
  }

  private static final class AccountSessionFilter extends OncePerRequestFilter {
    private final UserRepository users;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    private AccountSessionFilter(UserRepository users, JdbcTemplate db, ObjectMapper json) {
      this.users = users;
      this.db = db;
      this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null && authentication.getPrincipal() instanceof SessionAccount session) {
        boolean valid = users.findById(session.id).map(account ->
            Objects.equals(account.getEmail(), session.getUsername())
                && account.getRole() == session.role
                && Objects.equals(account.getPasswordHash(), session.passwordHashAtLogin)
                && (account.getRole() != AppUser.Role.ADMIN || account.isAdminActive())
                && (account.getRole() != AppUser.Role.MECHANIC || Boolean.TRUE.equals(
                    db.queryForObject(
                        "SELECT COUNT(*) > 0 FROM mechanics WHERE user_id=? AND active=TRUE",
                        Boolean.class, account.getId())))).orElse(false);
        if (!valid) {
          var currentSession = request.getSession(false);
          if (currentSession != null) currentSession.invalidate();
          SecurityContextHolder.clearContext();
          response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
          response.setContentType("application/json;charset=UTF-8");
          json.writeValue(response.getWriter(), new ApiError("로그인이 필요합니다."));
          return;
        }
      }
      chain.doFilter(request, response);
    }
  }
}
