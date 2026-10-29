package com.motu.market;

import java.math.BigDecimal;
import java.time.Instant;

public record Quote(String code, long price, long changeAmount, BigDecimal changeRate, Instant asOf, Source source) {

	// LIVE: 방금 KIS에서 받은 값(또는 몇 초 안의 캐시), STORED: KIS가 실패해서 보여 주는 마지막 가격
	public enum Source {
		LIVE, STORED
	}

	static Quote stored(StockPrice price) {
		return new Quote(price.getStockCode(), price.getPrice(), price.getChangeAmount(), price.getChangeRate(),
				price.getAsOf(), Source.STORED);
	}

}
