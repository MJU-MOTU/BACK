package com.motu.trading;

import java.time.Instant;

import com.motu.market.Quote;

// replayed: 같은 Idempotency-Key로 다시 와서 새로 체결하지 않고 처음 결과를 돌려준 경우
public record OrderResult(Long orderId, Order.Status status, String stockCode, Side side, int quantity,
		ExecutionView execution, long cashAfter, Instant createdAt, boolean replayed) {

	public record ExecutionView(long price, int quantity, long amount, Quote.Source priceSource, Long realizedPnl,
			Instant executedAt) {

		static ExecutionView from(Execution execution) {
			return new ExecutionView(execution.getPrice(), execution.getQuantity(), execution.getAmount(),
					execution.getPriceSource(), execution.getRealizedPnl(), execution.getExecutedAt());
		}

	}

}
