package com.motu.market.kis;

import java.time.Clock;

import com.motu.market.PriceSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class KisConfig {

	@Bean
	PriceSource priceSource(KisProperties properties, Clock clock) {
		if (!properties.enabled()) {
			return PriceSource.none();
		}
		return new KisClient(RestClient.builder(), properties, clock);
	}

}
