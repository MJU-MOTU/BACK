package com.motu.market.kis;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.function.UnaryOperator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// kis_access_tokens의 한 행을 잠가 서버 사이의 발급 순서를 정한다. 시각은 다른 테이블처럼 UTC로 저장한다
public class JdbcKisTokenStore implements KisTokenStore {

	private final JdbcTemplate jdbcTemplate;
	private final TransactionTemplate transactionTemplate;

	public JdbcKisTokenStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
		this.jdbcTemplate = jdbcTemplate;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		// 시세 조회가 다른 트랜잭션(주문 준비 등) 안에서 일어나도 토큰 행 잠금은 발급이 끝나면 바로 푼다
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Override
	public TokenState update(UnaryOperator<TokenState> update) {
		return transactionTemplate.execute(status -> {
			TokenState current = jdbcTemplate.queryForObject(
					"SELECT access_token, refresh_at, retry_after FROM kis_access_tokens WHERE id = 1 FOR UPDATE",
					(rs, rowNum) -> new TokenState(rs.getString("access_token"),
							toInstant(rs.getObject("refresh_at", LocalDateTime.class)),
							toInstant(rs.getObject("retry_after", LocalDateTime.class))));
			TokenState next = update.apply(current);
			if (!Objects.equals(next, current)) {
				jdbcTemplate.update(
						"UPDATE kis_access_tokens SET access_token = ?, refresh_at = ?, retry_after = ?, updated_at = UTC_TIMESTAMP(6) WHERE id = 1",
						next.accessToken(), toUtc(next.refreshAt()), toUtc(next.retryAfter()));
			}
			return next;
		});
	}

	private static Instant toInstant(LocalDateTime utc) {
		return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
	}

	private static LocalDateTime toUtc(Instant instant) {
		return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
