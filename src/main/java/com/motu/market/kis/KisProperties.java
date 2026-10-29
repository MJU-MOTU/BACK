package com.motu.market.kis;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("motu.kis")
public record KisProperties(boolean enabled, String baseUrl, String appKey, String appSecret) {

	public KisProperties {
		if (enabled && (isBlank(appKey) || isBlank(appSecret))) {
			throw new IllegalStateException("KIS_ENABLED=true이면 KIS_APP_KEY와 KIS_APP_SECRET이 필요합니다");
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
