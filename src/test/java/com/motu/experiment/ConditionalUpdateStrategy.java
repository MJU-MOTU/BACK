package com.motu.experiment;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// C. 조건부 UPDATE: 읽고 판단하지 않고, 조건을 WHERE에 넣어 한 문장으로 바꾼다. 바뀐 행이 0이면 조건이 안 맞은 것이다.
// 같은 키는 주문을 먼저 INSERT해서 UNIQUE 제약으로 막는다
class ConditionalUpdateStrategy implements OrderStrategy {

	private final JdbcTemplate jdbc;
	private final OrderSql sql;
	private final TransactionTemplate tx;

	ConditionalUpdateStrategy(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
		this.jdbc = jdbc;
		this.sql = new OrderSql(jdbc);
		this.tx = new TransactionTemplate(txManager);
		this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	@Override
	public String name() {
		return "C 조건부 UPDATE";
	}

	@Override
	public Outcome buy(long userId, String key, int quantity, long price) {
		return tx.execute(status -> {
			long orderId;
			try {
				orderId = sql.insertOrder(userId, key, "BUY", quantity, "FILLED", null);
			}
			catch (DuplicateKeyException e) {
				return Outcome.of(Status.REPLAYED);
			}
			long amount = price * quantity;
			if (jdbc.update("update wallets set cash = cash - ?, updated_at = ? where user_id = ? and cash >= ?",
					amount, sql.now(), userId, amount) == 0) {
				reject(orderId, "INSUFFICIENT_CASH");
				return Outcome.of(Status.REJECTED);
			}
			// 방금 바꾼 행은 이 트랜잭션이 잠그고 있으므로 읽은 값이 곧 바뀐 뒤 잔고다
			var wallet = jdbc.queryForMap("select id, cash from wallets where user_id = ?", userId);
			sql.insertLedger(((Number) wallet.get("id")).longValue(), "BUY", -amount,
					((Number) wallet.get("cash")).longValue(), orderId);
			jdbc.update("""
					insert into holdings (user_id, stock_code, quantity, total_cost, updated_at)
					values (?, ?, ?, ?, ?) as new
					on duplicate key update quantity = holdings.quantity + new.quantity,
					                        total_cost = holdings.total_cost + new.total_cost,
					                        updated_at = new.updated_at""",
					userId, OrderSql.STOCK, quantity, amount, sql.now());
			sql.insertExecution(orderId, price, quantity, amount);
			return Outcome.of(Status.FILLED);
		});
	}

	@Override
	public Outcome sell(long userId, String key, int quantity, long price) {
		return tx.execute(status -> {
			long orderId;
			try {
				orderId = sql.insertOrder(userId, key, "SELL", quantity, "FILLED", null);
			}
			catch (DuplicateKeyException e) {
				return Outcome.of(Status.REPLAYED);
			}
			// 전부 팔면 행을 지우고(CHECK quantity > 0), 일부면 줄인다. 원가는 줄어드는 수량 비율만큼(내림)
			int changed = jdbc.update("delete from holdings where user_id = ? and stock_code = ? and quantity = ?",
					userId, OrderSql.STOCK, quantity);
			if (changed == 0) {
				changed = jdbc.update("""
						update holdings set total_cost = total_cost - (total_cost * ? div quantity),
						                    quantity = quantity - ?, updated_at = ?
						where user_id = ? and stock_code = ? and quantity > ?""", quantity, quantity, sql.now(), userId,
						OrderSql.STOCK, quantity);
			}
			if (changed == 0) {
				reject(orderId, "INSUFFICIENT_QUANTITY");
				return Outcome.of(Status.REJECTED);
			}
			long amount = price * quantity;
			jdbc.update("update wallets set cash = cash + ?, updated_at = ? where user_id = ?", amount, sql.now(),
					userId);
			var wallet = jdbc.queryForMap("select id, cash from wallets where user_id = ?", userId);
			sql.insertLedger(((Number) wallet.get("id")).longValue(), "SELL", amount,
					((Number) wallet.get("cash")).longValue(), orderId);
			sql.insertExecution(orderId, price, quantity, amount);
			return Outcome.of(Status.FILLED);
		});
	}

	private void reject(long orderId, String reason) {
		jdbc.update("update orders set status = 'REJECTED', reject_reason = ? where id = ?", reason, orderId);
	}

}
