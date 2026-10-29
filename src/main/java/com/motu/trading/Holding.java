package com.motu.trading;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "holdings")
public class Holding {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "stock_code", nullable = false, length = 6, updatable = false)
	private String stockCode;

	@Column(nullable = false)
	private int quantity;

	// 평균단가 대신 총매입금액을 저장한다. 평균단가는 소수라 저장하면 반올림 오차가 거래마다 쌓인다
	@Column(name = "total_cost", nullable = false)
	private long totalCost;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Holding() {
	}

	Holding(Long userId, String stockCode, Instant now) {
		this.userId = userId;
		this.stockCode = stockCode;
		this.updatedAt = now;
	}

	void add(int addQuantity, long cost, Instant now) {
		quantity = Math.addExact(quantity, addQuantity);
		totalCost = Math.addExact(totalCost, cost);
		updatedAt = now;
	}

	// 판 수량만큼의 매입 원가를 빼고 그 원가를 돌려준다.
	// 원가 = 총매입금액 × 판 수량 ÷ 보유 수량 (원 단위 내림). 전부 팔면 남은 원가를 모두 뺀다
	long remove(int sellQuantity, Instant now) {
		if (sellQuantity <= 0 || sellQuantity > quantity) {
			throw new IllegalStateException("보유 수량보다 많이 팔 수 없습니다: quantity=" + quantity + ", sell=" + sellQuantity);
		}
		long cost = sellQuantity == quantity ? totalCost
				: Math.multiplyExact(totalCost, sellQuantity) / quantity;
		quantity -= sellQuantity;
		totalCost -= cost;
		updatedAt = now;
		return cost;
	}

	boolean isEmpty() {
		return quantity == 0;
	}

	public String getStockCode() {
		return stockCode;
	}

	public int getQuantity() {
		return quantity;
	}

	public long getTotalCost() {
		return totalCost;
	}

}
