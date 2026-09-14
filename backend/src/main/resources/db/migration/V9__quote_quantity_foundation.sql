-- Phase 1 foundation only. Existing bookings/work orders keep quantity=1 and
-- historical bookings remain explicitly "parts quote not captured" until new code writes snapshots.

ALTER TABLE service_part_requirements
    ADD COLUMN required_quantity NUMERIC(14,3);

ALTER TABLE service_part_requirements
    ADD COLUMN quantity_confirmed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE appointment_items
    ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1
        CHECK (quantity > 0 AND quantity <= 16);

ALTER TABLE appointment_items
    ADD COLUMN parts_quote_captured BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE work_order_items
    ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1
        CHECK (quantity > 0 AND quantity <= 16);

-- Booking-time part-price snapshot. No rows are backfilled for legacy appointments:
-- absence + parts_quote_captured=FALSE means the historical part estimate was never recorded.
CREATE TABLE appointment_item_parts (
    appointment_id UUID NOT NULL,
    service_item_id UUID NOT NULL,
    part_id UUID NOT NULL REFERENCES parts(id),
    part_name VARCHAR(120) NOT NULL,
    unit VARCHAR(12) NOT NULL,
    required_quantity_per_service NUMERIC(14,3),
    total_quantity NUMERIC(14,3),

    unit_price NUMERIC(12,0) NOT NULL
        CHECK (unit_price >= 0),
    amount NUMERIC(14,0) NOT NULL
        CHECK (amount >= 0),
    charge_policy VARCHAR(20) NOT NULL DEFAULT 'STANDARD'
        CHECK (charge_policy IN ('STANDARD','COMPLIMENTARY')),

    CONSTRAINT appointment_item_parts_quantity_policy CHECK (
        (
            required_quantity_per_service IS NOT NULL
            AND required_quantity_per_service > 0
            AND total_quantity IS NOT NULL
            AND total_quantity > 0
        )
        OR
        (
            charge_policy = 'COMPLIMENTARY'
            AND required_quantity_per_service IS NULL
            AND total_quantity IS NULL
        )
    ),

    PRIMARY KEY (appointment_id, service_item_id, part_id),
    FOREIGN KEY (appointment_id, service_item_id)
        REFERENCES appointment_items(appointment_id, service_item_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_appointment_item_parts_part
    ON appointment_item_parts(part_id);
