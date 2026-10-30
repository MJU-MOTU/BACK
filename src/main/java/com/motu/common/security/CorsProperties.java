package com.motu.common.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 프론트를 다른 도메인에 배포하면 브라우저가 CORS 허용을 확인한다. 비어 있으면 다른 출처 요청을 모두 막는다
@ConfigurationProperties("motu.cors")
public record CorsProperties(List<String> allowedOrigins) {

	public CorsProperties {
		allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
	}

}
