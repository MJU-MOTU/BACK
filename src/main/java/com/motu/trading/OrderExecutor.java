package com.motu.trading;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.motu.common.error.BusinessException;
import com.motu.common.error.ErrorCode;
import com.motu.market.Quote;
import com.motu.wallet.Wallet;
import com.motu.wallet.WalletRepository;
import com.motu.wallet.WalletService;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 지갑을 잠근 채로 주문 하나를 체결하거나 거절한다
@Component
class OrderExecutor {

	private final WalletRepository walletRepository;
	private final WalletService walletService;
	private final OrderRepository orderRepository;
	private final ExecutionRepository executionRepository;
	private final HoldingRepository holdingRepository;
	private final OrderReplays orderReplays;
	private final Clock clock;

	OrderExecutor(WalletRepository walletRepository, WalletService walletService, OrderRepository orderRepository,
			ExecutionRepository executionRepository, HoldingRepository holdingRepository, OrderReplays orderReplays,
			Clock clock) {
		this.walletRepository = walletRepository;
		this.walletService = walletService;
		this.orderRepository = orderRepository;
		this.executionRepository = executionRepository;
		this.holdingRepository = holdingRepository;
		this.orderReplays = orderReplays;
		this.clock = clock;
	}

	// - 첫 문장이 지갑 SELECT ... FOR UPDATE다. 같은 회원의 주문은 여기서 한 줄로 선다.
	// - READ COMMITTED: MySQL 기본값(REPEATABLE READ)은 트랜잭션의 첫 일반 SELECT 시점 스냅샷을 계속 보므로,
	//   잠금을 기다리는 동안 앞 주문이 바꾼 보유 수량·주문 기록을 못 볼 수 있다.
	// - 거절도 주문으로 남겨야 같은 키 재요청에 같은 답을 주므로 BusinessException으로는 롤백하지 않는다.
	//   거절은 지갑·보유를 바꾸기 전에 정해지므로 되돌릴 변경이 없다
	@Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = BusinessException.class)
	OrderResult execute(Long userId, OrderCommand command, Quote quote) {
		Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
			.orElseThrow(() -> new IllegalStateException("지갑이 없는 회원입니다: " + userId));

		// 잠금을 기다리는 사이 같은 키의 요청이 먼저 끝났을 수 있다
		Optional<OrderResult> replay = orderReplays.find(userId, command);
		if (replay.isPresent()) {
			return replay.get();
		}

		Instant now = clock.instant();
		long amount = Math.multiplyExact(quote.price(), (long) command.quantity());
		return command.side() == Side.BUY
				? buy(userId, command, quote, wallet, amount, now)
				: sell(userId, command, quote, wallet, amount, now);
	}

	private OrderResult buy(Long userId, OrderCommand command, Quote quote, Wallet wallet, long amount, Instant now) {
		if (!wallet.canAfford(amount)) {
			throw reject(userId, command, ErrorCode.INSUFFICIENT_CASH, now);
		}
		Order order = orderRepository.save(Order.filled(userId, command, now));
		walletService.withdrawForBuy(wallet, amount, order.getId(), now);

		Holding holding = holdingRepository.findByUserIdAndStockCode(userId, command.stockCode())
			.orElseGet(() -> new Holding(userId, command.stockCode(), now));
		holding.add(command.quantity(), amount, now);
		holdingRepository.save(holding);

		Execution execution = executionRepository.save(new Execution(order.getId(), quote.price(), command.quantity(),
				amount, quote.source(), null, now));
		return OrderResults.of(order, execution, wallet.getCash(), false);
	}

	private OrderResult sell(Long userId, OrderCommand command, Quote quote, Wallet wallet, long amount, Instant now) {
		Optional<Holding> found = holdingRepository.findByUserIdAndStockCode(userId, command.stockCode());
		if (found.isEmpty() || found.get().getQuantity() < command.quantity()) {
			throw reject(userId, command, ErrorCode.INSUFFICIENT_QUANTITY, now);
		}
		Holding holding = found.get();
		Order order = orderRepository.save(Order.filled(userId, command, now));

		long cost = holding.remove(command.quantity(), now);
		if (holding.isEmpty()) {
			holdingRepository.delete(holding);
		}
		walletService.depositForSell(wallet, amount, order.getId(), now);

		Execution execution = executionRepository.save(new Execution(order.getId(), quote.price(), command.quantity(),
				amount, quote.source(), amount - cost, now));
		return OrderResults.of(order, execution, wallet.getCash(), false);
	}

	private BusinessException reject(Long userId, OrderCommand command, ErrorCode reason, Instant now) {
		orderRepository.save(Order.rejected(userId, command, reason, now));
		return new BusinessException(reason);
	}

}
