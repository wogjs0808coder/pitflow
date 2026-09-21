CREATE TABLE treasury_ledger_v22 (
    id UUID PRIMARY KEY,
    event_group_id UUID NOT NULL,
    account_type VARCHAR(16) NOT NULL REFERENCES treasury_accounts(account_type),
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN (
        'OPENING_ALLOCATION','REBALANCE','DEPOSIT_INTEREST','INVESTMENT_RETURN',
        'CUSTOMER_PAYMENT','PAYMENT_REFUND','OPERATING_EXPENSE','OPERATING_INCOME',
        'PAYROLL_PAYMENT','INVENTORY_PURCHASE')),
    amount_delta NUMERIC(19,0) NOT NULL,
    balance_after NUMERIC(19,0) NOT NULL CHECK (balance_after >= 0),
    reason VARCHAR(500) NOT NULL
        CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 500),
    source_type VARCHAR(32),
    source_id UUID,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK ((source_type IS NULL AND source_id IS NULL)
        OR (source_type IS NOT NULL AND source_id IS NOT NULL)),
    UNIQUE (event_type,source_type,source_id,account_type)
);

INSERT INTO treasury_ledger_v22
    (id,event_group_id,account_type,event_type,amount_delta,balance_after,
     reason,source_type,source_id,created_by,created_at)
SELECT id,event_group_id,account_type,event_type,amount_delta,balance_after,
       reason,NULL,NULL,created_by,created_at
FROM treasury_ledger;

DROP TABLE treasury_ledger;
ALTER TABLE treasury_ledger_v22 RENAME TO treasury_ledger;

CREATE INDEX idx_treasury_ledger_event
    ON treasury_ledger(event_group_id,account_type);

CREATE INDEX idx_treasury_ledger_created
    ON treasury_ledger(created_at,id);

ALTER TABLE finance_entries
    ADD COLUMN affects_treasury BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE treasury_simulation_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    activation_date DATE,
    last_settled_date DATE,
    updated_at TIMESTAMP WITH TIME ZONE,
    CHECK ((activation_date IS NULL AND last_settled_date IS NULL)
        OR (activation_date IS NOT NULL AND last_settled_date IS NOT NULL
            AND last_settled_date >= activation_date))
);

INSERT INTO treasury_simulation_state
    (id,activation_date,last_settled_date,updated_at)
VALUES (1,NULL,NULL,NULL);

CREATE TABLE treasury_daily_settlements (
    settlement_date DATE PRIMARY KEY,
    event_group_id UUID NOT NULL UNIQUE,
    deposit_opening_balance NUMERIC(19,0) NOT NULL CHECK (deposit_opening_balance >= 0),
    deposit_daily_rate NUMERIC(20,18) NOT NULL CHECK (deposit_daily_rate >= 0),
    deposit_interest NUMERIC(19,0) NOT NULL CHECK (deposit_interest >= 0),
    deposit_closing_balance NUMERIC(19,0) NOT NULL CHECK (deposit_closing_balance >= 0),
    investment_opening_balance NUMERIC(19,0) NOT NULL CHECK (investment_opening_balance >= 0),
    investment_return_rate NUMERIC(8,6) NOT NULL
        CHECK (investment_return_rate >= -0.03 AND investment_return_rate <= 0.05),
    investment_return_amount NUMERIC(19,0) NOT NULL,
    investment_closing_balance NUMERIC(19,0) NOT NULL CHECK (investment_closing_balance >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
