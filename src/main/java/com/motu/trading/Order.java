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

import com.motu.common.error.ErrorCode;

@Entity(name = "TradeOrder")
@Table(name = "orders")
public class Order {

	public enum Status {
		FILLED, REJECTED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "stock_code", nullable = false, length = 6, updatable = false)
	private String stockCode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 4, updatable = false)
	private Side side;

	@Column(nullable = false, updatable = false)
	private int quantity;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Status status;

	// 거절된 주문은 이유를 남긴다. 같은 키로 다시 오면 같은 에러를 돌려준다
	@Enumerated(EnumType.STRING)
	@Column(name = "reject_reason", length = 40)
	private ErrorCode rejectReason;

	@Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
	private String idempotencyKey;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Order() {
	}

	private Order(Long userId, String stockCode, Side side, int quantity, Status status, ErrorCode rejectReason,
			String idempotencyKey, Instant createdAt) {
		this.userId = userId;
		this.stockCode = stockCode;
		this.side = side;
		this.quantity = quantity;
		this.status = status;
		this.rejectReason = rejectReason;
		this.idempotencyKey = idempotencyKey;
		this.createdAt = createdAt;
	}

	static Order filled(Long userId, OrderCommand command, Instant now) {
		return new Order(userId, command.stockCode(), command.side(), command.quantity(), Status.FILLED, null,
				command.idempotencyKey(), now);
	}

	static Order rejected(Long userId, OrderCommand command, ErrorCode reason, Instant now) {
		return new Order(userId, command.stockCode(), command.side(), command.quantity(), Status.REJECTED, reason,
				command.idempotencyKey(), now);
	}

	// 같은 키로 온 요청이 처음 요청과 같은 주문인지
	boolean sameRequestAs(OrderCommand command) {
		return stockCode.equals(command.stockCode()) && side == command.side() && quantity == command.quantity();
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getStockCode() {
		return stockCode;
	}

	public Side getSide() {
		return side;
	}

	public int getQuantity() {
		return quantity;
	}

	public Status getStatus() {
		return status;
	}

	public ErrorCode getRejectReason() {
		return rejectReason;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
