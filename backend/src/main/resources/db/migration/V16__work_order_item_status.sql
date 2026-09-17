ALTER TABLE work_order_items ADD COLUMN status VARCHAR(20);
ALTER TABLE work_order_items ADD COLUMN skip_reason VARCHAR(900);

UPDATE work_order_items
SET status = CASE WHEN done THEN 'COMPLETED' ELSE 'PENDING' END;

ALTER TABLE work_order_items ALTER COLUMN status SET DEFAULT 'PENDING';
ALTER TABLE work_order_items ALTER COLUMN status SET NOT NULL;

ALTER TABLE work_order_items
    ADD CONSTRAINT chk_work_order_item_status
    CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','WAITING_PARTS','SKIPPED'));

ALTER TABLE work_order_items
    ADD CONSTRAINT chk_work_order_item_skip_reason
    CHECK (
        (status = 'SKIPPED'
            AND skip_reason IS NOT NULL
            AND length(trim(skip_reason)) > 0)
        OR
        (status <> 'SKIPPED'
            AND skip_reason IS NULL)
    );

ALTER TABLE work_order_items
    ADD CONSTRAINT chk_work_order_item_done_status
    CHECK (
        (status = 'COMPLETED' AND done = TRUE)
        OR
        (status <> 'COMPLETED' AND done = FALSE)
    );