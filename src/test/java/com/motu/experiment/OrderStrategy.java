package com.motu.experiment;

// 동시성 전략 비교 실험용 주문 처리. 세 전략은 같은 SQL 작업(주문·지갑·원장·보유·체결)을 하고 동시성 제어만 다르다
interface OrderStrategy {

	String name();

	Outcome buy(long userId, String idempotencyKey, int quantity, long price);

	Outcome sell(long userId, String idempotencyKey, int quantity, long price);

	enum Status {
		FILLED, REJECTED, REPLAYED
	}

	// retries: 충돌로 처음부터 다시 시도한 횟수(낙관적 락만 0보다 크다)
	record Outcome(Status status, int retries) {

		static Outcome of(Status status) {
			return new Outcome(status, 0);
		}

	}

}
