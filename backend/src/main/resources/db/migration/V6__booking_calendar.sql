CREATE TABLE booking_calendar (
    id INTEGER PRIMARY KEY CHECK (id=1),
    revision BIGINT NOT NULL DEFAULT 0,
    closed_days VARCHAR(100)
);
INSERT INTO booking_calendar (id,revision,closed_days) VALUES (1,0,NULL);
CREATE TABLE booking_day_overrides (
    calendar_date DATE PRIMARY KEY,
    closed BOOLEAN NOT NULL
);
