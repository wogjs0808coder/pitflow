ALTER TABLE work_orders ALTER COLUMN mechanic_id DROP NOT NULL;
ALTER TABLE work_orders ALTER COLUMN mechanic_name DROP NOT NULL;

ALTER TABLE work_orders ADD CONSTRAINT work_order_mechanic_pair_v13 CHECK (
    (mechanic_id IS NULL AND mechanic_name IS NULL)
    OR (mechanic_id IS NOT NULL AND mechanic_name IS NOT NULL)
);
