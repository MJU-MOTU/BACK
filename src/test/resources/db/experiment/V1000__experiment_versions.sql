-- 동시성 전략 비교 실험 전용. 낙관적 락에 쓸 version 컬럼을 붙인다(운영 스키마에는 없다)
ALTER TABLE wallets ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE holdings ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
