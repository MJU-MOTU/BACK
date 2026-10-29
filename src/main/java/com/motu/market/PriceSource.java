package com.motu.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// 외부 시세 제공자. 실패하면 예외 대신 빈 값을 돌려주고, 대체 가격을 쓸지는 호출하는 쪽이 정한다
public interface PriceSource {

	Optional<LiveQuote> currentPrice(String code);

	Optional<List<DailyCandle>> dailyCandles(String code, LocalDate from, LocalDate to);

	record LiveQuote(long price, long changeAmount, BigDecimal changeRate) {
	}

	record DailyCandle(LocalDate date, long open, long high, long low, long close, long volume) {
	}

	// KIS 키가 없을 때 쓰는 빈 제공자
	static PriceSource none() {
		return new PriceSource() {
			@Override
			public Optional<LiveQuote> currentPrice(String code) {
				return Optional.empty();
			}

			@Override
			public Optional<List<DailyCandle>> dailyCandles(String code, LocalDate from, LocalDate to) {
				return Optional.empty();
			}
		};
	}

}
