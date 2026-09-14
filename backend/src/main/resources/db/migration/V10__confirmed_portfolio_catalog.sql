-- Apply only to catalog rows that can be identified deterministically.
-- User-created/reused rows with other IDs are deliberately left untouched for manual review.

ALTER TABLE service_items
    ADD COLUMN requirements_confirmed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE service_selection_conflicts (
    service_id_a UUID NOT NULL REFERENCES service_items(id) ON DELETE CASCADE,
    service_id_b UUID NOT NULL REFERENCES service_items(id) ON DELETE CASCADE,
    reason VARCHAR(200) NOT NULL,
    PRIMARY KEY (service_id_a, service_id_b),
    CHECK (service_id_a <> service_id_b)
);

UPDATE service_items SET labor_price=19000, duration_minutes=30 WHERE id='11111111-1111-4111-8111-111111111111';
UPDATE service_items SET labor_price=15000, duration_minutes=30 WHERE id='22222222-2222-4222-8222-222222222222';
UPDATE service_items SET labor_price=15000, duration_minutes=30 WHERE id='33333333-3333-4333-8333-333333333333';
UPDATE service_items SET labor_price=10000, duration_minutes=30 WHERE id='953e027e-f285-39fb-aa5c-cfa91e47a613';
UPDATE service_items SET labor_price=10000, duration_minutes=30 WHERE id='e457ae18-3e15-3fa1-b281-57683d4b40ad';
UPDATE service_items SET labor_price=30000, duration_minutes=60 WHERE id='a6c9c65b-ae7d-37f8-95b4-db20ce47d36f';
UPDATE service_items SET name='뒷 브레이크 패드 교체', labor_price=30000, duration_minutes=60 WHERE id='26736bcc-611f-36e5-834c-f4912c76b6bb';
UPDATE service_items SET labor_price=40000, duration_minutes=60 WHERE id='23a019ff-2986-3d82-8972-96f8f0522340';
UPDATE service_items SET labor_price=40000, duration_minutes=60 WHERE id='5e11b1ac-875b-32da-ad90-556bebb0a681';
UPDATE service_items SET labor_price=40000, duration_minutes=60 WHERE id='da2c3e6f-a598-37f7-8c48-a51e4668f185';
UPDATE service_items SET labor_price=5000, duration_minutes=30 WHERE id='b6d9d7f7-f033-35ee-aee2-beee44c98181';
UPDATE service_items SET labor_price=60000, duration_minutes=90 WHERE id='d63aeb42-9933-3209-a67f-4b37a157a43c';
UPDATE service_items SET labor_price=50000, duration_minutes=60 WHERE id='a48cdee0-93ae-31ea-9c21-6207575aa691';
UPDATE service_items SET labor_price=10000, duration_minutes=30 WHERE id='373a3681-4f49-3272-b2f2-69378498d4ae';
UPDATE service_items SET name='워셔액 보충 서비스', labor_price=0, duration_minutes=30 WHERE id='f6b2e966-cf84-3576-9a3f-a64ebf1de473';
UPDATE service_items SET labor_price=20000, duration_minutes=30, requirements_confirmed=TRUE WHERE id='c85dfac7-b638-3282-a90a-a923af3c6d69';
UPDATE service_items SET labor_price=50000, duration_minutes=60, requirements_confirmed=TRUE WHERE id='737d5564-b70e-34bc-b7af-211e4039867a';
UPDATE service_items SET labor_price=20000, duration_minutes=30, requirements_confirmed=TRUE WHERE id='d9613f25-5f50-30ec-8bbf-13160a4f7e68';

UPDATE parts SET unit_price=85000 WHERE sku='PF-BATTERY';
UPDATE parts SET unit_price=45000 WHERE sku='PF-FRONT-PAD';
UPDATE parts SET name='뒷 브레이크 패드 세트', unit_price=45000 WHERE sku='PF-REAR-PAD';
UPDATE parts SET unit_price=20000 WHERE sku='PF-BRAKE-FLUID';
UPDATE parts SET name='와이퍼 블레이드 세트', unit_price=25000 WHERE sku='PF-WIPER';

UPDATE service_part_requirements SET required_quantity=NULL, quantity_confirmed=FALSE
 WHERE service_id IN (
 '11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222',
 '33333333-3333-4333-8333-333333333333','953e027e-f285-39fb-aa5c-cfa91e47a613',
 'e457ae18-3e15-3fa1-b281-57683d4b40ad','a6c9c65b-ae7d-37f8-95b4-db20ce47d36f',
 '26736bcc-611f-36e5-834c-f4912c76b6bb','23a019ff-2986-3d82-8972-96f8f0522340',
 '5e11b1ac-875b-32da-ad90-556bebb0a681','da2c3e6f-a598-37f7-8c48-a51e4668f185',
 'b6d9d7f7-f033-35ee-aee2-beee44c98181','d63aeb42-9933-3209-a67f-4b37a157a43c',
 'a48cdee0-93ae-31ea-9c21-6207575aa691','373a3681-4f49-3272-b2f2-69378498d4ae',
 'f6b2e966-cf84-3576-9a3f-a64ebf1de473');

DELETE FROM service_part_requirements r
 WHERE r.service_id='11111111-1111-4111-8111-111111111111'
   AND r.part_id IN (SELECT id FROM parts WHERE sku IN ('PF-OIL-FILTER','PF-DRAIN-WASHER'));
UPDATE service_part_requirements SET required_quantity=4, quantity_confirmed=TRUE
 WHERE service_id='11111111-1111-4111-8111-111111111111' AND part_id=(SELECT id FROM parts WHERE sku='PF-OIL');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed)
 SELECT '11111111-1111-4111-8111-111111111111',id,4,TRUE FROM parts p WHERE sku='PF-OIL'
 AND EXISTS(SELECT 1 FROM service_items WHERE id='11111111-1111-4111-8111-111111111111')
 AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='11111111-1111-4111-8111-111111111111' AND r.part_id=p.id);
UPDATE service_part_requirements SET required_quantity=1, quantity_confirmed=TRUE
 WHERE service_id='11111111-1111-4111-8111-111111111111' AND part_id=(SELECT id FROM parts WHERE sku='PF-AIR-FILTER');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed)
 SELECT '11111111-1111-4111-8111-111111111111',id,1,TRUE FROM parts p WHERE sku='PF-AIR-FILTER'
 AND EXISTS(SELECT 1 FROM service_items WHERE id='11111111-1111-4111-8111-111111111111')
 AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='11111111-1111-4111-8111-111111111111' AND r.part_id=p.id);

UPDATE service_part_requirements SET required_quantity=1, quantity_confirmed=TRUE WHERE service_id='22222222-2222-4222-8222-222222222222' AND part_id=(SELECT id FROM parts WHERE sku='PF-TIRE');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed) SELECT '22222222-2222-4222-8222-222222222222',id,1,TRUE FROM parts p WHERE sku='PF-TIRE' AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='22222222-2222-4222-8222-222222222222' AND r.part_id=p.id);
UPDATE service_part_requirements SET required_quantity=1, quantity_confirmed=TRUE WHERE service_id='33333333-3333-4333-8333-333333333333' AND part_id=(SELECT id FROM parts WHERE sku='PF-BATTERY');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed) SELECT '33333333-3333-4333-8333-333333333333',id,1,TRUE FROM parts p WHERE sku='PF-BATTERY' AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='33333333-3333-4333-8333-333333333333' AND r.part_id=p.id);
UPDATE service_part_requirements SET required_quantity=1, quantity_confirmed=TRUE WHERE service_id='953e027e-f285-39fb-aa5c-cfa91e47a613' AND part_id=(SELECT id FROM parts WHERE sku='PF-AIR-FILTER');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed) SELECT '953e027e-f285-39fb-aa5c-cfa91e47a613',id,1,TRUE FROM parts p WHERE sku='PF-AIR-FILTER' AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='953e027e-f285-39fb-aa5c-cfa91e47a613' AND r.part_id=p.id);
UPDATE service_part_requirements SET required_quantity=1, quantity_confirmed=TRUE WHERE service_id='e457ae18-3e15-3fa1-b281-57683d4b40ad' AND part_id=(SELECT id FROM parts WHERE sku='PF-CABIN-FILTER');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed) SELECT 'e457ae18-3e15-3fa1-b281-57683d4b40ad',id,1,TRUE FROM parts p WHERE sku='PF-CABIN-FILTER' AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='e457ae18-3e15-3fa1-b281-57683d4b40ad' AND r.part_id=p.id);
UPDATE service_part_requirements SET required_quantity=1, quantity_confirmed=TRUE WHERE service_id='a6c9c65b-ae7d-37f8-95b4-db20ce47d36f' AND part_id=(SELECT id FROM parts WHERE sku='PF-FRONT-PAD');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed) SELECT 'a6c9c65b-ae7d-37f8-95b4-db20ce47d36f',id,1,TRUE FROM parts p WHERE sku='PF-FRONT-PAD' AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='a6c9c65b-ae7d-37f8-95b4-db20ce47d36f' AND r.part_id=p.id);
UPDATE service_part_requirements SET required_quantity=1, quantity_confirmed=TRUE WHERE service_id='26736bcc-611f-36e5-834c-f4912c76b6bb' AND part_id=(SELECT id FROM parts WHERE sku='PF-REAR-PAD');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed) SELECT '26736bcc-611f-36e5-834c-f4912c76b6bb',id,1,TRUE FROM parts p WHERE sku='PF-REAR-PAD' AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='26736bcc-611f-36e5-834c-f4912c76b6bb' AND r.part_id=p.id);
UPDATE service_part_requirements SET required_quantity=1, quantity_confirmed=TRUE WHERE service_id='23a019ff-2986-3d82-8972-96f8f0522340' AND part_id=(SELECT id FROM parts WHERE sku='PF-BRAKE-FLUID');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed) SELECT '23a019ff-2986-3d82-8972-96f8f0522340',id,1,TRUE FROM parts p WHERE sku='PF-BRAKE-FLUID' AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='23a019ff-2986-3d82-8972-96f8f0522340' AND r.part_id=p.id);
UPDATE service_part_requirements SET required_quantity=2, quantity_confirmed=TRUE WHERE service_id='5e11b1ac-875b-32da-ad90-556bebb0a681' AND part_id=(SELECT id FROM parts WHERE sku='PF-COOLANT');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed) SELECT '5e11b1ac-875b-32da-ad90-556bebb0a681',id,2,TRUE FROM parts p WHERE sku='PF-COOLANT' AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='5e11b1ac-875b-32da-ad90-556bebb0a681' AND r.part_id=p.id);
UPDATE service_part_requirements SET required_quantity=4, quantity_confirmed=TRUE WHERE service_id='da2c3e6f-a598-37f7-8c48-a51e4668f185' AND part_id=(SELECT id FROM parts WHERE sku='PF-SPARK-PLUG');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed) SELECT 'da2c3e6f-a598-37f7-8c48-a51e4668f185',id,4,TRUE FROM parts p WHERE sku='PF-SPARK-PLUG' AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='da2c3e6f-a598-37f7-8c48-a51e4668f185' AND r.part_id=p.id);
UPDATE service_part_requirements SET required_quantity=1, quantity_confirmed=TRUE WHERE service_id='b6d9d7f7-f033-35ee-aee2-beee44c98181' AND part_id=(SELECT id FROM parts WHERE sku='PF-WIPER');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed) SELECT 'b6d9d7f7-f033-35ee-aee2-beee44c98181',id,1,TRUE FROM parts p WHERE sku='PF-WIPER' AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='b6d9d7f7-f033-35ee-aee2-beee44c98181' AND r.part_id=p.id);
UPDATE service_part_requirements SET required_quantity=6, quantity_confirmed=TRUE WHERE service_id='d63aeb42-9933-3209-a67f-4b37a157a43c' AND part_id=(SELECT id FROM parts WHERE sku='PF-TRANS-FLUID');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed) SELECT 'd63aeb42-9933-3209-a67f-4b37a157a43c',id,6,TRUE FROM parts p WHERE sku='PF-TRANS-FLUID' AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='d63aeb42-9933-3209-a67f-4b37a157a43c' AND r.part_id=p.id);
UPDATE service_part_requirements SET required_quantity=1, quantity_confirmed=TRUE WHERE service_id='a48cdee0-93ae-31ea-9c21-6207575aa691' AND part_id=(SELECT id FROM parts WHERE sku='PF-BELT');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed) SELECT 'a48cdee0-93ae-31ea-9c21-6207575aa691',id,1,TRUE FROM parts p WHERE sku='PF-BELT' AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='a48cdee0-93ae-31ea-9c21-6207575aa691' AND r.part_id=p.id);
UPDATE service_part_requirements SET required_quantity=1, quantity_confirmed=TRUE WHERE service_id='373a3681-4f49-3272-b2f2-69378498d4ae' AND part_id=(SELECT id FROM parts WHERE sku='PF-BULB');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed) SELECT '373a3681-4f49-3272-b2f2-69378498d4ae',id,1,TRUE FROM parts p WHERE sku='PF-BULB' AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='373a3681-4f49-3272-b2f2-69378498d4ae' AND r.part_id=p.id);
UPDATE service_part_requirements SET required_quantity=1, quantity_confirmed=TRUE WHERE service_id='f6b2e966-cf84-3576-9a3f-a64ebf1de473' AND part_id=(SELECT id FROM parts WHERE sku='PF-WASHER');
INSERT INTO service_part_requirements(service_id,part_id,required_quantity,quantity_confirmed) SELECT 'f6b2e966-cf84-3576-9a3f-a64ebf1de473',id,1,TRUE FROM parts p WHERE sku='PF-WASHER' AND NOT EXISTS(SELECT 1 FROM service_part_requirements r WHERE r.service_id='f6b2e966-cf84-3576-9a3f-a64ebf1de473' AND r.part_id=p.id);

UPDATE service_items s SET requirements_confirmed=TRUE
 WHERE s.id='11111111-1111-4111-8111-111111111111'
   AND 2=(SELECT COUNT(*) FROM service_part_requirements r WHERE r.service_id=s.id AND r.quantity_confirmed=TRUE)
   AND 0=(SELECT COUNT(*) FROM service_part_requirements r WHERE r.service_id=s.id AND r.quantity_confirmed=FALSE);
UPDATE service_items s SET requirements_confirmed=TRUE
 WHERE s.id IN (
 '22222222-2222-4222-8222-222222222222','33333333-3333-4333-8333-333333333333',
 '953e027e-f285-39fb-aa5c-cfa91e47a613','e457ae18-3e15-3fa1-b281-57683d4b40ad',
 'a6c9c65b-ae7d-37f8-95b4-db20ce47d36f','26736bcc-611f-36e5-834c-f4912c76b6bb',
 '23a019ff-2986-3d82-8972-96f8f0522340','5e11b1ac-875b-32da-ad90-556bebb0a681',
 'da2c3e6f-a598-37f7-8c48-a51e4668f185','b6d9d7f7-f033-35ee-aee2-beee44c98181',
 'd63aeb42-9933-3209-a67f-4b37a157a43c','a48cdee0-93ae-31ea-9c21-6207575aa691',
 '373a3681-4f49-3272-b2f2-69378498d4ae','f6b2e966-cf84-3576-9a3f-a64ebf1de473')
   AND 1=(SELECT COUNT(*) FROM service_part_requirements r WHERE r.service_id=s.id AND r.quantity_confirmed=TRUE)
   AND 0=(SELECT COUNT(*) FROM service_part_requirements r WHERE r.service_id=s.id AND r.quantity_confirmed=FALSE);

INSERT INTO service_selection_conflicts(service_id_a,service_id_b,reason)
SELECT '11111111-1111-4111-8111-111111111111','953e027e-f285-39fb-aa5c-cfa91e47a613',
       '엔진오일 교체 항목에 엔진 에어필터가 포함되어 있습니다.'
WHERE EXISTS(SELECT 1 FROM service_items WHERE id='11111111-1111-4111-8111-111111111111')
  AND EXISTS(SELECT 1 FROM service_items WHERE id='953e027e-f285-39fb-aa5c-cfa91e47a613');
