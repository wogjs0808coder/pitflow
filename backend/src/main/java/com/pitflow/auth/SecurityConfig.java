package com.pitflow.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitflow.common.ApiError;
import com.pitflow.user.*;
import java.util.Locale;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService userDetailsService(UserRepository users) {
    return email -> {
      var u =
          users
              .findByEmail(email.strip().toLowerCase(Locale.ROOT))
              .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
      return User.withUsername(u.getEmail())
          .password(u.getPasswordHash())
          .roles(u.getRole().name())
          .build();
    };
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, ObjectMapper json, UserRepository users)
      throws Exception {
    http.authorizeHttpRequests(
        a ->
            a.requestMatchers(
                    "/api/auth/csrf",
                    "/api/auth/register",
                    "/api/auth/login",
                    "/api/health",
                    "/api/health/ready",
                    "/error")
                .permitAll()
                .requestMatchers("/api/admin/**")
                .hasRole("ADMIN")
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
    // Keep the default session-based CSRF protection and session-fixation protection.
    return http.build();
  }
}
