package com.motu.support;

import java.util.UUID;

import com.motu.auth.AuthDtos.SignupRequest;
import com.motu.auth.AuthService;

import org.springframework.stereotype.Component;

// 테스트마다 새 회원(지갑 포함)을 만든다. 통합 테스트는 DB를 공유하므로 회원을 나눠 서로 영향을 주지 않게 한다
@Component
public class TestUsers {

	private final AuthService authService;

	TestUsers(AuthService authService) {
		this.authService = authService;
	}

	public Long create() {
		return authService
			.signup(new SignupRequest("user-" + UUID.randomUUID() + "@motu.test", "motu-pass-1234", "테스터"))
			.userId();
	}

}
