package com.motu.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import com.motu.IntegrationTest;
import com.motu.common.error.BusinessException;
import com.motu.common.error.ErrorCode;
import com.motu.market.StockPrice;
import com.motu.market.StockPriceRepository;
import com.motu.support.TestUsers;
import com.motu.wallet.LedgerEntryRepository;
import com.motu.wallet.Wallet;
import com.motu.wallet.WalletRepository;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 MySQL(Testcontainers)에 여러 스레드가 동시에 주문을 넣는다. 돈과 수량이 하나도 어긋나면 안 된다(R1, R2, R4, R5)
@IntegrationTest
class OrderConcurrencyTests {

	static final String LG_ELECTRONICS = "066570";

	@Autowired
	OrderService orderService;

	@Autowired
	TestUsers testUsers;

	@Autowired
	StockPriceRepository stockPriceRepository;

	@Autowired
	WalletRepository walletRepository;

	@Autowired
	LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	HoldingRepository holdingRepository;

	@Autowired
	OrderRepository orderRepository;

	@Test
	void 잔고로_100주만_살_수_있을_때_150건을_동시에_보내도_100건만_체결된다() throws Exception {
		Long userId = testUsers.create();
		setPrice(LG_ELECTRONICS, 100_000);

		Outcomes outcomes = runConcurrently(150,
				i -> () -> orderService.place(userId, buy(1, UUID.randomUUID().toString())));

		assertThat(outcomes.filled).hasSize(100);
		assertThat(outcomes.rejectedWith(ErrorCode.INSUFFICIENT_CASH)).isEqualTo(50);
		assertThat(outcomes.unexpected).isEmpty();
		Wallet wallet = walletRepository.findByUserId(userId).orElseThrow();
		assertThat(wallet.getCash()).isZero();
		Holding holding = holdingRepository.findByUserIdAndStockCode(userId, LG_ELECTRONICS).orElseThrow();
		assertThat(holding.getQuantity()).isEqualTo(100);
		assertThat(holding.getTotalCost()).isEqualTo(10_000_000);
		assertThat(ledgerEntryRepository.sumAmountByWalletId(wallet.getId())).isEqualTo(wallet.getCash());
	}

	@Test
	void 같은_키로_10건을_동시에_보내도_주문은_하나만_생긴다() throws Exception {
		Long userId = testUsers.create();
		setPrice(LG_ELECTRONICS, 100_000);
		String key = UUID.randomUUID().toString();

		Outcomes outcomes = runConcurrently(10, i -> () -> orderService.place(userId, buy(1, key)));

		assertThat(outcomes.unexpected).isEmpty();
		assertThat(outcomes.filled).hasSize(10);
		assertThat(outcomes.filled.stream().map(OrderResult::orderId).distinct()).hasSize(1);
		assertThat(outcomes.filled.stream().filter(r -> !r.replayed())).hasSize(1);
		assertThat(walletRepository.findByUserId(userId).orElseThrow().getCash()).isEqualTo(9_900_000);
		assertThat(orderRepository.findByUserIdOrderByIdDesc(userId, org.springframework.data.domain.Pageable.unpaged())
			.getTotalElements()).isEqualTo(1);
	}

	// 잔고 부족으로 거절될 주문을 더블클릭한 상황. 잠금을 기다린 요청은 잠금 안에서 같은 키를 다시 확인하고
	// 처음 결과(거절)를 그대로 돌려줘야 한다. 500(UnexpectedRollbackException)이 나면 안 된다
	@Test
	void 거절될_주문을_같은_키로_동시에_보내도_모두_같은_거절을_받는다() throws Exception {
		Long userId = testUsers.create();
		setPrice(LG_ELECTRONICS, 100_000);
		String key = UUID.randomUUID().toString();

		Outcomes outcomes = runConcurrently(10, i -> () -> orderService.place(userId, buy(200, key)));

		assertThat(outcomes.unexpected).isEmpty();
		assertThat(outcomes.rejectedWith(ErrorCode.INSUFFICIENT_CASH)).isEqualTo(10);
		assertThat(orderRepository.findByUserIdOrderByIdDesc(userId, org.springframework.data.domain.Pageable.unpaged())
			.getTotalElements()).isEqualTo(1);
	}

	@Test
	void 보유_10주를_10주씩_5건_동시에_팔면_1건만_체결된다() throws Exception {
		Long userId = testUsers.create();
		setPrice(LG_ELECTRONICS, 100_000);
		orderService.place(userId, buy(10, UUID.randomUUID().toString()));

		Outcomes outcomes = runConcurrently(5,
				i -> () -> orderService.place(userId, new OrderCommand(LG_ELECTRONICS, Side.SELL, 10,
						UUID.randomUUID().toString())));

		assertThat(outcomes.unexpected).isEmpty();
		assertThat(outcomes.filled).hasSize(1);
		assertThat(outcomes.rejectedWith(ErrorCode.INSUFFICIENT_QUANTITY)).isEqualTo(4);
		assertThat(holdingRepository.findByUserIdAndStockCode(userId, LG_ELECTRONICS)).isEmpty();
		Wallet wallet = walletRepository.findByUserId(userId).orElseThrow();
		assertThat(wallet.getCash()).isEqualTo(10_000_000);
		assertThat(ledgerEntryRepository.sumAmountByWalletId(wallet.getId())).isEqualTo(wallet.getCash());
	}

	private static OrderCommand buy(int quantity, String key) {
		return new OrderCommand(LG_ELECTRONICS, Side.BUY, quantity, key);
	}

	private void setPrice(String code, long price) {
		StockPrice stockPrice = stockPriceRepository.findById(code)
			.orElseGet(() -> new StockPrice(code, price, 0, BigDecimal.ZERO, Instant.now()));
		stockPrice.update(price, 0, BigDecimal.ZERO, Instant.now());
		stockPriceRepository.save(stockPrice);
	}

	// 모든 스레드를 준비시킨 뒤 한꺼번에 출발시킨다
	private static Outcomes runConcurrently(int count, IntFunction<Callable<OrderResult>> task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(count);
		CountDownLatch ready = new CountDownLatch(count);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<OrderResult>> futures = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Callable<OrderResult> callable = task.apply(i);
			futures.add(pool.submit(() -> {
				ready.countDown();
				start.await();
				return callable.call();
			}));
		}
		ready.await();
		start.countDown();

		Outcomes outcomes = new Outcomes();
		for (Future<OrderResult> future : futures) {
			try {
				outcomes.filled.add(future.get());
			}
			catch (java.util.concurrent.ExecutionException e) {
				if (e.getCause() instanceof BusinessException business) {
					outcomes.rejected.add(business.errorCode());
				}
				else {
					outcomes.unexpected.add(e.getCause());
				}
			}
		}
		pool.shutdown();
		return outcomes;
	}

	static class Outcomes {

		final List<OrderResult> filled = new ArrayList<>();
		final List<ErrorCode> rejected = new ArrayList<>();
		final List<Throwable> unexpected = new ArrayList<>();

		long rejectedWith(ErrorCode code) {
			return rejected.stream().filter(code::equals).count();
		}

	}

}
