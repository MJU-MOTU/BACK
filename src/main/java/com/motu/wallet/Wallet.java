package com.motu.wallet;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "wallets")
public class Wallet {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, unique = true)
	private Long userId;

	@Column(nullable = false)
	private long cash;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Wallet() {
	}

	private Wallet(Long userId, long cash, Instant now) {
		this.userId = userId;
		this.cash = cash;
		this.updatedAt = now;
	}

	static Wallet open(Long userId, long initialCash, Instant now) {
		return new Wallet(userId, initialCash, now);
	}

	public boolean canAfford(long amount) {
		return cash >= amount;
	}

	public void withdraw(long amount, Instant now) {
		if (amount <= 0 || !canAfford(amount)) {
			throw new IllegalStateException("잔고보다 많이 뺄 수 없습니다: cash=" + cash + ", amount=" + amount);
		}
		cash -= amount;
		updatedAt = now;
	}

	public void deposit(long amount, Instant now) {
		if (amount <= 0) {
			throw new IllegalStateException("입금액은 0보다 커야 합니다: " + amount);
		}
		cash = Math.addExact(cash, amount);
		updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public long getCash() {
		return cash;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
