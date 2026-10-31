package com.motu.market.kis;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.motu.market.PriceSource;
import com.motu.market.kis.KisTokenStore.TokenState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// 한국투자증권 Open API 클라이언트. 실패하면 빈 값을 돌려주고 로그만 남긴다
public class KisClient implements PriceSource {

	private static final Logger log = LoggerFactory.getLogger(KisClient.class);

	private static final String PRICE_TR_ID = "FHKST01010100";
	private static final String DAILY_CHART_TR_ID = "FHKST03010100";
	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

	// 토큰은 만료 1시간 전까지 다시 쓴다. 발급 요청을 자주 보내면 KIS가 막는다
	private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofHours(1);

	// 발급이 실패하면 이 시간 동안은 다시 요청하지 않는다. 시세 요청마다 발급을 재시도하면 발급 제한에 걸린다
	static final Duration TOKEN_RETRY_BACKOFF = Duration.ofMinutes(1);

	private final RestClient restClient;
	private final KisProperties properties;
	private final Clock clock;
	private final KisTokenStore tokenStore;

	// 저장소에서 마지막으로 읽은 상태. 쓸 수 있거나 재시도 대기 중이면 저장소에 묻지 않는다
	private TokenState cachedToken = TokenState.EMPTY;

	public KisClient(RestClient.Builder builder, KisProperties properties, Clock clock, KisTokenStore tokenStore) {
		this.restClient = builder.baseUrl(properties.baseUrl()).build();
		this.properties = properties;
		this.clock = clock;
		this.tokenStore = tokenStore;
	}

	@Override
	public Optional<LiveQuote> currentPrice(String code) {
		try {
			PriceResponse response = restClient.get()
				.uri(uri -> uri.path("/uapi/domestic-stock/v1/quotations/inquire-price")
					.queryParam("FID_COND_MRKT_DIV_CODE", "J")
					.queryParam("FID_INPUT_ISCD", code)
					.build())
				.headers(headers -> headers.setAll(quotationHeaders(PRICE_TR_ID)))
				.retrieve()
				.body(PriceResponse.class);
			if (response == null || !"0".equals(response.rtCd()) || response.output() == null) {
				log.warn("KIS 현재가 실패 code={} msg={}", code, response == null ? null : response.msg());
				return Optional.empty();
			}
			PriceResponse.Output output = response.output();
			return Optional.of(new LiveQuote(Long.parseLong(output.price()), Long.parseLong(output.changeAmount()),
					new BigDecimal(output.changeRate())));
		}
		catch (RestClientException | NumberFormatException e) {
			log.warn("KIS 현재가 실패 code={}", code, e);
			return Optional.empty();
		}
	}

	@Override
	public Optional<List<DailyCandle>> dailyCandles(String code, LocalDate from, LocalDate to) {
		try {
			ChartResponse response = restClient.get()
				.uri(uri -> uri.path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
					.queryParam("FID_COND_MRKT_DIV_CODE", "J")
					.queryParam("FID_INPUT_ISCD", code)
					.queryParam("FID_INPUT_DATE_1", from.format(YYYYMMDD))
					.queryParam("FID_INPUT_DATE_2", to.format(YYYYMMDD))
					.queryParam("FID_PERIOD_DIV_CODE", "D")
					.queryParam("FID_ORG_ADJ_PRC", "0")
					.build())
				.headers(headers -> headers.setAll(quotationHeaders(DAILY_CHART_TR_ID)))
				.retrieve()
				.body(ChartResponse.class);
			if (response == null || !"0".equals(response.rtCd()) || response.bars() == null) {
				log.warn("KIS 일봉 실패 code={} msg={}", code, response == null ? null : response.msg());
				return Optional.empty();
			}
			// KIS는 최신순으로 주고, 거래가 없는 날은 빈 행이 섞일 수 있다
			List<DailyCandle> candles = response.bars().stream()
				.filter(bar -> bar.date() != null && !bar.date().isBlank())
				.map(ChartResponse.Bar::toCandle)
				.sorted(Comparator.comparing(DailyCandle::date))
				.toList();
			return Optional.of(candles);
		}
		catch (RestClientException | NumberFormatException e) {
			log.warn("KIS 일봉 실패 code={}", code, e);
			return Optional.empty();
		}
	}

	private Map<String, String> quotationHeaders(String trId) {
		return Map.of(
				"authorization", "Bearer " + accessToken(),
				"appkey", properties.appKey(),
				"appsecret", properties.appSecret(),
				"tr_id", trId,
				"custtype", "P");
	}

	// 서버(Lambda 실행 환경)가 여러 개라 토큰은 저장소에 두고 같이 쓴다. 저장소에도 쓸 토큰이 없을 때만 발급한다
	private synchronized String accessToken() {
		Instant now = clock.instant();
		if (!cachedToken.usableAt(now) && !cachedToken.waitingAt(now)) {
			cachedToken = tokenStore.update(current -> {
				// 기다리는 사이에 다른 서버가 받아 뒀거나, 다른 서버의 발급 실패로 대기 중이면 그대로 쓴다
				if (current.usableAt(now) || current.waitingAt(now)) {
					return current;
				}
				return issueToken(current, now);
			});
		}
		if (!cachedToken.usableAt(now)) {
			throw new RestClientException("KIS 토큰 발급 재시도 대기 중");
		}
		return cachedToken.accessToken();
	}

	private TokenState issueToken(TokenState current, Instant now) {
		try {
			TokenResponse token = restClient.post()
				.uri("/oauth2/tokenP")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("grant_type", "client_credentials", "appkey", properties.appKey(), "appsecret",
						properties.appSecret()))
				.retrieve()
				.body(TokenResponse.class);
			if (token == null || token.accessToken() == null) {
				throw new RestClientException("KIS 토큰 발급 응답이 비어 있습니다");
			}
			return new TokenState(token.accessToken(),
					now.plusSeconds(token.expiresIn()).minus(TOKEN_REFRESH_MARGIN), null);
		}
		catch (RestClientException e) {
			log.warn("KIS 토큰 발급 실패. {} 동안 다시 요청하지 않습니다", TOKEN_RETRY_BACKOFF, e);
			return new TokenState(current.accessToken(), current.refreshAt(), now.plus(TOKEN_RETRY_BACKOFF));
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TokenResponse(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") long expiresIn) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record PriceResponse(@JsonProperty("rt_cd") String rtCd, @JsonProperty("msg1") String msg, Output output) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Output(
				@JsonProperty("stck_prpr") String price,
				@JsonProperty("prdy_vrss") String changeAmount,
				@JsonProperty("prdy_ctrt") String changeRate) {
		}

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ChartResponse(@JsonProperty("rt_cd") String rtCd, @JsonProperty("msg1") String msg,
			@JsonProperty("output2") List<Bar> bars) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Bar(
				@JsonProperty("stck_bsop_date") String date,
				@JsonProperty("stck_oprc") String open,
				@JsonProperty("stck_hgpr") String high,
				@JsonProperty("stck_lwpr") String low,
				@JsonProperty("stck_clpr") String close,
				@JsonProperty("acml_vol") String volume) {

			DailyCandle toCandle() {
				return new DailyCandle(LocalDate.parse(date, YYYYMMDD), Long.parseLong(open), Long.parseLong(high),
						Long.parseLong(low), Long.parseLong(close), Long.parseLong(volume));
			}

		}

	}

}
