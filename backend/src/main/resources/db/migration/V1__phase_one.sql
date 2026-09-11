CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    name VARCHAR(50) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('CUSTOMER', 'ADMIN')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE vehicles (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id),
    plate_number VARCHAR(20) NOT NULL UNIQUE,
    manufacturer VARCHAR(40) NOT NULL,
    model VARCHAR(60) NOT NULL,
    model_year INTEGER NOT NULL CHECK (model_year BETWEEN 1900 AND 2100),
    mileage INTEGER NOT NULL CHECK (mileage >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_vehicles_owner ON vehicles(owner_id);

CREATE TABLE service_items (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL UNIQUE,
    description VARCHAR(500) NOT NULL,
    labor_price NUMERIC(12, 0) NOT NULL CHECK (labor_price >= 0),
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes BETWEEN 30 AND 480 AND MOD(duration_minutes, 30) = 0),
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Demonstration service catalog. Prices are sample labor fees, not quotes.
INSERT INTO service_items VALUES
('11111111-1111-4111-8111-111111111111', '엔진오일 교체', '엔진오일 및 오일필터 교체 작업입니다. 오일·필터 비용은 별도이며 차종에 따라 달라집니다.', 20000, 30, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('22222222-2222-4222-8222-222222222222', '타이어 교체', '타이어 교체와 휠 밸런스 작업입니다. 타이어 비용과 교체 수량에 따라 최종 비용이 달라집니다.', 40000, 60, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('33333333-3333-4333-8333-333333333333', '배터리 교체', '배터리 교체와 충전 상태 확인 작업입니다. 배터리 비용은 별도입니다.', 15000, 30, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
