package com.motu.market;

import java.math.BigDecimal;
import java.time.Instant;

import com.motu.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 테스트에서는 KIS가 꺼져 있다(motu.kis.enabled=false). 저장된 가격만으로 동작하는지 본다
@IntegrationTest
class StockApiTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StockPriceRepository stockPriceRepository;

	@Test
	void 종목을_이름이나_코드_앞부분으로_찾는다() throws Exception {
		mockMvc.perform(get("/api/v1/stocks").param("query", "삼성").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].name").value(org.hamcrest.Matchers.hasItems("삼성전자", "삼성SDI")))
			.andExpect(jsonPath("$.items[*].name").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("카카오"))));

		mockMvc.perform(get("/api/v1/stocks").param("query", "0059").with(jwt()))
			.andExpect(jsonPath("$.items[0].code").value("005930"));
	}

	@Test
	void 시장으로_거르고_페이지로_나눈다() throws Exception {
		mockMvc.perform(get("/api/v1/stocks").param("market", "KOSDAQ").param("size", "3").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[*].market").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("KOSDAQ"))))
			.andExpect(jsonPath("$.totalElements").value(8));
	}

	@Test
	void KIS가_꺼져_있어도_저장된_가격이_있으면_STORED로_보여준다() throws Exception {
		stockPriceRepository.save(new StockPrice("000660", 180000, -2000, new BigDecimal("-1.10"),
				Instant.parse("2026-10-28T06:30:00Z")));

		mockMvc.perform(get("/api/v1/stocks/000660").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("SK하이닉스"))
			.andExpect(jsonPath("$.quote.price").value(180000))
			.andExpect(jsonPath("$.quote.source").value("STORED"));
	}

	@Test
	void 가격을_받은_적이_없으면_quote는_null이고_일봉은_503이다() throws Exception {
		mockMvc.perform(get("/api/v1/stocks/035720").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quote").isEmpty());

		mockMvc.perform(get("/api/v1/stocks/035720/candles").with(jwt()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("PRICE_UNAVAILABLE"));
	}

	@Test
	void 없는_종목은_404이고_로그인하지_않으면_401이다() throws Exception {
		mockMvc.perform(get("/api/v1/stocks/999999").with(jwt()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));

		mockMvc.perform(get("/api/v1/stocks"))
			.andExpect(status().isUnauthorized());
	}

}
