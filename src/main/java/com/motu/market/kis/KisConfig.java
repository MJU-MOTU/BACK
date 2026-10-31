package com.motu.market.kis;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import com.motu.market.PriceSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

@Configuration
public class KisConfig {

	// 토큰 발급은 DB 행을 잠근 채로 KIS를 부르므로, KIS가 응답하지 않아도 오래 붙잡지 않게 시간을 정한다
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

	@Bean
	PriceSource priceSource(KisProperties properties, Clock clock, JdbcTemplate jdbcTemplate,
			PlatformTransactionManager transactionManager) {
		if (!properties.enabled()) {
			return PriceSource.none();
		}
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
		requestFactory.setReadTimeout(READ_TIMEOUT);
		return new KisClient(RestClient.builder().requestFactory(requestFactory), properties, clock,
				new JdbcKisTokenStore(jdbcTemplate, transactionManager));
	}

}
