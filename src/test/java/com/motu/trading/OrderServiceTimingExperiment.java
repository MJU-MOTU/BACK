package com.motu.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import com.motu.IntegrationTest;
import com.motu.common.error.BusinessException;
import com.motu.market.Quote;
import com.motu.market.QuoteService;
import com.motu.market.StockPrice;
import com.motu.market.StockPriceRepository;
import com.motu.market.StockRepository;
import com.motu.support.TestUsers;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

// 운영 OrderService로 "한 회원 150건 동시 매수"를 D4 실험과 같은 방식(출발 → 마지막 완료)으로 잰다.
// 단계별 누적 시간과 커넥션을 기다린 스레드 수(최대)도 본다
@Tag("experiment")
@IntegrationTest
class OrderServiceTimingExperiment {

	static final String STOCK = "066570";
	static final int RUNS = 5;

	@Autowired
	OrderService orderService;

	@Autowired
	OrderReplays orderReplays;

	@Autowired
	OrderPreparation orderPreparation;

	@Autowired
	OrderExecutor orderExecutor;

	@Autowired
	QuoteService quoteService;

	@Autowired
	StockRepository stockRepository;

	@Autowired
	StockPriceRepository stockPriceRepository;

	@Autowired
	TestUsers testUsers;

	@Autowired
	DataSource dataSource;

	@Autowired
	org.springframework.jdbc.core.JdbcTemplate jdbc;

	@Test
	void 운영_주문_경로의_시간을_잰다() throws Exception {
		StockPrice price = stockPriceRepository.findById(STOCK)
			.orElseGet(() -> new StockPrice(STOCK, 100_000, 0, BigDecimal.ZERO, Instant.now()));
		price.update(100_000, 0, BigDecimal.ZERO, Instant.now());
		stockPriceRepository.save(price);
		HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
		System.out.println("hikari maximumPoolSize=" + hikari.getMaximumPoolSize());

		run(hikari, false);
		List<String> rows = new ArrayList<>();
		for (int i = 0; i < RUNS; i++) {
			rows.add(run(hikari, false));
		}
		// 단계별 측정은 OrderService가 단계를 나눠 부르는 구조일 때만 의미가 있다. PHASED=true 환경 변수로 켠다
		if ("true".equals(System.getenv("PHASED"))) {
			rows.add("-- 단계별 측정(같은 순서를 테스트에서 직접 호출) --");
			for (int i = 0; i < RUNS; i++) {
				rows.add(run(hikari, true));
			}
		}
		// 한 스레드로 150건을 차례로 넣으면 경합 없이 주문 1건의 단계별 비용이 보인다. SEQUENTIAL=true 환경 변수로 켠다
		if ("true".equals(System.getenv("SEQUENTIAL"))) {
			for (int i = 0; i < 3; i++) {
				rows.add(sequential());
			}
		}
		if ("true".equals(System.getenv("NOISY"))) {
			noisyNeighbor();
			for (int i = 0; i < RUNS; i++) {
				rows.add(noisyNeighbor());
			}
		}
		rows.forEach(System.out::println);
	}

	// 한 회원이 150건을 쏟아내는 동안 다른 회원 20명이 1건씩 주문할 때, 그 20명이 얼마나 기다리는지 잰다. NOISY=true로 켠다
	private String noisyNeighbor() throws Exception {
		Long heavy = testUsers.create();
		List<Long> others = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			others.add(testUsers.create());
		}
		int total = 150 + others.size();
		ExecutorService pool = Executors.newFixedThreadPool(total);
		CountDownLatch ready = new CountDownLatch(total);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Long>> otherLatencies = new ArrayList<>();
		List<Future<Long>> all = new ArrayList<>();
		for (int i = 0; i < total; i++) {
			Long userId = i < 150 ? heavy : others.get(i - 150);
			Future<Long> f = pool.submit(() -> {
				ready.countDown();
				start.await();
				long t0 = System.nanoTime();
				try {
					orderService.place(userId, new OrderCommand(STOCK, Side.BUY, 1, UUID.randomUUID().toString()));
				}
				catch (BusinessException expected) {
					// 잔고 부족 거절
				}
				return (System.nanoTime() - t0) / 1_000_000;
			});
			all.add(f);
			if (i >= 150) {
				otherLatencies.add(f);
			}
		}
		ready.await();
		start.countDown();
		for (Future<Long> f : all) {
			f.get();
		}
		pool.shutdown();
		List<Long> latencies = new ArrayList<>();
		for (Future<Long> f : otherLatencies) {
			latencies.add(f.get());
		}
		latencies.sort(null);
		return "noisy: 다른 회원 20명 지연 중앙값 %dms, 최대 %dms".formatted(latencies.get(latencies.size() / 2),
				latencies.getLast());
	}

	// MySQL이 받은 문장 수(SHOW GLOBAL STATUS). Questions = 클라이언트가 보낸 문장 전체
	private java.util.Map<String, Long> mysqlCounters() {
		java.util.Map<String, Long> counters = new java.util.HashMap<>();
		jdbc.query("show global status where Variable_name in ('Questions','Com_select','Com_insert','Com_update','Com_set_option','Com_commit','Com_rollback')",
				rs -> {
					counters.put(rs.getString(1), rs.getLong(2));
				});
		return counters;
	}

	private String sequential() {
		Long userId = testUsers.create();
		java.util.Map<String, Long> before = mysqlCounters();
		long replay = 0;
		long quote = 0;
		long execute = 0;
		long begin = System.nanoTime();
		for (int i = 0; i < 150; i++) {
			OrderCommand command = new OrderCommand(STOCK, Side.BUY, 1, UUID.randomUUID().toString());
			long t0 = System.nanoTime();
			Quote q = orderPreparation.prepare(userId, command).quote();
			long t1 = System.nanoTime();
			long t2 = t1;
			try {
				orderExecutor.execute(userId, command, q);
			}
			catch (BusinessException expected) {
				// 101건째부터 잔고 부족
			}
			long t3 = System.nanoTime();
			replay += t1 - t0;
			quote += t2 - t1;
			execute += t3 - t2;
		}
		long total = (System.nanoTime() - begin) / 1_000_000;
		java.util.Map<String, Long> after = mysqlCounters();
		StringBuilder perOrder = new StringBuilder();
		for (String name : java.util.List.of("Questions", "Com_select", "Com_insert", "Com_update", "Com_set_option",
				"Com_commit")) {
			// 카운터를 읽는 쿼리 자체(1건)는 빼고 주문 150건으로 나눈다
			long delta = after.get(name) - before.get(name) - (name.equals("Questions") ? 1 : 0);
			perOrder.append(" %s=%.1f".formatted(name, delta / 150.0));
		}
		return "sequential: %dms, 건당 평균 준비 %.2fms / (시세 포함) %.2fms / 체결 %.2fms | 건당 문장%s".formatted(total,
				replay / 150 / 1e6, quote / 150 / 1e6, execute / 150 / 1e6, perOrder);
	}

	private String run(HikariDataSource hikari, boolean phased) throws Exception {
		Long userId = testUsers.create();
		AtomicLong replayNanos = new AtomicLong();
		AtomicLong quoteNanos = new AtomicLong();
		AtomicLong executeNanos = new AtomicLong();
		AtomicInteger maxWaiting = new AtomicInteger();
		AtomicBoolean sampling = new AtomicBoolean(true);
		Thread sampler = new Thread(() -> {
			while (sampling.get()) {
				maxWaiting.accumulateAndGet(hikari.getHikariPoolMXBean().getThreadsAwaitingConnection(), Math::max);
				Thread.onSpinWait();
			}
		});

		ExecutorService pool = Executors.newFixedThreadPool(150);
		CountDownLatch ready = new CountDownLatch(150);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < 150; i++) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				start.await();
				OrderCommand command = new OrderCommand(STOCK, Side.BUY, 1, UUID.randomUUID().toString());
				try {
					if (!phased) {
						orderService.place(userId, command);
						return null;
					}
					long t0 = System.nanoTime();
					orderReplays.find(userId, command);
					stockRepository.existsById(STOCK);
					long t1 = System.nanoTime();
					Quote quote = quoteService.get(STOCK);
					long t2 = System.nanoTime();
					try {
						orderExecutor.execute(userId, command, quote);
					}
					finally {
						long t3 = System.nanoTime();
						replayNanos.addAndGet(t1 - t0);
						quoteNanos.addAndGet(t2 - t1);
						executeNanos.addAndGet(t3 - t2);
					}
				}
				catch (BusinessException expected) {
					// 잔고 부족 거절
				}
				return null;
			}));
		}
		ready.await();
		sampler.start();
		long begin = System.nanoTime();
		start.countDown();
		for (Future<?> f : futures) {
			f.get();
		}
		long millis = (System.nanoTime() - begin) / 1_000_000;
		sampling.set(false);
		sampler.join();
		pool.shutdown();
		if (!phased) {
			return "run: %dms, 커넥션 대기 스레드 최대 %d".formatted(millis, maxWaiting.get());
		}
		return "run: %dms, 대기 최대 %d, 누적(스레드 합) 확인 %dms / 시세 %dms / 체결 %dms".formatted(millis,
				maxWaiting.get(), replayNanos.get() / 1_000_000, quoteNanos.get() / 1_000_000,
				executeNanos.get() / 1_000_000);
	}

}
