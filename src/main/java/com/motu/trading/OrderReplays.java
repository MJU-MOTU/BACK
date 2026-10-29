package com.motu.trading;

import java.util.Optional;

import com.motu.common.error.BusinessException;
import com.motu.common.error.ErrorCode;
import com.motu.wallet.LedgerEntry;
import com.motu.wallet.LedgerEntryRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 같은 Idempotency-Key로 다시 온 주문에 처음 결과를 그대로 돌려준다
@Component
class OrderReplays {

	private final OrderRepository orderRepository;
	private final ExecutionRepository executionRepository;
	private final LedgerEntryRepository ledgerEntryRepository;

	OrderReplays(OrderRepository orderRepository, ExecutionRepository executionRepository,
			LedgerEntryRepository ledgerEntryRepository) {
		this.orderRepository = orderRepository;
		this.executionRepository = executionRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
	}

	@Transactional(readOnly = true)
	Optional<OrderResult> find(Long userId, OrderCommand command) {
		return orderRepository.findByUserIdAndIdempotencyKey(userId, command.idempotencyKey()).map(order -> {
			if (!order.sameRequestAs(command)) {
				throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
			}
			if (order.getStatus() == Order.Status.REJECTED) {
				throw new BusinessException(order.getRejectReason());
			}
			Execution execution = executionRepository.findByOrderId(order.getId()).orElseThrow();
			long cashAfter = ledgerEntryRepository.findByOrderId(order.getId())
				.map(LedgerEntry::getBalanceAfter)
				.orElseThrow();
			return OrderResults.of(order, execution, cashAfter, true);
		});
	}

}
