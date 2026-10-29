package com.motu.market;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.motu.common.error.BusinessException;
import com.motu.common.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteService {

	// 같은 종목을 이 시간 안에 다시 물으면 KIS를 부르지 않는다. KIS 호출 수 제한 때문이다
	static final Duration CACHE_TTL = Duration.ofSeconds(5);

	private final PriceSource priceSource;
	private final StockPriceRepository stockPriceRepository;
	private final Clock clock;
	private final Map<String, Quote> cache = new ConcurrentHashMap<>();

	QuoteService(PriceSource priceSource, StockPriceRepository stockPriceRepository, Clock clock) {
		this.priceSource = priceSource;
		this.stockPriceRepository = stockPriceRepository;
		this.clock = clock;
	}

	// 캐시 → KIS → 저장된 마지막 가격 순서로 찾는다. 셋 다 없으면 시세를 줄 수 없다
	@Transactional
	public Optional<Quote> find(String code) {
		Instant now = clock.instant();
		Quote cached = cache.get(code);
		if (cached != null && now.isBefore(cached.asOf().plus(CACHE_TTL))) {
			return Optional.of(cached);
		}

		Optional<PriceSource.LiveQuote> live = priceSource.currentPrice(code);
		if (live.isPresent()) {
			PriceSource.LiveQuote value = live.get();
			Quote quote = new Quote(code, value.price(), value.changeAmount(), value.changeRate(), now, Quote.Source.LIVE);
			cache.put(code, quote);
			saveLastPrice(quote);
			return Optional.of(quote);
		}

		return stockPriceRepository.findById(code).map(Quote::stored);
	}

	public Quote get(String code) {
		return find(code).orElseThrow(() -> new BusinessException(ErrorCode.PRICE_UNAVAILABLE));
	}

	private void saveLastPrice(Quote quote) {
		stockPriceRepository.findById(quote.code()).ifPresentOrElse(
				saved -> saved.update(quote.price(), quote.changeAmount(), quote.changeRate(), quote.asOf()),
				() -> stockPriceRepository.save(new StockPrice(quote.code(), quote.price(), quote.changeAmount(),
						quote.changeRate(), quote.asOf())));
	}

}
