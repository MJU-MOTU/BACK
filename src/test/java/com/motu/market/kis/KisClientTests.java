package com.motu.market.kis;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import com.motu.market.PriceSource.DailyCandle;
import com.motu.market.PriceSource.LiveQuote;
import com.motu.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KisClientTests {

	private static final String BASE = "https://kis.test";
	private static final String TOKEN = """
		{"access_token":"tkn-1","token_type":"Bearer","expires_in":86400}""";
	private static final String PRICE = """
		{"rt_cd":"0","msg1":"정상처리 되었습니다.","output":{"stck_prpr":"71200","prdy_vrss":"-800","prdy_ctrt":"-1.11","prdy_vrss_sign":"5"}}""";

	MutableClock clock;
	MockRestServiceServer server;
	KisClient client;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(Instant.parse("2026-10-29T01:00:00Z"));
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new KisClient(builder, new KisProperties(true, BASE, "key", "secret"), clock,
				new InMemoryKisTokenStore());
	}

	@Test
	void 현재가를_숫자로_바꾸고_토큰은_한_번만_받는다() {
		server.expect(ExpectedCount.once(), requestTo(BASE + "/oauth2/tokenP"))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess(TOKEN, MediaType.APPLICATION_JSON));
		server.expect(ExpectedCount.twice(), requestToUriTemplate(BASE
				+ "/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=005930"))
			.andExpect(header("authorization", "Bearer tkn-1"))
			.andExpect(header("tr_id", "FHKST01010100"))
			.andExpect(header("appkey", "key"))
			.andRespond(withSuccess(PRICE, MediaType.APPLICATION_JSON));

		assertThat(client.currentPrice("005930")).contains(new LiveQuote(71200, -800, new BigDecimal("-1.11")));
		assertThat(client.currentPrice("005930")).isPresent();
		server.verify();
	}

	@Test
	void 토큰은_만료_1시간_전이_되면_다시_받는다() {
		server.expect(requestTo(BASE + "/oauth2/tokenP")).andRespond(withSuccess(TOKEN, MediaType.APPLICATION_JSON));
		server.expect(requestTo(org.hamcrest.Matchers.containsString("inquire-price")))
			.andRespond(withSuccess(PRICE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/oauth2/tokenP"))
			.andRespond(withSuccess(TOKEN.replace("tkn-1", "tkn-2"), MediaType.APPLICATION_JSON));
		server.expect(requestTo(org.hamcrest.Matchers.containsString("inquire-price")))
			.andExpect(header("authorization", "Bearer tkn-2"))
			.andRespond(withSuccess(PRICE, MediaType.APPLICATION_JSON));

		client.currentPrice("005930");
		clock.advance(Duration.ofHours(23));
		client.currentPrice("005930");
		server.verify();
	}

	@Test
	void KIS가_실패하면_빈_값을_돌려준다() {
		server.expect(requestTo(BASE + "/oauth2/tokenP")).andRespond(withSuccess(TOKEN, MediaType.APPLICATION_JSON));
		server.expect(requestTo(org.hamcrest.Matchers.containsString("inquire-price"))).andRespond(withServerError());
		server.expect(requestTo(org.hamcrest.Matchers.containsString("inquire-price")))
			.andRespond(withSuccess("""
				{"rt_cd":"1","msg1":"초당 거래건수를 초과하였습니다."}""", MediaType.APPLICATION_JSON));

		assertThat(client.currentPrice("005930")).isEmpty();
		assertThat(client.currentPrice("005930")).isEmpty();
	}

	@Test
	void 토큰_발급이_실패하면_1분_동안은_다시_요청하지_않는다() {
		server.expect(ExpectedCount.once(), requestTo(BASE + "/oauth2/tokenP")).andRespond(withServerError());

		assertThat(client.currentPrice("005930")).isEmpty();
		clock.advance(KisClient.TOKEN_RETRY_BACKOFF.minusSeconds(1));
		assertThat(client.currentPrice("005930")).isEmpty();
		server.verify();

		server.reset();
		server.expect(requestTo(BASE + "/oauth2/tokenP")).andRespond(withSuccess(TOKEN, MediaType.APPLICATION_JSON));
		server.expect(requestTo(org.hamcrest.Matchers.containsString("inquire-price")))
			.andRespond(withSuccess(PRICE, MediaType.APPLICATION_JSON));
		clock.advance(Duration.ofSeconds(1));
		assertThat(client.currentPrice("005930")).isPresent();
		server.verify();
	}

	@Test
	void 일봉은_빈_행을_빼고_날짜_오름차순으로_돌려준다() {
		server.expect(requestTo(BASE + "/oauth2/tokenP")).andRespond(withSuccess(TOKEN, MediaType.APPLICATION_JSON));
		server.expect(requestTo(org.hamcrest.Matchers.containsString("inquire-daily-itemchartprice")))
			.andExpect(header("tr_id", "FHKST03010100"))
			.andExpect(queryParam("FID_INPUT_DATE_1", "20261001"))
			.andExpect(queryParam("FID_INPUT_DATE_2", "20261029"))
			.andExpect(queryParam("FID_PERIOD_DIV_CODE", "D"))
			.andRespond(withSuccess("""
				{"rt_cd":"0","output1":{"hts_kor_isnm":"삼성전자"},"output2":[
				 {"stck_bsop_date":"20261029","stck_oprc":"71000","stck_hgpr":"71500","stck_lwpr":"70800","stck_clpr":"71200","acml_vol":"1000"},
				 {"stck_bsop_date":"20261028","stck_oprc":"72000","stck_hgpr":"72100","stck_lwpr":"71900","stck_clpr":"72000","acml_vol":"2000"},
				 {}
				]}""", MediaType.APPLICATION_JSON));

		assertThat(client.dailyCandles("005930", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 29)))
			.hasValueSatisfying(candles -> assertThat(candles).containsExactly(
					new DailyCandle(LocalDate.of(2026, 10, 28), 72000, 72100, 71900, 72000, 2000),
					new DailyCandle(LocalDate.of(2026, 10, 29), 71000, 71500, 70800, 71200, 1000)));
	}

}
