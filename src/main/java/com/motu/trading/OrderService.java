package com.motu.trading;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import com.motu.common.error.BusinessException;
import com.motu.common.error.ErrorCode;
import com.motu.market.Quote;

import org.springframework.stereotype.Service;

// 트랜잭션을 일부러 걸지 않는다. 단계마다 따로 트랜잭션을 쓴다:
// 1) 준비: 같은 키 확인 + 종목 확인 + 시세(KIS HTTP)  2) 지갑을 잠그고 체결(OrderExecutor)
// 시세를 기다리는 동안 지갑을 잠그지 않고, 체결 트랜잭션이 잠금보다 먼저 찍힌 스냅샷을 보지 않게 하려는 것이다
@Service
public class OrderService {

	// 같은 회원의 앞 주문을 이만큼 기다려도 차례가 안 오면 포기한다. 같은 Idempotency-Key로 다시 보내면 안전하다
	static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);

	private final OrderPreparation orderPreparation;
	private final OrderExecutor orderExecutor;
	private final UserOrderLocks userOrderLocks;

	OrderService(OrderPreparation orderPreparation, OrderExecutor orderExecutor, UserOrderLocks userOrderLocks) {
		this.orderPreparation = orderPreparation;
		this.orderExecutor = orderExecutor;
		this.userOrderLocks = userOrderLocks;
	}

	public OrderResult place(Long userId, OrderCommand command) {
		OrderPreparation.Prepared prepared = orderPreparation.prepare(userId, command);
		if (prepared.replay() != null) {
			return prepared.replay();
		}
		return executeInTurn(userId, command, prepared.quote());
	}

	// 같은 회원의 주문은 커넥션을 빌리기 전에 서버 안에서 먼저 줄을 세운다(UserOrderLocks 참고)
	private OrderResult executeInTurn(Long userId, OrderCommand command, Quote quote) {
		ReentrantLock lock = userOrderLocks.lockFor(userId);
		try {
			if (!lock.tryLock(LOCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new BusinessException(ErrorCode.ORDER_BUSY);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BusinessException(ErrorCode.ORDER_BUSY);
		}
		try {
			return orderExecutor.execute(userId, command, quote);
		}
		finally {
			lock.unlock();
		}
	}

}
