package com.pitflow.vehicle;

import com.pitflow.common.ApiException;
import com.pitflow.user.UserRepository;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class VehicleService {
  private final VehicleRepository vehicles;
  private final UserRepository users;
  private final org.springframework.jdbc.core.JdbcTemplate db;

  public VehicleService(
      VehicleRepository vehicles,
      UserRepository users,
      org.springframework.jdbc.core.JdbcTemplate db) {
    this.vehicles = vehicles;
    this.users = users;
    this.db = db;
  }

  private UUID ownerId(String email) {
    return users
        .findByEmail(email)
        .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."))
        .getId();
  }

  private Vehicle owned(String email, UUID id) {
    return vehicles
        .findByIdAndOwnerId(id, ownerId(email))
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "차량을 찾을 수 없습니다."));
  }

  public List<VehicleView> list(String email) {
    return vehicles.findAllByOwnerIdOrderByCreatedAtDesc(ownerId(email)).stream()
        .map(VehicleView::from)
        .toList();
  }

  @Transactional
  public VehicleView create(String email, VehicleRequest request) {
    return VehicleView.from(vehicles.saveAndFlush(new Vehicle(ownerId(email), request)));
  }

  @Transactional
  public VehicleView update(String email, UUID id, VehicleRequest request) {
    var v =
        vehicles
            .lockOwned(id, ownerId(email))
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "차량을 찾을 수 없습니다."));
    Integer recorded =
        db.queryForObject(
            "SELECT COALESCE(MAX(received_mileage),0) FROM work_orders WHERE vehicle_id=?",
            Integer.class,
            id);
    if (request.mileage() < recorded) {
      throw new ApiException(HttpStatus.CONFLICT, "정비 입고 기록보다 낮은 주행거리로 변경할 수 없습니다.");
    }
    v.update(request);
    vehicles.flush();
    return VehicleView.from(v);
  }

  @Transactional
  public void delete(String email, UUID id) {
    var vehicle =
        vehicles
            .lockOwned(id, ownerId(email))
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "차량을 찾을 수 없습니다."));
    if (Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM appointments WHERE vehicle_id = ?)",
            Boolean.class,
            id))) {
      throw new ApiException(HttpStatus.CONFLICT, "예약 이력이 있는 차량은 삭제할 수 없습니다.");
    }
    vehicles.delete(vehicle);
    vehicles.flush();
  }
}
