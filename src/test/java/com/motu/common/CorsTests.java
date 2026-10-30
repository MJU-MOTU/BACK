package com.motu.common;

import com.motu.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 프론트를 다른 도메인에 배포했을 때 브라우저가 보내는 요청 (허용 출처는 application-test.yml)
@IntegrationTest
class CorsTests {

	private static final String FRONT = "https://front.motu.test";

	@Autowired
	MockMvc mockMvc;

	@Test
	void 허용한_출처의_주문_사전요청은_멱등키_헤더까지_허용한다() throws Exception {
		mockMvc.perform(options("/api/v1/orders")
				.header(HttpHeaders.ORIGIN, FRONT)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,idempotency-key"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
					containsStringIgnoringCase("idempotency-key")))
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
	}

	@Test
	void 허용하지_않은_출처의_사전요청은_403이다() throws Exception {
		mockMvc.perform(options("/api/v1/orders")
				.header(HttpHeaders.ORIGIN, "https://evil.example")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void 토큰이_없어_401이어도_CORS_헤더가_붙어_프론트가_응답을_읽을_수_있다() throws Exception {
		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.ORIGIN, FRONT))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT));
	}

}
