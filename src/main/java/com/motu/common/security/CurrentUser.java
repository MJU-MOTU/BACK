package com.motu.common.security;

import org.springframework.security.oauth2.jwt.Jwt;

// Access 토큰의 subject가 회원 ID다
public final class CurrentUser {

	private CurrentUser() {
	}

	public static Long id(Jwt jwt) {
		return Long.valueOf(jwt.getSubject());
	}

}
