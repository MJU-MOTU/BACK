package com.motu.market.kis;

import java.util.function.UnaryOperator;

// 단위 테스트용. 서버 한 대 안에서만 공유된다
class InMemoryKisTokenStore implements KisTokenStore {

	private TokenState state = TokenState.EMPTY;

	@Override
	public synchronized TokenState update(UnaryOperator<TokenState> update) {
		state = update.apply(state);
		return state;
	}

}
