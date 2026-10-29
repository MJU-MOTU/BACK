package com.motu.market;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.motu.common.error.BusinessException;
import com.motu.common.error.ErrorCode;
import com.motu.market.StockDtos.CandleResponse;
import com.motu.market.StockDtos.StockDetail;
import com.motu.market.StockDtos.StockPage;
import com.motu.market.StockDtos.StockSummary;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stocks")
public class StockController {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final int MAX_PAGE_SIZE = 50;

	private final StockRepository stockRepository;
	private final StockPriceRepository stockPriceRepository;
	private final QuoteService quoteService;
	private final PriceSource priceSource;
	private final Clock clock;

	StockController(StockRepository stockRepository, StockPriceRepository stockPriceRepository,
			QuoteService quoteService, PriceSource priceSource, Clock clock) {
		this.stockRepository = stockRepository;
		this.stockPriceRepository = stockPriceRepository;
		this.quoteService = quoteService;
		this.priceSource = priceSource;
		this.clock = clock;
	}

	@GetMapping
	@Transactional(readOnly = true)
	StockPage search(@RequestParam(required = false) String query, @RequestParam(required = false) Market market,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		String keyword = (query == null || query.isBlank()) ? null : query.strip();
		PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
		Page<Stock> stocks = stockRepository.search(keyword, market, pageable);

		Map<String, StockPrice> prices = stockPriceRepository
			.findAllById(stocks.map(Stock::getCode).getContent()).stream()
			.collect(Collectors.toMap(StockPrice::getStockCode, Function.identity()));

		return new StockPage(stocks.map(stock -> {
			StockPrice price = prices.get(stock.getCode());
			return new StockSummary(stock.getCode(), stock.getName(), stock.getMarket(),
					price == null ? null : price.getPrice(), price == null ? null : price.getChangeRate(),
					price == null ? null : price.getAsOf());
		}).getContent(), stocks.getNumber(), stocks.getSize(), stocks.getTotalElements(), stocks.getTotalPages());
	}

	@GetMapping("/{code}")
	StockDetail detail(@PathVariable String code) {
		Stock stock = findStock(code);
		return new StockDetail(stock.getCode(), stock.getName(), stock.getMarket(),
				quoteService.find(stock.getCode()).orElse(null));
	}

	// 기본은 최근 3개월. KIS는 한 번에 최대 100개를 준다
	@GetMapping("/{code}/candles")
	CandleResponse candles(@PathVariable String code,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		Stock stock = findStock(code);
		LocalDate end = to != null ? to : LocalDate.now(clock.withZone(KST));
		LocalDate start = from != null ? from : end.minusMonths(3);
		if (start.isAfter(end)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return priceSource.dailyCandles(stock.getCode(), start, end)
			.map(candles -> new CandleResponse(stock.getCode(), start, end, candles))
			.orElseThrow(() -> new BusinessException(ErrorCode.PRICE_UNAVAILABLE));
	}

	private Stock findStock(String code) {
		return stockRepository.findById(code).orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));
	}

}
