package com.motu.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("motu.jwt")
public record JwtProperties(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {

	// HS256 키는 32바이트 이상이어야 한다. 비밀키 없이 배포되는 일을 막으려고 시작할 때 바로 실패시킨다
	public JwtProperties {
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException("motu.jwt.secret(JWT_SECRET)은 32바이트 이상이어야 합니다");
		}
	}

}
