package com.motu.market;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// KIS에서 마지막으로 받아 온 가격. 조회가 실패할 때 대신 보여 준다
@Entity
@Table(name = "stock_prices")
public class StockPrice {

	@Id
	@Column(name = "stock_code", length = 6)
	private String stockCode;

	@Column(nullable = false)
	private long price;

	@Column(name = "change_amount", nullable = false)
	private long changeAmount;

	@Column(name = "change_rate", nullable = false, precision = 7, scale = 2)
	private BigDecimal changeRate;

	@Column(name = "as_of", nullable = false)
	private Instant asOf;

	protected StockPrice() {
	}

	public StockPrice(String stockCode, long price, long changeAmount, BigDecimal changeRate, Instant asOf) {
		this.stockCode = stockCode;
		update(price, changeAmount, changeRate, asOf);
	}

	public void update(long price, long changeAmount, BigDecimal changeRate, Instant asOf) {
		this.price = price;
		this.changeAmount = changeAmount;
		this.changeRate = changeRate;
		this.asOf = asOf;
	}

	public String getStockCode() {
		return stockCode;
	}

	public long getPrice() {
		return price;
	}

	public long getChangeAmount() {
		return changeAmount;
	}

	public BigDecimal getChangeRate() {
		return changeRate;
	}

	public Instant getAsOf() {
		return asOf;
	}

}
