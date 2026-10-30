package com.motu.experiment;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// A. 지금 운영 코드와 같은 방식: 지갑 SELECT ... FOR UPDATE로 회원별 주문을 한 줄로 세운다
class PessimisticStrategy implements OrderStrategy {

	private final JdbcTemplate jdbc;
	private final OrderSql sql;
	private final TransactionTemplate tx;

	PessimisticStrategy(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
		this.jdbc = jdbc;
		this.sql = new OrderSql(jdbc);
		this.tx = new TransactionTemplate(txManager);
		this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	@Override
	public String name() {
		return "A 비관적 락";
	}

	@Override
	public Outcome buy(long userId, String key, int quantity, long price) {
		return tx.execute(status -> {
			Map<String, Object> wallet = jdbc.queryForMap("select id, cash from wallets where user_id = ? for update",
					userId);
			if (sql.orderExists(userId, key)) {
				return Outcome.of(Status.REPLAYED);
			}
			long walletId = ((Number) wallet.get("id")).longValue();
			long cash = ((Number) wallet.get("cash")).longValue();
			long amount = price * quantity;
			if (cash < amount) {
				sql.insertOrder(userId, key, "BUY", quantity, "REJECTED", "INSUFFICIENT_CASH");
				return Outcome.of(Status.REJECTED);
			}
			long orderId = sql.insertOrder(userId, key, "BUY", quantity, "FILLED", null);
			jdbc.update("update wallets set cash = cash - ?, updated_at = ? where id = ?", amount, sql.now(), walletId);
			sql.insertLedger(walletId, "BUY", -amount, cash - amount, orderId);
			List<Map<String, Object>> holding = jdbc.queryForList(
					"select id from holdings where user_id = ? and stock_code = ?", userId, OrderSql.STOCK);
			if (holding.isEmpty()) {
				jdbc.update("""
						insert into holdings (user_id, stock_code, quantity, total_cost, updated_at)
						values (?, ?, ?, ?, ?)""", userId, OrderSql.STOCK, quantity, amount, sql.now());
			}
			else {
				jdbc.update("""
						update holdings set quantity = quantity + ?, total_cost = total_cost + ?, updated_at = ?
						where id = ?""", quantity, amount, sql.now(), holding.get(0).get("id"));
			}
			sql.insertExecution(orderId, price, quantity, amount);
			return Outcome.of(Status.FILLED);
		});
	}

	@Override
	public Outcome sell(long userId, String key, int quantity, long price) {
		return tx.execute(status -> {
			Map<String, Object> wallet = jdbc.queryForMap("select id, cash from wallets where user_id = ? for update",
					userId);
			if (sql.orderExists(userId, key)) {
				return Outcome.of(Status.REPLAYED);
			}
			long walletId = ((Number) wallet.get("id")).longValue();
			long cash = ((Number) wallet.get("cash")).longValue();
			List<Map<String, Object>> found = jdbc.queryForList(
					"select id, quantity, total_cost from holdings where user_id = ? and stock_code = ?", userId,
					OrderSql.STOCK);
			if (found.isEmpty() || ((Number) found.get(0).get("quantity")).intValue() < quantity) {
				sql.insertOrder(userId, key, "SELL", quantity, "REJECTED", "INSUFFICIENT_QUANTITY");
				return Outcome.of(Status.REJECTED);
			}
			long amount = price * quantity;
			long orderId = sql.insertOrder(userId, key, "SELL", quantity, "FILLED", null);
			int held = ((Number) found.get(0).get("quantity")).intValue();
			long totalCost = ((Number) found.get(0).get("total_cost")).longValue();
			long cost = held == quantity ? totalCost : totalCost * quantity / held;
			if (held == quantity) {
				jdbc.update("delete from holdings where id = ?", found.get(0).get("id"));
			}
			else {
				jdbc.update("update holdings set quantity = ?, total_cost = ?, updated_at = ? where id = ?",
						held - quantity, totalCost - cost, sql.now(), found.get(0).get("id"));
			}
			jdbc.update("update wallets set cash = cash + ?, updated_at = ? where id = ?", amount, sql.now(), walletId);
			sql.insertLedger(walletId, "SELL", amount, cash + amount, orderId);
			sql.insertExecution(orderId, price, quantity, amount);
			return Outcome.of(Status.FILLED);
		});
	}

}
