package com.pitflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.pitflow.catalog.*;
import com.pitflow.user.*;
import com.pitflow.vehicle.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhaseOneIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired VehicleRepository vehicles;
  @Autowired ServiceItemRepository services;
  @Autowired PasswordEncoder encoder;
  private static final String PASSWORD = "Test-password-2026!";

  @BeforeEach
  void setup() {
    vehicles.deleteAll();
    users.deleteAll();
    services.deleteAll();
    users.saveAndFlush(
        new AppUser("owner@example.com", encoder.encode(PASSWORD), "소유자", AppUser.Role.CUSTOMER));
    users.saveAndFlush(
        new AppUser("other@example.com", encoder.encode(PASSWORD), "다른 고객", AppUser.Role.CUSTOMER));
    users.saveAndFlush(
        new AppUser("admin@example.com", encoder.encode(PASSWORD), "관리자", AppUser.Role.ADMIN));
    services.saveAndFlush(
        new ServiceItem(
            new ServiceRequest("엔진오일 교체", "부품 별도", BigDecimal.valueOf(20000), 30, true)));
  }

  private String vehicleBody(String plate) {
    return "{\"plateNumber\":\""
        + plate
        + "\",\"manufacturer\":\"기아\",\"model\":\"K3\",\"modelYear\":2024,\"mileage\":26400}";
  }

  private UUID createVehicle() throws Exception {
    var result =
        mvc.perform(
                post("/api/vehicles")
                    .with(user("owner@example.com"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(vehicleBody("123가4567")))
            .andExpect(status().isCreated())
            .andReturn();
    return UUID.fromString(
        json.readTree(result.getResponse().getContentAsString()).get("id").asText());
  }

  @Test
  void unauthenticatedAndCsrfRequestsAreRejected() throws Exception {
    mvc.perform(get("/api/vehicles")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/vehicles")
                .with(user("owner@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(vehicleBody("12가3456")))
        .andExpect(status().isForbidden());
  }

  @Test
  void registrationHashesPasswordAndCannotAssignAdmin() throws Exception {
    String body =
        "{\"name\":\"신규 고객\",\"email\":\"NEW@example.com\",\"password\":\"" + PASSWORD + "\"}";
    mvc.perform(
            post("/api/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role").value("CUSTOMER"))
        .andExpect(jsonPath("$.passwordHash").doesNotExist());
    var saved = users.findByEmail("new@example.com").orElseThrow();
    assertThat(saved.getPasswordHash()).isNotEqualTo(PASSWORD);
    assertThat(encoder.matches(PASSWORD, saved.getPasswordHash())).isTrue();
    mvc.perform(
            post("/api/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.substring(0, body.length() - 1) + ",\"role\":\"ADMIN\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void realLoginRotatesSessionAndLogoutInvalidatesIt() throws Exception {
    var csrfResult = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
    var session = (MockHttpSession) csrfResult.getRequest().getSession(false);
    String oldId = session.getId();
    var token = json.readTree(csrfResult.getResponse().getContentAsString());
    var result =
        mvc.perform(
                post("/api/auth/login")
                    .session(session)
                    .header(token.get("headerName").asText(), token.get("token").asText())
                    .param("email", "OWNER@example.com")
                    .param("password", PASSWORD))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("owner@example.com"))
            .andReturn();
    var authenticated = (MockHttpSession) result.getRequest().getSession(false);
    assertThat(authenticated.getId()).isNotEqualTo(oldId);
    mvc.perform(get("/api/auth/me").session(authenticated)).andExpect(status().isOk());
    var refreshed = mvc.perform(get("/api/auth/csrf").session(authenticated)).andReturn();
    var freshToken = json.readTree(refreshed.getResponse().getContentAsString());
    mvc.perform(
            post("/api/auth/logout")
                .session(authenticated)
                .header(freshToken.get("headerName").asText(), freshToken.get("token").asText()))
        .andExpect(status().isNoContent());
    assertThat(authenticated.isInvalid()).isTrue();
    mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void wrongPasswordDoesNotAuthenticate() throws Exception {
    mvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .param("email", "owner@example.com")
                .param("password", "incorrect"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void otherCustomerCannotReadUpdateOrDeleteVehicle() throws Exception {
    UUID id = createVehicle();
    mvc.perform(get("/api/vehicles").with(user("other@example.com")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(
            put("/api/vehicles/" + id)
                .with(user("other@example.com"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(vehicleBody("12나1234")))
        .andExpect(status().isNotFound());
    mvc.perform(delete("/api/vehicles/" + id).with(user("other@example.com")).with(csrf()))
        .andExpect(status().isNotFound());
    assertThat(vehicles.findById(id)).isPresent();
  }

  @Test
  void ownerCanUpdateAndDeleteOwnVehicle() throws Exception {
    UUID id = createVehicle();
    mvc.perform(
            put("/api/vehicles/" + id)
                .with(user("owner@example.com"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(vehicleBody("123가4567").replace("26400", "27000")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mileage").value(27000));
    mvc.perform(delete("/api/vehicles/" + id).with(user("owner@example.com")).with(csrf()))
        .andExpect(status().isNoContent());
    assertThat(vehicles.findById(id)).isEmpty();
  }

  @Test
  void duplicatesAndInvalidMileageAreRejected() throws Exception {
    createVehicle();
    mvc.perform(
            post("/api/vehicles")
                .with(user("other@example.com"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(vehicleBody("123 가4567")))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/vehicles")
                .with(user("owner@example.com"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(vehicleBody("12나1234").replace("26400", "-1")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void adminCanCreateAndDisableServiceWhileCustomerCannot() throws Exception {
    String body =
        "{\"name\":\"배터리 교체\",\"description\":\"배터리"
            + " 별도\",\"laborPrice\":15000,\"durationMinutes\":30,\"active\":true}";
    mvc.perform(get("/api/admin/services").with(user("owner@example.com")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/admin/services")
                .with(user("owner@example.com"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    var result =
        mvc.perform(
                post("/api/admin/services")
                    .with(user("admin@example.com").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(
            put("/api/admin/services/" + id)
                .with(user("admin@example.com").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("true", "false")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/services").with(user("owner@example.com")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(get("/api/admin/services").with(user("admin@example.com").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void invalidServiceDurationRejected() throws Exception {
    String body =
        "{\"name\":\"작업\",\"description\":\"설명\",\"laborPrice\":1000,\"durationMinutes\":45,\"active\":true}";
    mvc.perform(
            post("/api/admin/services")
                .with(user("admin@example.com").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }
}
