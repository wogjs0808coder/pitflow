package com.pitflow.appointment;

import static com.pitflow.appointment.AppointmentModels.*;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AppointmentRepository {
  private final JdbcTemplate db;

  public AppointmentRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Bay> bays() {
    return db.query(
        "SELECT id, name FROM work_bays WHERE active = TRUE ORDER BY name, id",
        (r, n) -> new Bay(r.getObject("id", UUID.class), r.getString("name")));
  }

  Optional<Car> ownedCar(UUID id, UUID owner, boolean lock) {
    return db
        .query(
            "SELECT id, plate_number, manufacturer, model FROM vehicles WHERE id = ? AND owner_id ="
                + " ?"
                + (lock ? " FOR UPDATE" : ""),
            (r, n) ->
                new Car(
                    r.getObject("id", UUID.class),
                    r.getString("plate_number"),
                    r.getString("manufacturer") + " " + r.getString("model")),
            id,
            owner)
        .stream()
        .findFirst();
  }

  List<Item> catalog(List<UUID> ids) {
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    return db.query(
        "SELECT id AS service_item_id, name, labor_price, duration_minutes, 1 AS quantity FROM"
            + " service_items WHERE active = TRUE AND id IN ("
            + placeholders
            + ") ORDER BY id",
        AppointmentRepository::item,
        ids.toArray());
  }

  List<QuoteServiceRow> quoteServices(List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    return db.query(
        "SELECT id,name,labor_price,duration_minutes,requirements_confirmed FROM service_items"
            + " WHERE active=TRUE AND id IN ("
            + placeholders
            + ") ORDER BY id",
        (r, n) ->
            new QuoteServiceRow(
                r.getObject("id", UUID.class),
                r.getString("name"),
                r.getBigDecimal("labor_price"),
                r.getInt("duration_minutes"),
                r.getBoolean("requirements_confirmed")),
        ids.toArray());
  }

  List<QuoteRequirementRow> quoteRequirements(List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    return db.query(
        "SELECT r.service_id,p.id AS part_id,p.name AS part_name,p.unit,r.required_quantity,"
            + "p.unit_price,p.active,p.archived,r.quantity_confirmed FROM service_part_requirements r"
            + " JOIN parts p ON p.id=r.part_id WHERE r.service_id IN ("
            + placeholders
            + ") ORDER BY r.service_id,p.id",
        (r, n) ->
            new QuoteRequirementRow(
                r.getObject("service_id", UUID.class),
                r.getObject("part_id", UUID.class),
                r.getString("part_name"),
                r.getString("unit"),
                r.getBigDecimal("required_quantity"),
                r.getBigDecimal("unit_price"),
                r.getBoolean("active"),
                r.getBoolean("archived"),
                r.getBoolean("quantity_confirmed")),
        ids.toArray());
  }

  List<QuoteConflictRow> quoteConflicts(List<UUID> ids) {
    if (ids.size() < 2) return List.of();
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    Object[] args = new Object[ids.size() * 2];
    for (int i = 0; i < ids.size(); i++) {
      args[i] = ids.get(i);
      args[i + ids.size()] = ids.get(i);
    }
    return db.query(
        "SELECT service_id_a,service_id_b,reason FROM service_selection_conflicts"
            + " WHERE service_id_a IN ("
            + placeholders
            + ") AND service_id_b IN ("
            + placeholders
            + ") ORDER BY service_id_a,service_id_b",
        (r, n) ->
            new QuoteConflictRow(
                r.getObject("service_id_a", UUID.class),
                r.getObject("service_id_b", UUID.class),
                r.getString("reason")),
        args);
  }

  List<Occupied> occupied(OffsetDateTime from, OffsetDateTime to) {
    return db.query(
        "SELECT work_bay_id, vehicle_id, starts_at FROM slot_allocations WHERE starts_at >= ? AND"
            + " starts_at < ?",
        (r, n) ->
            new Occupied(
                r.getObject("work_bay_id", UUID.class),
                r.getObject("vehicle_id", UUID.class),
                time(r, "starts_at").toInstant()),
        from,
        to);
  }

  void insert(
      UUID id,
      UUID customer,
      Car car,
      UUID bay,
      OffsetDateTime start,
      List<Item> items,
      String notes,
      Instant now) {
    int minutes =
        items.stream().mapToInt(item -> item.durationMinutes() * item.quantity()).sum();
    var total =
        items.stream()
            .map(item -> item.laborPrice().multiply(java.math.BigDecimal.valueOf(item.quantity())))
            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    var timestamp = now.atOffset(ZoneOffset.UTC);
    db.update(
        """
INSERT INTO appointments (id, customer_id, vehicle_id, work_bay_id, plate_number, vehicle_label,
starts_at, ends_at, status, notes, total_labor_price, duration_minutes, created_at, updated_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?)
""",
        id,
        customer,
        car.id(),
        bay,
        car.plateNumber(),
        car.label(),
        start,
        start.plusMinutes(minutes),
        notes,
        total,
        minutes,
        timestamp,
        timestamp);
    for (Item item : items) {
      db.update(
          "INSERT INTO appointment_items (appointment_id, service_item_id, name, labor_price,"
              + " duration_minutes, quantity) VALUES (?, ?, ?, ?, ?, ?)",
          id,
          item.serviceId(),
          item.name(),
          item.laborPrice(),
          item.durationMinutes(),
          item.quantity());
    }
    for (int minute = 0; minute < minutes; minute += BookingPolicy.SLOT_MINUTES) {
      db.update(
          "INSERT INTO slot_allocations (appointment_id, work_bay_id, vehicle_id, starts_at) VALUES"
              + " (?, ?, ?, ?)",
          id,
          bay,
          car.id(),
          start.plusMinutes(minute));
    }
  }

  private static final String SELECT =
      """
SELECT a.*, b.name AS work_bay_name, u.name AS customer_name, u.email AS customer_email
FROM appointments a JOIN work_bays b ON b.id = a.work_bay_id JOIN users u ON u.id = a.customer_id
""";

  Optional<Row> find(UUID id, UUID customer) {
    return db
        .query(
            SELECT + " WHERE a.id = ?" + (customer == null ? "" : " AND a.customer_id = ?"),
            AppointmentRepository::row,
            customer == null ? new Object[] {id} : new Object[] {id, customer})
        .stream()
        .findFirst();
  }

  boolean lock(UUID id, UUID customer) {
    return !db.query(
            "SELECT id FROM appointments WHERE id = ?"
                + (customer == null ? "" : " AND customer_id = ?")
                + " FOR UPDATE",
            (r, n) -> r.getObject(1, UUID.class),
            customer == null ? new Object[] {id} : new Object[] {id, customer})
        .isEmpty();
  }

  List<Row> list(UUID customer, OffsetDateTime from, OffsetDateTime to) {
    return db.query(
        SELECT
            + " WHERE a.starts_at >= ? AND a.starts_at < ?"
            + (customer == null ? "" : " AND a.customer_id = ?")
            + " ORDER BY a.starts_at, b.name, a.id",
        AppointmentRepository::row,
        customer == null ? new Object[] {from, to} : new Object[] {from, to, customer});
  }

  Map<UUID, List<Item>> items(List<UUID> ids) {
    Map<UUID, List<Item>> result = new HashMap<>();
    if (ids.isEmpty()) return result;
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    db.query(
        "SELECT * FROM appointment_items WHERE appointment_id IN ("
            + placeholders
            + ") ORDER BY name, service_item_id",
        (org.springframework.jdbc.core.RowCallbackHandler)
            r ->
                result
                    .computeIfAbsent(
                        r.getObject("appointment_id", UUID.class), key -> new ArrayList<>())
                    .add(item(r, 0)),
        ids.toArray());
    return result;
  }

  void change(UUID id, Status status, Instant now) {
    db.update(
        "UPDATE appointments SET status = ?, updated_at = ? WHERE id = ?",
        status.name(),
        now.atOffset(ZoneOffset.UTC),
        id);
    if (status == Status.CANCELLED || status == Status.NO_SHOW) {
      db.update("DELETE FROM slot_allocations WHERE appointment_id = ?", id);
    }
  }

  private static Item item(ResultSet r, int n) throws SQLException {
    return new Item(
        r.getObject("service_item_id", UUID.class),
        r.getString("name"),
        r.getBigDecimal("labor_price"),
        r.getInt("duration_minutes"),
        r.getInt("quantity"));
  }

  private static OffsetDateTime time(ResultSet r, String field) throws SQLException {
    return r.getObject(field, OffsetDateTime.class);
  }

  private static Row row(ResultSet r, int n) throws SQLException {
    return new Row(
        r.getObject("id", UUID.class),
        r.getObject("customer_id", UUID.class),
        r.getObject("vehicle_id", UUID.class),
        r.getString("plate_number"),
        r.getString("vehicle_label"),
        r.getObject("work_bay_id", UUID.class),
        r.getString("work_bay_name"),
        r.getString("customer_name"),
        r.getString("customer_email"),
        time(r, "starts_at"),
        time(r, "ends_at"),
        Status.valueOf(r.getString("status")),
        r.getString("notes"),
        r.getBigDecimal("total_labor_price"),
        r.getInt("duration_minutes"));
  }
}
