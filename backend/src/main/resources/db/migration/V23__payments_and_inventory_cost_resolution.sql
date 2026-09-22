CREATE TABLE inventory_cost_resolutions (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES stock_operations(id),
    source_lot_id UUID NOT NULL REFERENCES inventory_cost_lots(id),
    part_id UUID NOT NULL REFERENCES parts(id),
    resolved_quantity NUMERIC(14,3) NOT NULL CHECK (resolved_quantity > 0),
    resolved_unit_cost NUMERIC(14,3) NOT NULL CHECK (resolved_unit_cost >= 0),
    actor_id UUID NOT NULL REFERENCES users(id),
    reason VARCHAR(500) NOT NULL CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(operation_id, source_lot_id)
);

ALTER TABLE inventory_cost_lots
    ADD COLUMN cost_resolution_id UUID UNIQUE REFERENCES inventory_cost_resolutions(id);

CREATE INDEX idx_inventory_cost_resolutions_part
    ON inventory_cost_resolutions(part_id, created_at, id);

CREATE TABLE payment_provider_orders (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL UNIQUE REFERENCES invoices(id),
    customer_id UUID NOT NULL REFERENCES users(id),
    provider VARCHAR(16) NOT NULL CHECK (provider IN ('TOSS')),
    provider_order_id VARCHAR(64) NOT NULL UNIQUE,
    customer_key VARCHAR(64) NOT NULL,
    amount NUMERIC(14,0) NOT NULL CHECK (amount > 0),
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('READY','CONFIRMING','DONE','REFUNDING','CANCELED')),
    provider_payment_key VARCHAR(200) UNIQUE,
    payment_record_id UUID UNIQUE REFERENCES payment_records(id),
    approved_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK ((status IN ('READY','CONFIRMING') AND payment_record_id IS NULL AND approved_at IS NULL)
        OR (status IN ('DONE','REFUNDING') AND provider_payment_key IS NOT NULL
            AND payment_record_id IS NOT NULL AND approved_at IS NOT NULL)
        OR (status='CANCELED' AND provider_payment_key IS NOT NULL
            AND payment_record_id IS NOT NULL AND approved_at IS NOT NULL
            AND cancelled_at IS NOT NULL))
);

CREATE INDEX idx_payment_provider_orders_payment
    ON payment_provider_orders(provider_payment_key);
