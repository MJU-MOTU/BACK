package com.motu.experiment;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

// 세 전략이 같이 쓰는 INSERT 문
final class OrderSql {

	static final String STOCK = "066570";

	private final JdbcTemplate jdbc;

	OrderSql(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	boolean orderExists(long userId, String key) {
		Integer count = jdbc.queryForObject("select count(*) from orders where user_id = ? and idempotency_key = ?",
				Integer.class, userId, key);
		return count != null && count > 0;
	}

	long insertOrder(long userId, String key, String side, int quantity, String status, String rejectReason) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement("""
					insert into orders (user_id, stock_code, side, quantity, status, reject_reason, idempotency_key, created_at)
					values (?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
			ps.setLong(1, userId);
			ps.setString(2, STOCK);
			ps.setString(3, side);
			ps.setInt(4, quantity);
			ps.setString(5, status);
			ps.setString(6, rejectReason);
			ps.setString(7, key);
			ps.setTimestamp(8, Timestamp.from(Instant.now()));
			return ps;
		}, keys);
		return Objects.requireNonNull(keys.getKey()).longValue();
	}

	void insertLedger(long walletId, String type, long amount, long balanceAfter, long orderId) {
		jdbc.update("""
				insert into wallet_ledger (wallet_id, type, amount, balance_after, order_id, created_at)
				values (?, ?, ?, ?, ?, ?)""", walletId, type, amount, balanceAfter, orderId,
				Timestamp.from(Instant.now()));
	}

	void insertExecution(long orderId, long price, int quantity, long amount) {
		jdbc.update("""
				insert into executions (order_id, price, quantity, amount, price_source, realized_pnl, executed_at)
				values (?, ?, ?, ?, 'STORED', null, ?)""", orderId, price, quantity, amount,
				Timestamp.from(Instant.now()));
	}

	Timestamp now() {
		return Timestamp.from(Instant.now());
	}

}
