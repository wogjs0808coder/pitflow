CREATE TABLE work_bays (
    id UUID PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE appointments (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES users(id),
    vehicle_id UUID NOT NULL REFERENCES vehicles(id),
    work_bay_id UUID NOT NULL REFERENCES work_bays(id),
    plate_number VARCHAR(20) NOT NULL,
    vehicle_label VARCHAR(120) NOT NULL,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'VISITED', 'NO_SHOW')),
    notes VARCHAR(500) NOT NULL DEFAULT '',
    total_labor_price NUMERIC(14, 0) NOT NULL CHECK (total_labor_price >= 0),
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes BETWEEN 30 AND 480 AND MOD(duration_minutes, 30) = 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (ends_at > starts_at),
    UNIQUE (id, work_bay_id, vehicle_id)
);
CREATE INDEX idx_appointments_customer_start ON appointments(customer_id, starts_at);
CREATE INDEX idx_appointments_start ON appointments(starts_at);
CREATE INDEX idx_appointments_vehicle ON appointments(vehicle_id);

-- Freeze the quoted service name, labor fee and duration at booking time.
CREATE TABLE appointment_items (
    appointment_id UUID NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    service_item_id UUID NOT NULL REFERENCES service_items(id),
    name VARCHAR(80) NOT NULL,
    labor_price NUMERIC(12, 0) NOT NULL CHECK (labor_price >= 0),
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes BETWEEN 30 AND 480 AND MOD(duration_minutes, 30) = 0),
    PRIMARY KEY (appointment_id, service_item_id)
);

-- Both constraints are authoritative even with multiple backend instances.
-- A car cannot occupy two bays at the same time. Adjacent bookings are allowed.
CREATE TABLE slot_allocations (
    appointment_id UUID NOT NULL,
    work_bay_id UUID NOT NULL,
    vehicle_id UUID NOT NULL,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (work_bay_id, starts_at),
    UNIQUE (vehicle_id, starts_at),
    FOREIGN KEY (appointment_id, work_bay_id, vehicle_id)
        REFERENCES appointments(id, work_bay_id, vehicle_id) ON DELETE CASCADE,
    CHECK (EXTRACT(MINUTE FROM starts_at) IN (0, 30) AND EXTRACT(SECOND FROM starts_at) = 0)
);
CREATE INDEX idx_slots_appointment ON slot_allocations(appointment_id);
CREATE INDEX idx_slots_start ON slot_allocations(starts_at);

-- Two demonstration work bays; operating hours live in application.yml.
INSERT INTO work_bays (id, name, active) VALUES
('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1', '작업 공간 1', TRUE),
('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2', '작업 공간 2', TRUE);
