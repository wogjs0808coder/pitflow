ALTER TABLE users ADD COLUMN phone_number VARCHAR(20);
ALTER TABLE users ADD COLUMN birth_date DATE;
ALTER TABLE users ADD COLUMN main_admin BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN admin_number BIGINT;
ALTER TABLE users ADD COLUMN admin_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD CONSTRAINT uq_users_phone_number UNIQUE (phone_number);
ALTER TABLE users ADD CONSTRAINT uq_users_admin_number UNIQUE (admin_number);
ALTER TABLE users ADD CONSTRAINT ck_users_admin_number CHECK (admin_number IS NULL OR (role = 'ADMIN' AND admin_number > 0));
ALTER TABLE users ADD CONSTRAINT ck_users_main_admin CHECK (main_admin = FALSE OR (role = 'ADMIN' AND admin_number IS NULL));

CREATE TABLE admin_account_sequence (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    next_number BIGINT NOT NULL CHECK (next_number > 0)
);
INSERT INTO admin_account_sequence (id, next_number) VALUES (1, 1);

CREATE TABLE admin_account_audit (
    id UUID PRIMARY KEY,
    actor_id UUID REFERENCES users(id),
    target_id UUID NOT NULL REFERENCES users(id),
    action VARCHAR(40) NOT NULL,
    detail VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_admin_account_audit_created ON admin_account_audit (created_at);
