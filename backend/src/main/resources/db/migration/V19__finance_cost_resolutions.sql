CREATE TABLE work_order_cost_resolutions (
    id UUID PRIMARY KEY,
    work_order_id UUID NOT NULL REFERENCES work_orders(id),
    unresolved_parts_cost NUMERIC(14,0),
    labor_cost NUMERIC(14,0),
    reason VARCHAR(500) NOT NULL,
    resolved_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (unresolved_parts_cost IS NOT NULL OR labor_cost IS NOT NULL),
    CHECK (unresolved_parts_cost IS NULL OR unresolved_parts_cost >= 0),
    CHECK (labor_cost IS NULL OR labor_cost >= 0),
    CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 500)
);

CREATE INDEX idx_work_order_cost_resolutions_latest
    ON work_order_cost_resolutions(work_order_id, created_at DESC, id DESC);
