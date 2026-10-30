package com.motu.trading;

import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

// 같은 회원의 주문을 DB 커넥션을 빌리기 전에 JVM 안에서 먼저 줄 세운다.
// DB의 지갑 잠금(FOR UPDATE)만 쓰면, 잠금을 기다리는 요청들이 커넥션을 쥔 채 기다려서 풀이 바닥나고
// 다른 회원의 주문까지 커넥션을 못 받는다. 서버가 여러 대면 이 잠금은 서버 안에서만 통하므로 정확성은 여전히 DB 잠금이 맡는다.
// 회원 수만큼 잠금을 만들지 않도록 회원 ID를 STRIPES개로 나눠 공유한다(다른 회원이 가끔 같은 잠금을 쓸 수 있다)
@Component
class UserOrderLocks {

	private static final int STRIPES = 1024;

	private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

	UserOrderLocks() {
		for (int i = 0; i < STRIPES; i++) {
			locks[i] = new ReentrantLock();
		}
	}

	ReentrantLock lockFor(Long userId) {
		return locks[Math.floorMod(Long.hashCode(userId), STRIPES)];
	}

}
