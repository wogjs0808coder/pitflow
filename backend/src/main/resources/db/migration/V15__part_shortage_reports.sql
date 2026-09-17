CREATE TABLE part_shortage_reports (
    id UUID PRIMARY KEY,
    work_order_id UUID NOT NULL REFERENCES work_orders(id),
    work_order_item_id UUID NOT NULL REFERENCES work_order_items(id),
    part_id UUID NOT NULL REFERENCES parts(id),
    reporter_user_id UUID NOT NULL REFERENCES users(id),
    requested_quantity NUMERIC(14,3) NOT NULL CHECK (requested_quantity > 0),
    available_quantity_snapshot NUMERIC(14,3) NOT NULL CHECK (available_quantity_snapshot >= 0),
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','RESOLVED')),
    open_guard VARCHAR(1),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolved_by_user_id UUID REFERENCES users(id),
    CHECK ((status = 'OPEN' AND open_guard = 'Y' AND resolved_at IS NULL AND resolved_by_user_id IS NULL)
        OR (status = 'RESOLVED' AND open_guard IS NULL AND resolved_at IS NOT NULL AND resolved_by_user_id IS NOT NULL)),
    UNIQUE(work_order_id, work_order_item_id, part_id, open_guard)
);

CREATE INDEX idx_part_shortage_status_created
    ON part_shortage_reports(status, created_at);
