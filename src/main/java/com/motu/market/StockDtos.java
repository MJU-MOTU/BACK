package com.motu.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class StockDtos {

	private StockDtos() {
	}

	// 목록은 KIS를 부르지 않고 저장된 마지막 가격만 보여 준다. 가격을 받은 적이 없으면 null이다
	public record StockSummary(String code, String name, Market market, Long price, BigDecimal changeRate,
			Instant priceAsOf) {
	}

	public record StockPage(List<StockSummary> items, int page, int size, long totalElements, int totalPages) {
	}

	// 시세를 가져올 수 없으면 quote는 null이다
	public record StockDetail(String code, String name, Market market, Quote quote) {
	}

	public record CandleResponse(String code, LocalDate from, LocalDate to, List<PriceSource.DailyCandle> candles) {
	}

}
