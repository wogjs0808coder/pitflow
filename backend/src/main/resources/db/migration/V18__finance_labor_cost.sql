ALTER TABLE mechanics
    ADD COLUMN hourly_cost NUMERIC(14,0)
        CHECK (hourly_cost >= 0);

ALTER TABLE work_orders ADD COLUMN labor_minutes_snapshot INTEGER;
ALTER TABLE work_orders ADD COLUMN labor_hourly_cost_snapshot NUMERIC(14,0);
ALTER TABLE work_orders ADD COLUMN labor_cost_snapshot NUMERIC(14,0);
ALTER TABLE work_orders ADD COLUMN labor_cost_known BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE work_orders
    ADD CONSTRAINT work_order_labor_snapshot_v18 CHECK (
        (labor_cost_known = TRUE
            AND labor_minutes_snapshot IS NOT NULL
            AND labor_minutes_snapshot >= 0
            AND labor_hourly_cost_snapshot IS NOT NULL
            AND labor_hourly_cost_snapshot >= 0
            AND labor_cost_snapshot IS NOT NULL
            AND labor_cost_snapshot >= 0)
        OR
        (labor_cost_known = FALSE
            AND labor_hourly_cost_snapshot IS NULL
            AND labor_cost_snapshot IS NULL
            AND (labor_minutes_snapshot IS NULL OR labor_minutes_snapshot >= 0))
    );

CREATE INDEX idx_work_orders_finance_completed_v18
    ON work_orders(completed_at, id);
