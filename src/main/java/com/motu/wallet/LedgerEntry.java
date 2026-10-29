package com.motu.wallet;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 지갑 잔고가 바뀐 이유를 한 줄씩 쌓는다. 한 번 쓰면 바꾸지 않는다
@Entity
@Table(name = "wallet_ledger")
public class LedgerEntry {

	public enum Type {
		INITIAL, BUY, SELL
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "wallet_id", nullable = false, updatable = false)
	private Long walletId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10, updatable = false)
	private Type type;

	// 들어오면 +, 나가면 -
	@Column(nullable = false, updatable = false)
	private long amount;

	@Column(name = "balance_after", nullable = false, updatable = false)
	private long balanceAfter;

	@Column(name = "order_id", updatable = false)
	private Long orderId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected LedgerEntry() {
	}

	LedgerEntry(Long walletId, Type type, long amount, long balanceAfter, Long orderId, Instant createdAt) {
		this.walletId = walletId;
		this.type = type;
		this.amount = amount;
		this.balanceAfter = balanceAfter;
		this.orderId = orderId;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Type getType() {
		return type;
	}

	public long getAmount() {
		return amount;
	}

	public long getBalanceAfter() {
		return balanceAfter;
	}

	public Long getOrderId() {
		return orderId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
