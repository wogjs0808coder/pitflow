CREATE TABLE mechanics (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE work_orders (
    id UUID PRIMARY KEY,
    appointment_id UUID NOT NULL UNIQUE REFERENCES appointments(id),
    customer_id UUID NOT NULL REFERENCES users(id),
    vehicle_id UUID NOT NULL REFERENCES vehicles(id),
    vehicle_label VARCHAR(120) NOT NULL,
    plate_number VARCHAR(20) NOT NULL,
    received_mileage INTEGER NOT NULL CHECK (received_mileage BETWEEN 0 AND 9999999),
    mechanic_id UUID NOT NULL REFERENCES mechanics(id),
    mechanic_name VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RECEIVED','IN_PROGRESS','WAITING_PARTS','COMPLETED','CANCELLED')),
    notes VARCHAR(1000) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_work_customer ON work_orders(customer_id, received_at);
CREATE INDEX idx_work_status ON work_orders(status, received_at);

CREATE TABLE work_order_items (
    id UUID PRIMARY KEY,
    work_order_id UUID NOT NULL REFERENCES work_orders(id),
    service_item_id UUID NOT NULL REFERENCES service_items(id),
    name VARCHAR(80) NOT NULL,
    labor_price NUMERIC(12,0) NOT NULL CHECK (labor_price >= 0),
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes > 0),
    done BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE(work_order_id, service_item_id)
);

CREATE TABLE work_order_events (
    id UUID PRIMARY KEY,
    work_order_id UUID NOT NULL REFERENCES work_orders(id),
    actor_id UUID NOT NULL REFERENCES users(id),
    event_type VARCHAR(30) NOT NULL,
    detail VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_work_events ON work_order_events(work_order_id, created_at);

-- Every mutating phase-three command is identified by a durable key.
-- The reservation of the key, business writes and response commit atomically.
CREATE TABLE stock_operations (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES users(id),
    request_hash VARCHAR(64) NOT NULL,
    response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE parts (
    id UUID PRIMARY KEY,
    sku VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    unit VARCHAR(12) NOT NULL CHECK (unit IN ('EA','L','KG','M')),
    quantity NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    minimum_quantity NUMERIC(14,3) NOT NULL CHECK (minimum_quantity >= 0),
    unit_price NUMERIC(12,0) NOT NULL CHECK (unit_price >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

-- Append-only through the application. Corrections are new movements, never edits.
CREATE TABLE stock_movements (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES stock_operations(id),
    part_id UUID NOT NULL REFERENCES parts(id),
    work_order_id UUID REFERENCES work_orders(id),
    original_use_id UUID REFERENCES stock_movements(id),
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('RECEIPT','USE','RETURN')),
    quantity NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
    balance_after NUMERIC(14,3) NOT NULL CHECK (balance_after >= 0),
    part_name VARCHAR(120) NOT NULL,
    unit VARCHAR(12) NOT NULL,
    unit_price NUMERIC(12,0) NOT NULL CHECK (unit_price >= 0),
    actor_id UUID NOT NULL REFERENCES users(id),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK ((kind = 'RECEIPT' AND work_order_id IS NULL AND original_use_id IS NULL)
        OR (kind = 'USE' AND work_order_id IS NOT NULL AND original_use_id IS NULL)
        OR (kind = 'RETURN' AND work_order_id IS NOT NULL AND original_use_id IS NOT NULL))
);
CREATE INDEX idx_movements_part ON stock_movements(part_id, created_at);
CREATE INDEX idx_movements_work ON stock_movements(work_order_id, created_at);
CREATE INDEX idx_movements_original ON stock_movements(original_use_id);
