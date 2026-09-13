ALTER TABLE parts ADD COLUMN description VARCHAR(600) NOT NULL DEFAULT '';
ALTER TABLE parts ADD CONSTRAINT part_description_length_v8 CHECK (CHAR_LENGTH(description) <= 600);

-- Generic demo catalog notes. They describe use only and do not claim vehicle fitment.
UPDATE parts SET description = CASE sku
    WHEN 'PF-OIL' THEN '엔진 내부 윤활과 냉각을 돕는 소모품입니다. 작업 전 차량별 점도와 필요 수량을 확인하세요.'
    WHEN 'PF-OIL-FILTER' THEN '엔진오일의 이물질을 걸러주는 교환 부품입니다. 작업 전 차량별 규격을 확인하세요.'
    WHEN 'PF-AIR-FILTER' THEN '엔진으로 유입되는 공기의 먼지를 걸러주는 필터입니다.'
    WHEN 'PF-CABIN-FILTER' THEN '실내로 들어오는 공기의 먼지를 걸러주는 필터입니다.'
    WHEN 'PF-TIRE' THEN '노면과 직접 접촉하는 소모품입니다. 규격과 하중·속도 등급을 확인한 뒤 사용하세요.'
    WHEN 'PF-BATTERY' THEN '시동과 차량 전장품에 전원을 공급하는 12V 배터리입니다. 차량별 용량과 단자 방향을 확인하세요.'
    WHEN 'PF-FRONT-PAD' THEN '앞바퀴 제동에 사용하는 브레이크 패드 세트입니다. 차종별 규격을 확인하세요.'
    WHEN 'PF-REAR-PAD' THEN '뒷바퀴 제동에 사용하는 브레이크 패드 세트입니다. 차종별 규격을 확인하세요.'
    WHEN 'PF-BRAKE-FLUID' THEN '브레이크 유압 계통에 사용하는 오일입니다. 차량 지정 규격을 확인하세요.'
    WHEN 'PF-COOLANT' THEN '엔진의 열을 식히고 냉각 계통을 보호하는 소모품입니다. 혼합 규격을 확인하세요.'
    WHEN 'PF-SPARK-PLUG' THEN '가솔린 엔진의 혼합기에 불꽃을 발생시키는 점화 부품입니다.'
    WHEN 'PF-WIPER' THEN '전면 유리의 물기와 오염을 닦는 소모품입니다. 길이와 체결 방식을 확인하세요.'
    WHEN 'PF-TRANS-FLUID' THEN '변속기 윤활과 동력 전달에 사용하는 오일입니다. 변속기별 지정 규격을 확인하세요.'
    WHEN 'PF-BELT' THEN '엔진의 보조 장치를 구동하는 벨트입니다. 규격과 장력을 확인하세요.'
    WHEN 'PF-BULB' THEN '차량 등화장치 교환용 전구입니다. 전압과 소켓 규격을 확인하세요.'
    WHEN 'PF-WASHER' THEN '전면 유리 세척에 사용하는 워셔액입니다.'
    WHEN 'PF-DRAIN-WASHER' THEN '오일 배출 플러그의 밀봉을 돕는 교환용 와셔입니다.'
    ELSE description
END
WHERE sku LIKE 'PF-%' AND description = '';

-- Portfolio demo values only. Preserve every price already entered by an administrator.
UPDATE parts SET unit_price = CASE sku
    WHEN 'PF-OIL' THEN 12000
    WHEN 'PF-OIL-FILTER' THEN 9000
    WHEN 'PF-AIR-FILTER' THEN 18000
    WHEN 'PF-CABIN-FILTER' THEN 15000
    WHEN 'PF-TIRE' THEN 100000
    WHEN 'PF-BATTERY' THEN 100000
    WHEN 'PF-FRONT-PAD' THEN 80000
    WHEN 'PF-REAR-PAD' THEN 70000
    WHEN 'PF-BRAKE-FLUID' THEN 15000
    WHEN 'PF-COOLANT' THEN 12000
    WHEN 'PF-SPARK-PLUG' THEN 15000
    WHEN 'PF-WIPER' THEN 25000
    WHEN 'PF-TRANS-FLUID' THEN 18000
    WHEN 'PF-BELT' THEN 45000
    WHEN 'PF-BULB' THEN 10000
    WHEN 'PF-WASHER' THEN 5000
    WHEN 'PF-DRAIN-WASHER' THEN 2000
    ELSE unit_price
END
WHERE sku LIKE 'PF-%' AND unit_price = 0;
