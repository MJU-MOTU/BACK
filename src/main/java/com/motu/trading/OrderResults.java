package com.motu.trading;

final class OrderResults {

	private OrderResults() {
	}

	static OrderResult of(Order order, Execution execution, long cashAfter, boolean replayed) {
		return new OrderResult(order.getId(), order.getStatus(), order.getStockCode(), order.getSide(),
				order.getQuantity(), OrderResult.ExecutionView.from(execution), cashAfter, order.getCreatedAt(),
				replayed);
	}

}
