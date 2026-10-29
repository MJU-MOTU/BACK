package com.motu.market;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import com.motu.common.error.BusinessException;
import com.motu.common.error.ErrorCode;
import com.motu.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuoteServiceTests {

	MutableClock clock = new MutableClock(Instant.parse("2026-10-29T01:00:00Z"));
	FakePriceSource source = new FakePriceSource();
	StockPriceRepository repository = mock(StockPriceRepository.class);
	QuoteService service;

	@BeforeEach
	void setUp() {
		when(repository.findById(any())).thenReturn(Optional.empty());
		service = new QuoteService(source, repository, clock);
	}

	@Test
	void KIS에서_받은_가격을_돌려주고_마지막_가격으로_저장한다() {
		source.next(71200);

		Quote quote = service.get("005930");

		assertThat(quote.price()).isEqualTo(71200);
		assertThat(quote.source()).isEqualTo(Quote.Source.LIVE);
		verify(repository).save(any(StockPrice.class));
	}

	@Test
	void 캐시_시간_안에는_KIS를_다시_부르지_않는다() {
		source.next(71200);
		source.next(71300);

		service.get("005930");
		clock.advance(QuoteService.CACHE_TTL.minusMillis(1));
		assertThat(service.get("005930").price()).isEqualTo(71200);
		assertThat(source.calls).isEqualTo(1);

		clock.advance(Duration.ofMillis(1));
		assertThat(service.get("005930").price()).isEqualTo(71300);
		assertThat(source.calls).isEqualTo(2);
	}

	@Test
	void KIS가_실패하면_저장된_마지막_가격을_STORED로_돌려준다() {
		Instant yesterday = clock.instant().minus(Duration.ofDays(1));
		when(repository.findById("005930"))
			.thenReturn(Optional.of(new StockPrice("005930", 70000, 500, new BigDecimal("0.72"), yesterday)));

		Quote quote = service.get("005930");

		assertThat(quote.source()).isEqualTo(Quote.Source.STORED);
		assertThat(quote.price()).isEqualTo(70000);
		assertThat(quote.asOf()).isEqualTo(yesterday);
	}

	@Test
	void KIS도_저장된_가격도_없으면_PRICE_UNAVAILABLE이다() {
		assertThat(service.find("005930")).isEmpty();
		assertThatThrownBy(() -> service.get("005930"))
			.isInstanceOfSatisfying(BusinessException.class,
					e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
	}

	// 미리 넣어 둔 가격을 순서대로 돌려주고, 없으면 실패(빈 값)한다
	static class FakePriceSource implements PriceSource {

		final Deque<Long> prices = new ArrayDeque<>();
		int calls;

		void next(long price) {
			prices.add(price);
		}

		@Override
		public Optional<LiveQuote> currentPrice(String code) {
			calls++;
			Long price = prices.poll();
			return price == null ? Optional.empty() : Optional.of(new LiveQuote(price, 0, BigDecimal.ZERO));
		}

		@Override
		public Optional<List<DailyCandle>> dailyCandles(String code, LocalDate from, LocalDate to) {
			return Optional.empty();
		}

	}

}
