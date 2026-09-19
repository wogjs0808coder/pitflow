CREATE TABLE inventory_cost_lots (
    id UUID PRIMARY KEY,
    part_id UUID NOT NULL REFERENCES parts(id),
    source_movement_id UUID REFERENCES stock_movements(id),
    origin VARCHAR(24) NOT NULL
        CHECK (origin IN ('OPENING','RECEIPT','ADJUST_IN','LEGACY_RETURN')),
    original_quantity NUMERIC(14,3) NOT NULL CHECK (original_quantity > 0),
    remaining_quantity NUMERIC(14,3) NOT NULL
        CHECK (remaining_quantity >= 0 AND remaining_quantity <= original_quantity),
    purchase_unit_cost NUMERIC(14,3),
    cost_known BOOLEAN NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(source_movement_id),
    CHECK ((cost_known = TRUE AND purchase_unit_cost IS NOT NULL AND purchase_unit_cost >= 0)
        OR (cost_known = FALSE AND purchase_unit_cost IS NULL))
);

CREATE INDEX idx_inventory_cost_lots_fifo
    ON inventory_cost_lots(part_id, received_at, id);

CREATE TABLE inventory_cost_allocations (
    id UUID PRIMARY KEY,
    movement_id UUID NOT NULL REFERENCES stock_movements(id),
    lot_id UUID NOT NULL REFERENCES inventory_cost_lots(id),
    allocation_type VARCHAR(12) NOT NULL
        CHECK (allocation_type IN ('CONSUME','RESTORE')),
    source_allocation_id UUID REFERENCES inventory_cost_allocations(id),
    quantity NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
    purchase_unit_cost NUMERIC(14,3),
    cost_known BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(movement_id, lot_id, allocation_type),
    CHECK ((allocation_type = 'CONSUME' AND source_allocation_id IS NULL)
        OR (allocation_type = 'RESTORE' AND source_allocation_id IS NOT NULL)),
    CHECK ((cost_known = TRUE AND purchase_unit_cost IS NOT NULL AND purchase_unit_cost >= 0)
        OR (cost_known = FALSE AND purchase_unit_cost IS NULL))
);

CREATE INDEX idx_inventory_cost_allocations_source
    ON inventory_cost_allocations(source_allocation_id);

-- Existing on-hand stock has no trustworthy historical purchase cost. Reuse the
-- part UUID as the opening-lot UUID so this remains portable without UUID extensions.
INSERT INTO inventory_cost_lots
    (id,part_id,source_movement_id,origin,original_quantity,remaining_quantity,
     purchase_unit_cost,cost_known,received_at,created_at)
SELECT id,id,NULL,'OPENING',quantity,quantity,NULL,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM parts
WHERE quantity > 0;
