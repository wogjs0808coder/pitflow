ALTER TABLE mechanics
    ADD COLUMN monthly_base_salary NUMERIC(14,0)
        CHECK (monthly_base_salary >= 0);

ALTER TABLE mechanics
    ADD COLUMN monthly_standard_hours NUMERIC(8,2) NOT NULL DEFAULT 209
        CHECK (monthly_standard_hours > 0);

CREATE TABLE finance_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    default_monthly_base_salary NUMERIC(14,0) NOT NULL
        CHECK (default_monthly_base_salary >= 0),
    default_monthly_standard_hours NUMERIC(8,2) NOT NULL
        CHECK (default_monthly_standard_hours > 0),
    target_payroll_ratio NUMERIC(5,2) NOT NULL
        CHECK (target_payroll_ratio >= 0 AND target_payroll_ratio <= 100),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by UUID REFERENCES users(id)
);

INSERT INTO finance_settings
    (id,default_monthly_base_salary,default_monthly_standard_hours,
     target_payroll_ratio,updated_at,updated_by)
VALUES (1,3500000,209,30.00,CURRENT_TIMESTAMP,NULL);

CREATE TABLE finance_entries (
    id UUID PRIMARY KEY,
    entry_date DATE NOT NULL,
    category VARCHAR(32) NOT NULL CHECK (category IN (
        'RENT','UTILITIES','INSURANCE','SOFTWARE','SHOP_SUPPLIES',
        'EQUIPMENT_MAINTENANCE','CARD_FEES','DEPRECIATION','INTEREST',
        'TAX','OTHER_OPERATING','OTHER_INCOME')),
    amount NUMERIC(14,0) NOT NULL CHECK (amount >= 0),
    description VARCHAR(500) NOT NULL
        CHECK (CHAR_LENGTH(TRIM(description)) BETWEEN 1 AND 500),
    entry_kind VARCHAR(12) NOT NULL CHECK (entry_kind IN ('ENTRY','REVERSAL')),
    original_entry_id UUID REFERENCES finance_entries(id),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK ((entry_kind = 'ENTRY' AND original_entry_id IS NULL)
        OR (entry_kind = 'REVERSAL' AND original_entry_id IS NOT NULL)),
    UNIQUE(original_entry_id)
);

CREATE INDEX idx_finance_entries_period
    ON finance_entries(entry_date, category, created_at, id);
