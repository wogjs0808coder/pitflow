CREATE TABLE treasury_accounts (
    account_type VARCHAR(16) PRIMARY KEY
        CHECK (account_type IN ('OPERATING','DEPOSIT','INVESTMENT')),
    balance NUMERIC(19,0) NOT NULL CHECK (balance >= 0),
    target_ratio NUMERIC(7,6) NOT NULL
        CHECK (target_ratio >= 0 AND target_ratio <= 1),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE treasury_ledger (
    id UUID PRIMARY KEY,
    event_group_id UUID NOT NULL,
    account_type VARCHAR(16) NOT NULL REFERENCES treasury_accounts(account_type),
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN (
        'OPENING_ALLOCATION','REBALANCE','DEPOSIT_INTEREST','INVESTMENT_RETURN',
        'CUSTOMER_PAYMENT','PAYMENT_REFUND','OPERATING_EXPENSE','PAYROLL_PAYMENT')),
    amount_delta NUMERIC(19,0) NOT NULL,
    balance_after NUMERIC(19,0) NOT NULL CHECK (balance_after >= 0),
    reason VARCHAR(500) NOT NULL
        CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 500),
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_treasury_ledger_event
    ON treasury_ledger(event_group_id, account_type);

CREATE INDEX idx_treasury_ledger_created
    ON treasury_ledger(created_at, id);

INSERT INTO treasury_accounts
    (account_type,balance,target_ratio,created_at,updated_at)
VALUES
    ('OPERATING',400000000,0.40,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
    ('DEPOSIT',300000000,0.30,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
    ('INVESTMENT',300000000,0.30,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

INSERT INTO treasury_ledger
    (id,event_group_id,account_type,event_type,amount_delta,balance_after,
     reason,created_by,created_at)
VALUES
    ('51000000-0000-4000-8000-000000000001',
     '51000000-0000-4000-8000-000000000000',
     'OPERATING','OPENING_ALLOCATION',400000000,400000000,
     'Phase 5 opening treasury allocation',NULL,CURRENT_TIMESTAMP),
    ('51000000-0000-4000-8000-000000000002',
     '51000000-0000-4000-8000-000000000000',
     'DEPOSIT','OPENING_ALLOCATION',300000000,300000000,
     'Phase 5 opening treasury allocation',NULL,CURRENT_TIMESTAMP),
    ('51000000-0000-4000-8000-000000000003',
     '51000000-0000-4000-8000-000000000000',
     'INVESTMENT','OPENING_ALLOCATION',300000000,300000000,
     'Phase 5 opening treasury allocation',NULL,CURRENT_TIMESTAMP);
