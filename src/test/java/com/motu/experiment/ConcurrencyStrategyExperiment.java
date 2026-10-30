package com.motu.experiment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.motu.TestcontainersConfiguration;
import com.motu.experiment.OrderStrategy.Outcome;
import com.motu.experiment.OrderStrategy.Status;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

// D4 비교 실험. 같은 시나리오를 세 전략으로 RUNS번씩 돌려 정확성·시간·재시도를 잰다.
// 실행: ./gradlew test -Pexperiments --tests com.motu.experiment.ConcurrencyStrategyExperiment
@Tag("experiment")
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/experiment")
class ConcurrencyStrategyExperiment {

	static final int RUNS = 5;
	static final long PRICE = 100_000;
	static final long INITIAL_CASH = 10_000_000;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager txManager;

	@Test
	void 세_전략을_같은_시나리오로_비교한다() throws Exception {
		List<OrderStrategy> strategies = List.of(new PessimisticStrategy(jdbc, txManager),
				new OptimisticStrategy(jdbc, txManager), new ConditionalUpdateStrategy(jdbc, txManager));
		List<Scenario> scenarios = List.of(
				new Scenario("S1 한 회원 150건 동시 매수(100건만 가능)", this::contendedBuys),
				new Scenario("S2 같은 키 10건 동시", this::sameKey),
				new Scenario("S3 보유 10주를 10주씩 5건 동시 매도", this::concurrentSells),
				new Scenario("S4 150명이 1건씩 동시 매수(경합 없음)", this::uncontendedBuys));

		Map<String, List<Run>> results = new LinkedHashMap<>();
		for (Scenario scenario : scenarios) {
			// 첫 실행은 JIT·커넥션 풀 예열로 버린다
			for (OrderStrategy strategy : strategies) {
				scenario.body().run(strategy);
			}
			// 전략 순서를 번갈아 돌려 시간에 따른 편향을 줄인다
			for (int run = 0; run < RUNS; run++) {
				for (OrderStrategy strategy : strategies) {
					results.computeIfAbsent(scenario.name() + "|" + strategy.name(), k -> new ArrayList<>())
						.add(scenario.body().run(strategy));
				}
			}
		}

		String report = report(results);
		System.out.println(report);
		Path out = Path.of("build/experiment/d4-results.md");
		Files.createDirectories(out.getParent());
		Files.writeString(out, report, StandardCharsets.UTF_8);

		// 실험 장치가 맞는지만 확인한다: 운영 코드와 같은 방식(A)은 항상 맞아야 한다
		results.forEach((k, runs) -> {
			if (k.endsWith("A 비관적 락")) {
				assertThat(runs).allMatch(Run::correct);
			}
		});
	}

	// --- 시나리오 ---

	Run contendedBuys(OrderStrategy strategy) throws Exception {
		Fixture user = newUser();
		Timed timed = runConcurrently(150, i -> () -> strategy.buy(user.userId, key(), 1, PRICE));
		long cash = cash(user);
		boolean correct = timed.count(Status.FILLED) == 100 && timed.count(Status.REJECTED) == 50
				&& timed.errors.isEmpty() && cash == 0 && holding(user).equals(List.of(100L, 10_000_000L))
				&& ledgerSum(user) == cash && orderCount(user) == 150;
		return timed.toRun(correct);
	}

	Run sameKey(OrderStrategy strategy) throws Exception {
		Fixture user = newUser();
		String key = key();
		Timed timed = runConcurrently(10, i -> () -> strategy.buy(user.userId, key, 1, PRICE));
		boolean correct = timed.errors.isEmpty() && orderCount(user) == 1 && cash(user) == INITIAL_CASH - PRICE
				&& timed.count(Status.FILLED) == 1 && timed.count(Status.REPLAYED) == 9;
		return timed.toRun(correct);
	}

	Run concurrentSells(OrderStrategy strategy) throws Exception {
		Fixture user = newUser();
		strategy.buy(user.userId, key(), 10, PRICE);
		Timed timed = runConcurrently(5, i -> () -> strategy.sell(user.userId, key(), 10, PRICE));
		long cash = cash(user);
		boolean correct = timed.errors.isEmpty() && timed.count(Status.FILLED) == 1
				&& timed.count(Status.REJECTED) == 4 && holding(user).isEmpty() && cash == INITIAL_CASH
				&& ledgerSum(user) == cash;
		return timed.toRun(correct);
	}

	Run uncontendedBuys(OrderStrategy strategy) throws Exception {
		List<Fixture> users = new ArrayList<>();
		for (int i = 0; i < 150; i++) {
			users.add(newUser());
		}
		Timed timed = runConcurrently(150, i -> () -> strategy.buy(users.get(i).userId, key(), 1, PRICE));
		boolean correct = timed.errors.isEmpty() && timed.count(Status.FILLED) == 150
				&& users.stream().allMatch(u -> cash(u) == INITIAL_CASH - PRICE);
		return timed.toRun(correct);
	}

	// --- 실행·측정 ---

	interface Body {

		Run run(OrderStrategy strategy) throws Exception;

	}

	record Scenario(String name, Body body) {
	}

	record Run(boolean correct, long millis, long retries, int errors) {
	}

	static class Timed {

		final List<Outcome> outcomes = new ArrayList<>();
		final List<Throwable> errors = new ArrayList<>();
		long millis;

		long count(Status status) {
			return outcomes.stream().filter(o -> o.status() == status).count();
		}

		Run toRun(boolean correct) {
			return new Run(correct, millis, outcomes.stream().mapToLong(Outcome::retries).sum(), errors.size());
		}

	}

	interface TaskFactory {

		Callable<Outcome> task(int index);

	}

	// 모든 스레드를 준비시킨 뒤 한꺼번에 출발시키고, 출발부터 마지막 완료까지 시간을 잰다
	static Timed runConcurrently(int count, TaskFactory factory) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(count);
		CountDownLatch ready = new CountDownLatch(count);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Outcome>> futures = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Callable<Outcome> task = factory.task(i);
			futures.add(pool.submit(() -> {
				ready.countDown();
				start.await();
				return task.call();
			}));
		}
		ready.await();
		Timed timed = new Timed();
		long begin = System.nanoTime();
		start.countDown();
		for (Future<Outcome> future : futures) {
			try {
				timed.outcomes.add(future.get());
			}
			catch (ExecutionException e) {
				timed.errors.add(e.getCause());
			}
		}
		timed.millis = (System.nanoTime() - begin) / 1_000_000;
		pool.shutdown();
		return timed;
	}

	// --- 데이터 준비·확인 (가입 API는 BCrypt 때문에 느려서 JDBC로 직접 넣는다) ---

	record Fixture(long userId, long walletId) {
	}

	Fixture newUser() {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(
					"insert into users (email, password_hash, nickname, created_at) values (?, 'x', '실험', ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, "exp-" + UUID.randomUUID() + "@motu.test");
			ps.setTimestamp(2, Timestamp.from(Instant.now()));
			return ps;
		}, keys);
		long userId = Objects.requireNonNull(keys.getKey()).longValue();
		GeneratedKeyHolder walletKeys = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(
					"insert into wallets (user_id, cash, updated_at) values (?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
			ps.setLong(1, userId);
			ps.setLong(2, INITIAL_CASH);
			ps.setTimestamp(3, Timestamp.from(Instant.now()));
			return ps;
		}, walletKeys);
		long walletId = Objects.requireNonNull(walletKeys.getKey()).longValue();
		jdbc.update("""
				insert into wallet_ledger (wallet_id, type, amount, balance_after, order_id, created_at)
				values (?, 'INITIAL', ?, ?, null, ?)""", walletId, INITIAL_CASH, INITIAL_CASH,
				Timestamp.from(Instant.now()));
		return new Fixture(userId, walletId);
	}

	long cash(Fixture user) {
		return Objects.requireNonNull(
				jdbc.queryForObject("select cash from wallets where id = ?", Long.class, user.walletId));
	}

	long ledgerSum(Fixture user) {
		return Objects.requireNonNull(jdbc.queryForObject(
				"select coalesce(sum(amount), 0) from wallet_ledger where wallet_id = ?", Long.class, user.walletId));
	}

	long orderCount(Fixture user) {
		return Objects.requireNonNull(
				jdbc.queryForObject("select count(*) from orders where user_id = ?", Long.class, user.userId));
	}

	List<Long> holding(Fixture user) {
		return jdbc.query("select quantity, total_cost from holdings where user_id = ? and stock_code = ?",
				rs -> rs.next() ? List.of(rs.getLong(1), rs.getLong(2)) : List.<Long>of(), user.userId, OrderSql.STOCK);
	}

	static String key() {
		return UUID.randomUUID().toString();
	}

	// --- 결과 표 ---

	static String report(Map<String, List<Run>> results) {
		StringBuilder sb = new StringBuilder();
		sb.append("| 시나리오 | 전략 | 정확 | 시간 중앙값(ms) | 최소~최대(ms) | 재시도 중앙값 | 예외 합계 |\n");
		sb.append("| --- | --- | --- | --- | --- | --- | --- |\n");
		results.forEach((key, runs) -> {
			String[] parts = key.split("\\|");
			List<Long> times = runs.stream().map(Run::millis).sorted().toList();
			List<Long> retries = runs.stream().map(Run::retries).sorted().toList();
			sb.append("| %s | %s | %d/%d | %d | %d~%d | %d | %d |\n".formatted(parts[0], parts[1],
					runs.stream().filter(Run::correct).count(), runs.size(), times.get(times.size() / 2),
					times.getFirst(), times.getLast(), retries.get(retries.size() / 2),
					runs.stream().mapToInt(Run::errors).sum()));
		});
		return sb.toString();
	}

}
