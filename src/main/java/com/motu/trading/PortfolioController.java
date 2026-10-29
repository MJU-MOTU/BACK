package com.motu.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.motu.common.security.CurrentUser;
import com.motu.market.Quote;
import com.motu.market.QuoteService;
import com.motu.market.Stock;
import com.motu.market.StockRepository;
import com.motu.wallet.Wallet;
import com.motu.wallet.WalletRepository;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PortfolioController {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private final HoldingRepository holdingRepository;
	private final WalletRepository walletRepository;
	private final StockRepository stockRepository;
	private final QuoteService quoteService;

	PortfolioController(HoldingRepository holdingRepository, WalletRepository walletRepository,
			StockRepository stockRepository, QuoteService quoteService) {
		this.holdingRepository = holdingRepository;
		this.walletRepository = walletRepository;
		this.stockRepository = stockRepository;
		this.quoteService = quoteService;
	}

	// 시세를 못 가져온 종목은 평가금액·손익이 null이고, 합계에는 매입금액으로 넣는다(partial = true)
	public record HoldingView(String stockCode, String name, int quantity, BigDecimal averagePrice, long totalCost,
			Long currentPrice, Quote.Source priceSource, Long evaluationAmount, Long unrealizedPnl,
			BigDecimal unrealizedPnlRate) {
	}

	public record PortfolioResponse(long cash, List<HoldingView> holdings, long totalCost, long totalEvaluation,
			long totalAsset, long totalUnrealizedPnl, BigDecimal totalUnrealizedPnlRate, boolean partial) {
	}

	// 트랜잭션을 걸지 않는다. 시세 조회가 마지막 가격을 저장(쓰기)하므로 읽기 전용 트랜잭션 안에서 부를 수 없다
	@GetMapping("/api/v1/portfolio")
	PortfolioResponse portfolio(@AuthenticationPrincipal Jwt jwt) {
		Long userId = CurrentUser.id(jwt);
		Wallet wallet = walletRepository.findByUserId(userId)
			.orElseThrow(() -> new IllegalStateException("지갑이 없는 회원입니다"));
		List<Holding> holdings = holdingRepository.findByUserIdOrderByStockCode(userId);
		Map<String, String> names = stockRepository
			.findAllById(holdings.stream().map(Holding::getStockCode).toList()).stream()
			.collect(Collectors.toMap(Stock::getCode, Stock::getName));

		List<HoldingView> views = new ArrayList<>();
		long totalCost = 0;
		long totalEvaluation = 0;
		boolean partial = false;
		for (Holding holding : holdings) {
			Optional<Quote> quote = quoteService.find(holding.getStockCode());
			BigDecimal averagePrice = BigDecimal.valueOf(holding.getTotalCost())
				.divide(BigDecimal.valueOf(holding.getQuantity()), 2, RoundingMode.HALF_UP);
			totalCost += holding.getTotalCost();
			if (quote.isPresent()) {
				long evaluation = Math.multiplyExact(quote.get().price(), (long) holding.getQuantity());
				long pnl = evaluation - holding.getTotalCost();
				totalEvaluation += evaluation;
				views.add(new HoldingView(holding.getStockCode(), names.get(holding.getStockCode()),
						holding.getQuantity(), averagePrice, holding.getTotalCost(), quote.get().price(),
						quote.get().source(), evaluation, pnl, rate(pnl, holding.getTotalCost())));
			}
			else {
				partial = true;
				totalEvaluation += holding.getTotalCost();
				views.add(new HoldingView(holding.getStockCode(), names.get(holding.getStockCode()),
						holding.getQuantity(), averagePrice, holding.getTotalCost(), null, null, null, null, null));
			}
		}
		long totalPnl = totalEvaluation - totalCost;
		return new PortfolioResponse(wallet.getCash(), views, totalCost, totalEvaluation,
				wallet.getCash() + totalEvaluation, totalPnl, rate(totalPnl, totalCost), partial);
	}

	private static BigDecimal rate(long pnl, long cost) {
		if (cost == 0) {
			return BigDecimal.ZERO.setScale(2);
		}
		return BigDecimal.valueOf(pnl).multiply(HUNDRED).divide(BigDecimal.valueOf(cost), 2, RoundingMode.HALF_UP);
	}

}
