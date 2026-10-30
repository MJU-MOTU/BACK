package com.motu.experiment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// B. 낙관적 락: 잠그지 않고 읽은 뒤 version이 그대로일 때만 쓴다. 바뀌었으면 트랜잭션을 버리고 처음부터 다시 한다
class OptimisticStrategy implements OrderStrategy {

	static final int MAX_RETRIES = 1_000;

	private final JdbcTemplate jdbc;
	private final OrderSql sql;
	private final TransactionTemplate tx;

	OptimisticStrategy(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
		this.jdbc = jdbc;
		this.sql = new OrderSql(jdbc);
		this.tx = new TransactionTemplate(txManager);
		this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	@Override
	public String name() {
		return "B 낙관적 락";
	}

	@Override
	public Outcome buy(long userId, String key, int quantity, long price) {
		return withRetry(userId, key, () -> attemptBuy(userId, key, quantity, price));
	}

	@Override
	public Outcome sell(long userId, String key, int quantity, long price) {
		return withRetry(userId, key, () -> attemptSell(userId, key, quantity, price));
	}

	private Outcome withRetry(long userId, String key, java.util.function.Supplier<Status> attempt) {
		for (int retries = 0;; retries++) {
			try {
				return new Outcome(tx.execute(status -> attempt.get()), retries);
			}
			catch (Conflict | DuplicateKeyException e) {
				// 같은 키를 다른 요청이 먼저 넣었으면 다시 할 필요가 없다
				if (sql.orderExists(userId, key)) {
					return new Outcome(Status.REPLAYED, retries);
				}
				if (retries >= MAX_RETRIES) {
					throw new IllegalStateException("재시도 한도 초과", e);
				}
				sleepBriefly();
			}
		}
	}

	private Status attemptBuy(long userId, String key, int quantity, long price) {
		if (sql.orderExists(userId, key)) {
			return Status.REPLAYED;
		}
		Map<String, Object> wallet = jdbc.queryForMap("select id, cash, version from wallets where user_id = ?",
				userId);
		long walletId = number(wallet, "id");
		long cash = number(wallet, "cash");
		long amount = price * quantity;
		if (cash < amount) {
			sql.insertOrder(userId, key, "BUY", quantity, "REJECTED", "INSUFFICIENT_CASH");
			return Status.REJECTED;
		}
		List<Map<String, Object>> holding = jdbc.queryForList(
				"select id, quantity, total_cost, version from holdings where user_id = ? and stock_code = ?", userId,
				OrderSql.STOCK);

		if (jdbc.update("update wallets set cash = ?, version = version + 1, updated_at = ? where id = ? and version = ?",
				cash - amount, sql.now(), walletId, number(wallet, "version")) == 0) {
			throw new Conflict();
		}
		if (holding.isEmpty()) {
			jdbc.update("""
					insert into holdings (user_id, stock_code, quantity, total_cost, updated_at)
					values (?, ?, ?, ?, ?)""", userId, OrderSql.STOCK, quantity, amount, sql.now());
		}
		else if (jdbc.update("""
				update holdings set quantity = ?, total_cost = ?, version = version + 1, updated_at = ?
				where id = ? and version = ?""", number(holding.get(0), "quantity") + quantity,
				number(holding.get(0), "total_cost") + amount, sql.now(), number(holding.get(0), "id"),
				number(holding.get(0), "version")) == 0) {
			throw new Conflict();
		}
		long orderId = sql.insertOrder(userId, key, "BUY", quantity, "FILLED", null);
		sql.insertLedger(walletId, "BUY", -amount, cash - amount, orderId);
		sql.insertExecution(orderId, price, quantity, amount);
		return Status.FILLED;
	}

	private Status attemptSell(long userId, String key, int quantity, long price) {
		if (sql.orderExists(userId, key)) {
			return Status.REPLAYED;
		}
		List<Map<String, Object>> found = jdbc.queryForList(
				"select id, quantity, total_cost, version from holdings where user_id = ? and stock_code = ?", userId,
				OrderSql.STOCK);
		if (found.isEmpty() || number(found.get(0), "quantity") < quantity) {
			sql.insertOrder(userId, key, "SELL", quantity, "REJECTED", "INSUFFICIENT_QUANTITY");
			return Status.REJECTED;
		}
		Map<String, Object> holding = found.get(0);
		long held = number(holding, "quantity");
		long totalCost = number(holding, "total_cost");
		long cost = held == quantity ? totalCost : totalCost * quantity / held;
		int changed = held == quantity
				? jdbc.update("delete from holdings where id = ? and version = ?", number(holding, "id"),
						number(holding, "version"))
				: jdbc.update("""
						update holdings set quantity = ?, total_cost = ?, version = version + 1, updated_at = ?
						where id = ? and version = ?""", held - quantity, totalCost - cost, sql.now(),
						number(holding, "id"), number(holding, "version"));
		if (changed == 0) {
			throw new Conflict();
		}
		Map<String, Object> wallet = jdbc.queryForMap("select id, cash, version from wallets where user_id = ?",
				userId);
		long amount = price * quantity;
		if (jdbc.update("update wallets set cash = ?, version = version + 1, updated_at = ? where id = ? and version = ?",
				number(wallet, "cash") + amount, sql.now(), number(wallet, "id"), number(wallet, "version")) == 0) {
			throw new Conflict();
		}
		long orderId = sql.insertOrder(userId, key, "SELL", quantity, "FILLED", null);
		sql.insertLedger(number(wallet, "id"), "SELL", amount, number(wallet, "cash") + amount, orderId);
		sql.insertExecution(orderId, price, quantity, amount);
		return Status.FILLED;
	}

	private static long number(Map<String, Object> row, String column) {
		return ((Number) row.get(column)).longValue();
	}

	private static void sleepBriefly() {
		try {
			Thread.sleep(ThreadLocalRandom.current().nextInt(0, 3));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	static class Conflict extends RuntimeException {

		Conflict() {
			super(null, null, false, false);
		}

	}

}
