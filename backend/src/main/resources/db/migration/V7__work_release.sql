ALTER TABLE work_orders ADD COLUMN released_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE work_orders ADD CONSTRAINT released_only_completed CHECK (released_at IS NULL OR status='COMPLETED');
