package com.motu.trading;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.motu.market.Quote;

@Entity
@Table(name = "executions")
public class Execution {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "order_id", nullable = false, unique = true, updatable = false)
	private Long orderId;

	@Column(nullable = false, updatable = false)
	private long price;

	@Column(nullable = false, updatable = false)
	private int quantity;

	@Column(nullable = false, updatable = false)
	private long amount;

	// LIVE면 방금 받은 시세, STORED면 KIS를 못 불러 마지막 가격으로 체결했다
	@Enumerated(EnumType.STRING)
	@Column(name = "price_source", nullable = false, length = 10, updatable = false)
	private Quote.Source priceSource;

	// 매도할 때만 있다. 매도 금액 - 판 수량만큼의 매입 원가
	@Column(name = "realized_pnl", updatable = false)
	private Long realizedPnl;

	@Column(name = "executed_at", nullable = false, updatable = false)
	private Instant executedAt;

	protected Execution() {
	}

	Execution(Long orderId, long price, int quantity, long amount, Quote.Source priceSource, Long realizedPnl,
			Instant executedAt) {
		this.orderId = orderId;
		this.price = price;
		this.quantity = quantity;
		this.amount = amount;
		this.priceSource = priceSource;
		this.realizedPnl = realizedPnl;
		this.executedAt = executedAt;
	}

	public Long getOrderId() {
		return orderId;
	}

	public long getPrice() {
		return price;
	}

	public int getQuantity() {
		return quantity;
	}

	public long getAmount() {
		return amount;
	}

	public Quote.Source getPriceSource() {
		return priceSource;
	}

	public Long getRealizedPnl() {
		return realizedPnl;
	}

	public Instant getExecutedAt() {
		return executedAt;
	}

}
