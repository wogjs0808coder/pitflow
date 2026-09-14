ALTER TABLE appointments ADD COLUMN quote_fingerprint VARCHAR(64);
ALTER TABLE appointments ADD CONSTRAINT appointment_quote_fingerprint_v11
    CHECK (quote_fingerprint IS NULL OR CHAR_LENGTH(quote_fingerprint) = 64);

INSERT INTO service_selection_conflicts(service_id_a, service_id_b, reason)
SELECT
    CAST('11111111-1111-4111-8111-111111111111' AS UUID),
    CAST('953e027e-f285-39fb-aa5c-cfa91e47a613' AS UUID),
    '엔진오일 교체 항목에 엔진 에어필터가 포함되어 있습니다.'
WHERE EXISTS (
    SELECT 1 FROM service_items
    WHERE id=CAST('11111111-1111-4111-8111-111111111111' AS UUID))
  AND EXISTS (
    SELECT 1 FROM service_items
    WHERE id=CAST('953e027e-f285-39fb-aa5c-cfa91e47a613' AS UUID))
  AND NOT EXISTS (
    SELECT 1 FROM service_selection_conflicts
    WHERE service_id_a=CAST('11111111-1111-4111-8111-111111111111' AS UUID)
      AND service_id_b=CAST('953e027e-f285-39fb-aa5c-cfa91e47a613' AS UUID));
