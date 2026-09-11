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

  public VehicleService(VehicleRepository vehicles, UserRepository users) {
    this.vehicles = vehicles;
    this.users = users;
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
    var v = owned(email, id);
    v.update(request);
    vehicles.flush();
    return VehicleView.from(v);
  }

  @Transactional
  public void delete(String email, UUID id) {
    vehicles.delete(owned(email, id));
    vehicles.flush();
  }
}
