package com.motu.market.kis;

import java.time.Instant;
import java.util.function.UnaryOperator;

// KIS 접근 토큰을 서버 여러 대가 같이 쓰도록 보관한다
public interface KisTokenStore {

	// 저장된 상태를 잠근 채로 읽어 update에 넘기고, 돌려받은 상태로 바꾼다. 동시에 불려도 한 번에 하나씩 실행된다
	TokenState update(UnaryOperator<TokenState> update);

	// 값이 없으면 null이다
	record TokenState(String accessToken, Instant refreshAt, Instant retryAfter) {

		static final TokenState EMPTY = new TokenState(null, null, null);

		boolean usableAt(Instant now) {
			return accessToken != null && refreshAt != null && now.isBefore(refreshAt);
		}

		boolean waitingAt(Instant now) {
			return retryAfter != null && now.isBefore(retryAfter);
		}

	}

}
