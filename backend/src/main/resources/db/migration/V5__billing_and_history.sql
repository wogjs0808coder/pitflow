ALTER TABLE work_orders ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;
UPDATE work_orders SET completed_at=(
    SELECT MAX(e.created_at) FROM work_order_events e
    WHERE e.work_order_id=work_orders.id AND e.event_type='STATUS' AND e.detail LIKE '%→ COMPLETED%'
) WHERE status='COMPLETED';
CREATE INDEX idx_work_completed ON work_orders(completed_at);

CREATE TABLE invoices (
    id UUID PRIMARY KEY,
    work_order_id UUID NOT NULL REFERENCES work_orders(id),
    active_work_order_id UUID UNIQUE REFERENCES work_orders(id),
    status VARCHAR(10) NOT NULL CHECK (status IN ('OPEN','VOID')),
    source_hash VARCHAR(64) NOT NULL,
    total NUMERIC(14,0) NOT NULL CHECK (total>=0),
    issued_by UUID NOT NULL REFERENCES users(id),
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    voided_at TIMESTAMP WITH TIME ZONE,
    void_reason VARCHAR(500),
    voided_by UUID REFERENCES users(id),
    CHECK ((status='OPEN' AND active_work_order_id IS NOT NULL AND active_work_order_id=work_order_id AND voided_at IS NULL)
        OR (status='VOID' AND active_work_order_id IS NULL AND voided_at IS NOT NULL AND void_reason IS NOT NULL AND voided_by IS NOT NULL))
);
CREATE INDEX idx_invoices_work ON invoices(work_order_id,issued_at);
CREATE TABLE invoice_items (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('LABOR','PART')),
    source_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    quantity NUMERIC(14,3) NOT NULL CHECK (quantity>0),
    unit VARCHAR(12) NOT NULL,
    unit_price NUMERIC(12,0) NOT NULL CHECK (unit_price>=0),
    amount NUMERIC(14,0) NOT NULL CHECK (amount>=0),
    UNIQUE(invoice_id,kind,source_id)
);
CREATE TABLE payment_records (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    operation_id UUID NOT NULL UNIQUE REFERENCES stock_operations(id),
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('PAYMENT','REVERSAL')),
    original_payment_id UUID UNIQUE REFERENCES payment_records(id),
    amount NUMERIC(14,0) NOT NULL CHECK (amount>0),
    method VARCHAR(12) NOT NULL CHECK (method IN ('CASH','CARD','TRANSFER')),
    reference VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK ((kind='PAYMENT' AND original_payment_id IS NULL) OR (kind='REVERSAL' AND original_payment_id IS NOT NULL))
);
CREATE INDEX idx_payments_invoice ON payment_records(invoice_id,created_at);
CREATE INDEX idx_payments_time ON payment_records(created_at);
